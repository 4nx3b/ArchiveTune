// ArchiveTune — Listen Together server entry point.
//
// Serves:
//   - the BitChord-style REST surface for ops (/healthz, /api/time, /api/stats)
//   - a WebSocket endpoint speaking the ArchiveTune (vivi-music) JSON wire
//     protocol, on ANY path — the app connects to the server URL verbatim
//     (wss://host or wss://host/ws both work)
//
// The server layout (REST + hub + party + codes + clock + config packages,
// the reaper loop, the per-IP create limiter) is ported from BitChord's
// Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
//
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"moe.rukamori.archivetune/listentogether-server/clock"
	"moe.rukamori.archivetune/listentogether-server/config"
	"moe.rukamori.archivetune/listentogether-server/hub"
	"moe.rukamori.archivetune/listentogether-server/party"
	"moe.rukamori.archivetune/listentogether-server/protocol"
	"moe.rukamori.archivetune/listentogether-server/ws"
)

var (
	store         = party.NewStore()
	theHub        = hub.NewHub()
	uptime        = time.Now()
	createLimiter = newIPRateLimiter(time.Minute, config.CreateRatePerMinute, config.RateLimitMaxEntries)
)

func main() {
	go sweepLoop()

	addr := fmt.Sprintf("0.0.0.0:%d", config.Port)
	log.Printf("ArchiveTune Listen Together server starting on %s (max %d rooms, %d members each)", addr, config.MaxRooms, config.MaxMembers)
	server := &http.Server{
		Addr:              addr,
		Handler:           newRouter(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       90 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("server stopped: %v", err)
	}
}

// newRouter wires the REST surface and the catch-all WebSocket handler. It is
// shared by main and the integration tests.
func newRouter() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", handleHealthz)
	mux.HandleFunc("GET /api/time", handleTime)
	mux.HandleFunc("GET /time", handleTime)
	mux.HandleFunc("GET /api/stats", handleStats)
	mux.HandleFunc("GET /stats", handleStats)
	// The ArchiveTune client opens its WebSocket against the server URL
	// itself (see ListenTogetherClient.connect: Request.Builder().url(serverUrl)),
	// so the upgrade handler answers on every path.
	mux.HandleFunc("/", handleRoot)
	return mux
}

// sweepLoop is the reaper: disconnect grace expiry, empty rooms, stale join
// requests and the room age ceiling.
func sweepLoop() {
	interval := time.Duration(config.SweepIntervalMs) * time.Millisecond
	if interval <= 0 {
		interval = 5 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for range ticker.C {
		now := clock.NowMs()
		events, drops := store.Sweep(now, party.Thresholds{
			DisconnectGraceMs: config.DisconnectGraceMs,
			EmptyRoomTTLMs:    config.EmptyRoomTTLMs,
			RoomMaxAgeMs:      config.RoomMaxAgeMs,
			PendingJoinTTLMs:  config.PendingJoinTTLMs,
		})
		for _, ev := range events {
			deliverSweepEvent(ev)
		}
		for _, code := range drops {
			theHub.DropRoom(code)
			store.DropRoom(code)
			log.Printf("swept room %s", code)
		}
	}
}

func deliverSweepEvent(ev party.SweepEvent) {
	frame, err := protocol.NewEnvelope(ev.Type, ev.Payload)
	if err != nil {
		return
	}
	if ev.TargetUserID != "" {
		// join_rejected targets are pending joiners, whose sockets sit under
		// the pending registry key.
		key := ev.TargetUserID
		if ev.Type == protocol.TypeJoinRejected {
			key = pendingKey(key)
		}
		theHub.Send(ev.Code, key, frame)
	} else {
		theHub.Broadcast(ev.Code, frame, ev.SkipUserID)
	}
	for _, token := range ev.DroppedTokens {
		store.DropSession(token)
	}
}

// --- REST handlers ---------------------------------------------------------

func handleHealthz(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]any{
		"ok":       true,
		"serverMs": clock.NowMs(),
	})
}

func handleTime(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]any{
		"serverMs": clock.NowMs(),
	})
}

func handleStats(w http.ResponseWriter, r *http.Request) {
	rooms, sockets := theHub.Counts()
	jsonResponse(w, http.StatusOK, map[string]any{
		"rooms":     rooms,
		"sockets":   sockets,
		"maxRooms":  config.MaxRooms,
		"uptimeSec": int64(time.Since(uptime).Seconds()),
		"serverMs":  clock.NowMs(),
	})
}

func jsonResponse(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// --- root: WebSocket upgrade or info page ----------------------------------

func handleRoot(w http.ResponseWriter, r *http.Request) {
	if isUpgradeRequest(r) {
		origin := r.Header.Get("Origin")
		if origin != "" && !config.IsAllowedOrigin(origin) {
			http.Error(w, "origin not allowed", http.StatusForbidden)
			return
		}
		conn, err := ws.Upgrade(w, r, ws.Limits{
			MaxMessageSize:   config.MaxMessageBytes,
			IdleTimeout:      time.Duration(config.IdleTimeoutMs) * time.Millisecond,
			WriteTimeout:     time.Duration(config.WriteTimeoutMs) * time.Millisecond,
			HandshakeTimeout: time.Duration(config.HandshakeTimeoutMs) * time.Millisecond,
		})
		if err != nil {
			log.Printf("websocket upgrade failed: %v", err)
			return
		}
		serveWS(conn, clientIP(r))
		return
	}

	// Friendly landing page for anyone poking the server with a browser.
	body := map[string]any{
		"server":    "ArchiveTune Listen Together server",
		"protocol":  "vivi-music JSON over WebSocket (see README.md)",
		"websocket": "any path (ws://host or ws://host/ws), binary or text frames",
		"rest":      []string{"/healthz", "/api/time", "/api/stats"},
		"serverMs":  clock.NowMs(),
	}
	jsonResponse(w, http.StatusOK, body)
}

func isUpgradeRequest(r *http.Request) bool {
	return strings.EqualFold(r.Header.Get("Upgrade"), "websocket") ||
		(strings.Contains(strings.ToLower(r.Header.Get("Connection")), "upgrade") &&
			strings.Contains(strings.ToLower(r.Header.Get("Upgrade")), "websocket"))
}

// clientIP best-effort extracts the peer address for rate limiting.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// --- per-IP create limiter (ported from BitChord) ---------------------------

type ipRateLimiter struct {
	mu      sync.Mutex
	window  time.Duration
	limit   int
	maxKeys int
	entries map[string][]time.Time
}

func newIPRateLimiter(window time.Duration, limit, maxKeys int) *ipRateLimiter {
	return &ipRateLimiter{
		window:  window,
		limit:   limit,
		maxKeys: maxKeys,
		entries: make(map[string][]time.Time),
	}
}

// Allow reports whether the IP may create a room right now.
func (l *ipRateLimiter) Allow(ip string) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()

	stale := now.Add(-l.window)
	hits := l.entries[ip][:0]
	for _, t := range l.entries[ip] {
		if t.After(stale) {
			hits = append(hits, t)
		}
	}
	if len(hits) >= l.limit {
		l.entries[ip] = hits
		return false
	}
	hits = append(hits, now)
	l.entries[ip] = hits

	// Keep the tracked set bounded: once full, unknown IPs are refused rather
	// than letting the map grow without limit.
	if len(l.entries) > l.maxKeys {
		cutoff := now.Add(-l.window)
		for key, times := range l.entries {
			if len(times) == 0 || !times[len(times)-1].After(cutoff) {
				delete(l.entries, key)
			}
		}
		if len(l.entries) > l.maxKeys {
			return false
		}
	}
	return true
}
