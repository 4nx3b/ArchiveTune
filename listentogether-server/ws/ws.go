// Package ws is a minimal RFC 6455 WebSocket implementation built on the Go
// standard library only, so the server builds and runs without fetching any
// module. It covers exactly what the Listen Together protocol needs:
//
//   - the HTTP 101 handshake (server Upgrade and a Dial client for tests)
//   - text and binary data frames, client->server masking
//   - fragmented messages (continuation frames)
//   - ping/pong keepalive (inbound pings are answered inline)
//   - close frames, message size limits, read/write/idle deadlines
//
// The reference deployment (BitChord, GPL-3.0) uses gorilla/websocket; this
// package reimplements the subset its party server exercises so the module
// tree stays dependency-free:
// https://github.com/kushagrasinghx/BitChord
package ws

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// WebSocket frame opcodes (RFC 6455 §5.2).
const (
	OpcodeContinuation = 0x0
	OpcodeText         = 0x1
	OpcodeBinary       = 0x2
	OpcodeClose        = 0x8
	OpcodePing         = 0x9
	OpcodePong         = 0xA
)

// keyGUID is the RFC 6455 handshake GUID.
const keyGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// ErrClosed is returned once the connection has been closed locally.
var ErrClosed = errors.New("ws: connection closed")

// CloseError is returned from ReadMessage when the peer sent a close frame.
type CloseError struct {
	Code   int
	Reason string
}

func (e *CloseError) Error() string {
	return fmt.Sprintf("ws: peer closed: code=%d reason=%q", e.Code, e.Reason)
}

// Limits are applied per connection.
type Limits struct {
	MaxMessageSize   int64 // largest accumulated inbound message in bytes
	IdleTimeout      time.Duration
	WriteTimeout     time.Duration
	HandshakeTimeout time.Duration
}

func (l Limits) withDefaults() Limits {
	if l.MaxMessageSize <= 0 {
		l.MaxMessageSize = 1 << 18
	}
	if l.IdleTimeout <= 0 {
		l.IdleTimeout = 150 * time.Second
	}
	if l.WriteTimeout <= 0 {
		l.WriteTimeout = 10 * time.Second
	}
	if l.HandshakeTimeout <= 0 {
		l.HandshakeTimeout = 10 * time.Second
	}
	return l
}

// Conn is a WebSocket connection. Writes are serialised by an internal mutex;
// every write carries its own deadline, so a stalled peer never blocks the
// room for longer than WriteTimeout.
type Conn struct {
	mu        sync.Mutex
	conn      net.Conn
	reader    *bufio.Reader
	limits    Limits
	maskOut   bool // true on the client side: outbound frames must be masked
	closeMu   sync.Mutex
	closed    bool
	closeSent bool
}

// Upgrade performs the server side of the WebSocket handshake over an
// hijacked HTTP connection.
func Upgrade(w http.ResponseWriter, r *http.Request, limits Limits) (*Conn, error) {
	limits = limits.withDefaults()
	if r.Method != http.MethodGet {
		http.Error(w, "websocket handshake requires GET", http.StatusMethodNotAllowed)
		return nil, errors.New("ws: bad method")
	}
	if !headerContainsToken(r.Header, "Connection", "upgrade") {
		http.Error(w, `"Connection: Upgrade" header required`, http.StatusBadRequest)
		return nil, errors.New("ws: no connection upgrade")
	}
	if !headerContainsToken(r.Header, "Upgrade", "websocket") {
		http.Error(w, `"Upgrade: websocket" header required`, http.StatusBadRequest)
		return nil, errors.New("ws: no upgrade token")
	}
	if v := r.Header.Get("Sec-WebSocket-Version"); v != "13" {
		w.Header().Set("Sec-WebSocket-Version", "13")
		http.Error(w, "unsupported websocket version", http.StatusUpgradeRequired)
		return nil, errors.New("ws: unsupported version")
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		http.Error(w, "missing Sec-WebSocket-Key", http.StatusBadRequest)
		return nil, errors.New("ws: missing key")
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "server does not support hijacking", http.StatusInternalServerError)
		return nil, errors.New("ws: not a hijacker")
	}
	netConn, brw, err := hijacker.Hijack()
	if err != nil {
		return nil, err
	}

	// The hijacked connection may still carry the server's header-read
	// deadline; clear it before writing the 101, then rely on per-frame
	// deadlines afterwards.
	_ = netConn.SetDeadline(time.Time{})
	_ = netConn.SetWriteDeadline(time.Now().Add(limits.HandshakeTimeout))
	accept := computeAcceptKey(key)
	response := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
	if _, err := netConn.Write([]byte(response)); err != nil {
		_ = netConn.Close()
		return nil, err
	}
	_ = netConn.SetWriteDeadline(time.Time{})

	var reader *bufio.Reader
	if brw != nil && brw.Reader != nil {
		reader = brw.Reader
	} else {
		reader = bufio.NewReader(netConn)
	}
	return &Conn{conn: netConn, reader: reader, limits: limits, maskOut: false}, nil
}

// Dial opens a client WebSocket connection. Scheme ws:// over TCP; wss:// is
// supported with TLS. Used by the tests; production traffic arrives through
// Upgrade.
func Dial(rawURL string, extraHeaders http.Header, limits Limits) (*Conn, *http.Response, error) {
	limits = limits.withDefaults()
	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, nil, err
	}
	var (
		netConn net.Conn
	)
	secure := u.Scheme == "wss"
	switch u.Scheme {
	case "ws", "http":
	case "wss", "https":
		secure = true
	default:
		return nil, nil, fmt.Errorf("ws: unsupported scheme %q", u.Scheme)
	}
	host := u.Host
	if !strings.Contains(host, ":") {
		if secure {
			host += ":443"
		} else {
			host += ":80"
		}
	}
	if secure {
		netConn, err = tls.Dial("tcp", host, &tls.Config{ServerName: u.Hostname()})
	} else {
		netConn, err = net.Dial("tcp", host)
	}
	if err != nil {
		return nil, nil, err
	}

	keyBytes := make([]byte, 16)
	if _, err := rand.Read(keyBytes); err != nil {
		_ = netConn.Close()
		return nil, nil, err
	}
	key := base64.StdEncoding.EncodeToString(keyBytes)

	path := u.RequestURI()
	if path == "" {
		path = "/"
	}
	var req strings.Builder
	fmt.Fprintf(&req, "GET %s HTTP/1.1\r\n", path)
	fmt.Fprintf(&req, "Host: %s\r\n", u.Host)
	req.WriteString("Upgrade: websocket\r\n")
	req.WriteString("Connection: Upgrade\r\n")
	fmt.Fprintf(&req, "Sec-WebSocket-Key: %s\r\n", key)
	req.WriteString("Sec-WebSocket-Version: 13\r\n")
	for k, vals := range extraHeaders {
		for _, v := range vals {
			fmt.Fprintf(&req, "%s: %s\r\n", k, v)
		}
	}
	req.WriteString("\r\n")

	_ = netConn.SetWriteDeadline(time.Now().Add(limits.HandshakeTimeout))
	if _, err := netConn.Write([]byte(req.String())); err != nil {
		_ = netConn.Close()
		return nil, nil, err
	}
	_ = netConn.SetWriteDeadline(time.Time{})

	readReq, _ := http.NewRequest(http.MethodGet, rawURL, nil)
	br := bufio.NewReader(netConn)
	resp, err := http.ReadResponse(br, readReq)
	if err != nil {
		_ = netConn.Close()
		return nil, nil, err
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		_ = netConn.Close()
		return nil, resp, fmt.Errorf("ws: unexpected status %d", resp.StatusCode)
	}
	if !strings.EqualFold(strings.TrimSpace(resp.Header.Get("Upgrade")), "websocket") {
		_ = netConn.Close()
		return nil, resp, errors.New("ws: missing upgrade in response")
	}
	if resp.Header.Get("Sec-WebSocket-Accept") != computeAcceptKey(key) {
		_ = netConn.Close()
		return nil, resp, errors.New("ws: bad Sec-WebSocket-Accept")
	}
	_ = resp.Body.Close()

	// Reuse the response reader so any bytes the server framed after the 101
	// are not lost.
	conn := &Conn{conn: netConn, reader: br, limits: limits, maskOut: true}
	return conn, resp, nil
}

// ReadMessage blocks until a complete data message (text or binary) arrives.
// Control frames are handled transparently: pings are answered inline, pongs
// are dropped, and a close frame terminates the read with *CloseError after
// echoing the close.
func (c *Conn) ReadMessage() (opcode int, data []byte, err error) {
	var (
		assembling int
		buf        bytes.Buffer
	)
	for {
		if err := c.conn.SetReadDeadline(time.Now().Add(c.limits.IdleTimeout)); err != nil {
			return 0, nil, err
		}
		var head [2]byte
		if _, err := io.ReadFull(c.reader, head[:]); err != nil {
			return 0, nil, err
		}
		fin := head[0]&0x80 != 0
		op := int(head[0] & 0x0F)
		masked := head[1]&0x80 != 0
		length := int64(head[1] & 0x7F)

		switch length {
		case 126:
			var ext [2]byte
			if _, err := io.ReadFull(c.reader, ext[:]); err != nil {
				return 0, nil, err
			}
			length = int64(binary.BigEndian.Uint16(ext[:]))
		case 127:
			var ext [8]byte
			if _, err := io.ReadFull(c.reader, ext[:]); err != nil {
				return 0, nil, err
			}
			length = int64(binary.BigEndian.Uint64(ext[:]))
		}

		var maskKey [4]byte
		if masked {
			if _, err := io.ReadFull(c.reader, maskKey[:]); err != nil {
				return 0, nil, err
			}
		}

		if int64(buf.Len())+length > c.limits.MaxMessageSize {
			_ = c.writeControlFrame(OpcodeClose, closePayload(1009, "message too big"))
			return 0, nil, errors.New("ws: message exceeds size limit")
		}

		payload := make([]byte, length)
		if _, err := io.ReadFull(c.reader, payload); err != nil {
			return 0, nil, err
		}
		if masked {
			maskBytes(maskKey, payload)
		}

		switch op {
		case OpcodePing:
			// Control frames must be answered even between fragments.
			if err := c.writeControlFrame(OpcodePong, payload); err != nil {
				return 0, nil, err
			}
			continue
		case OpcodePong:
			continue
		case OpcodeClose:
			code := 1005
			reason := ""
			if len(payload) >= 2 {
				code = int(binary.BigEndian.Uint16(payload[:2]))
				reason = string(payload[2:])
			}
			c.sendClose(code)
			return 0, nil, &CloseError{Code: code, Reason: reason}
		case OpcodeText, OpcodeBinary:
			if assembling != 0 {
				return 0, nil, errors.New("ws: new data frame during fragmented message")
			}
			assembling = op
			buf.Write(payload)
			if fin {
				return assembling, buf.Bytes(), nil
			}
		case OpcodeContinuation:
			if assembling == 0 {
				return 0, nil, errors.New("ws: continuation without start frame")
			}
			buf.Write(payload)
			if fin {
				return assembling, buf.Bytes(), nil
			}
		default:
			return 0, nil, fmt.Errorf("ws: reserved opcode 0x%x", op)
		}
	}
}

// WriteMessage sends one complete data message.
func (c *Conn) WriteMessage(opcode int, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.isClosed() {
		return ErrClosed
	}
	return c.writeFrameLocked(opcode, data, true)
}

// WriteControl sends a control frame (payload must be ≤ 125 bytes).
func (c *Conn) WriteControl(opcode int, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.isClosed() {
		return ErrClosed
	}
	return c.writeControlFrame(opcode, data)
}

func (c *Conn) writeControlFrame(opcode int, data []byte) error {
	if len(data) > 125 {
		data = data[:125]
	}
	return c.writeFrameLocked(opcode, data, true)
}

func (c *Conn) writeFrameLocked(opcode int, data []byte, fin bool) error {
	_ = c.conn.SetWriteDeadline(time.Now().Add(c.limits.WriteTimeout))
	defer func() { _ = c.conn.SetWriteDeadline(time.Time{}) }()

	var head []byte
	first := byte(opcode & 0x0F)
	if fin {
		first |= 0x80
	}
	n := len(data)
	switch {
	case n <= 125:
		head = []byte{first, byte(n)}
	case n <= 0xFFFF:
		head = []byte{first, 126, byte(n >> 8), byte(n)}
	default:
		head = make([]byte, 10)
		head[0] = first
		head[1] = 127
		binary.BigEndian.PutUint64(head[2:], uint64(n))
	}

	var maskKey [4]byte
	if c.maskOut {
		head[1] |= 0x80
		if _, err := rand.Read(maskKey[:]); err != nil {
			return err
		}
	}

	frame := make([]byte, 0, len(head)+4+len(data))
	frame = append(frame, head...)
	if c.maskOut {
		frame = append(frame, maskKey[:]...)
	}
	if c.maskOut && len(data) > 0 {
		masked := make([]byte, len(data))
		copy(masked, data)
		maskBytes(maskKey, masked)
		frame = append(frame, masked...)
	} else {
		frame = append(frame, data...)
	}
	_, err := c.conn.Write(frame)
	return err
}

// sendClose transmits a close frame at most once, best effort.
func (c *Conn) sendClose(code int) {
	c.closeMu.Lock()
	alreadySent := c.closeSent
	c.closeSent = true
	c.closeMu.Unlock()
	if alreadySent {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.writeControlFrame(OpcodeClose, closePayload(code, ""))
}

// Close sends a normal close frame (when not already sent) and tears down the
// underlying connection.
func (c *Conn) Close() error {
	c.closeMu.Lock()
	c.closed = true
	c.closeMu.Unlock()
	c.sendClose(1000)
	return c.conn.Close()
}

// CloseNow tears down the TCP connection without a close frame (for tests and
// simulating drops).
func (c *Conn) CloseNow() error {
	c.closeMu.Lock()
	c.closed = true
	c.closeMu.Unlock()
	return c.conn.Close()
}

func (c *Conn) isClosed() bool {
	c.closeMu.Lock()
	defer c.closeMu.Unlock()
	return c.closed
}

// LocalAddr / RemoteAddr expose the underlying transport addresses.
func (c *Conn) LocalAddr() net.Addr  { return c.conn.LocalAddr() }
func (c *Conn) RemoteAddr() net.Addr { return c.conn.RemoteAddr() }

func maskBytes(key [4]byte, data []byte) {
	for i := range data {
		data[i] ^= key[i&3]
	}
}

func closePayload(code int, reason string) []byte {
	p := make([]byte, 2+len(reason))
	binary.BigEndian.PutUint16(p, uint16(code))
	copy(p[2:], reason)
	if len(p) > 125 {
		p = p[:125]
	}
	return p
}

func computeAcceptKey(key string) string {
	h := sha1.New()
	h.Write([]byte(key))
	h.Write([]byte(keyGUID))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

// headerContainsToken reports whether any comma-separated value of header
// `name` case-insensitively contains `token`.
func headerContainsToken(h http.Header, name string, token string) bool {
	for _, v := range h.Values(name) {
		for _, part := range strings.Split(v, ",") {
			if strings.EqualFold(strings.TrimSpace(part), token) {
				return true
			}
		}
	}
	return false
}
