// Package config holds the environment knobs for the Listen Together server.
//
// Every knob is optional; the defaults below suit a small single-binary
// deployment behind a reverse proxy.
//
// Adapted from BitChord's Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
package config

import (
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
)

func getInt(key string, fallback int) int {
	val := os.Getenv(key)
	if val == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(strings.TrimSpace(val))
	if err != nil {
		return fallback
	}
	return parsed
}

func getCSV(key string, fallback string) []string {
	val := os.Getenv(key)
	if val == "" {
		val = fallback
	}
	if val == "" {
		return nil
	}
	var res []string
	for _, item := range strings.Split(val, ",") {
		trimmed := strings.TrimSpace(item)
		if trimmed != "" {
			res = append(res, trimmed)
		}
	}
	return res
}

func getOrigin(key string) string {
	val := strings.TrimRight(strings.TrimSpace(os.Getenv(key)), "/")
	if val == "" {
		return ""
	}
	u, err := url.Parse(val)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.User != nil {
		log.Printf("%s %q is not an http(s) origin; ignoring", key, val)
		return ""
	}
	return val
}

// IsAllowedOrigin deliberately does not support a wildcard: browser clients
// must be explicitly named, while the Android client sends no Origin header at
// all and is always accepted.
func IsAllowedOrigin(origin string) bool {
	for _, allowed := range AllowedOrigins {
		if origin == allowed {
			return true
		}
	}
	return false
}

var (
	// Port the HTTP/WebSocket listener binds to.
	Port = getInt("PORT", 8080)

	// MaxMembers is the number of approved members (host included) a room
	// may hold. Pending joiners wait outside this count.
	MaxMembers = getInt("LT_MAX_MEMBERS", 8)

	// MaxRooms caps the number of simultaneously live rooms.
	MaxRooms = getInt("LT_MAX_ROOMS", 100)

	// DisconnectGraceMs is how long a lost socket keeps its seat. Inside the
	// grace window the member is reported as user_disconnected, not user_left,
	// and a reconnect reclaims the seat.
	DisconnectGraceMs = int64(getInt("LT_DISCONNECT_GRACE_MS", 90000))

	// EmptyRoomTTLMs is how long a room with no members at all survives.
	EmptyRoomTTLMs = int64(getInt("LT_EMPTY_ROOM_TTL_MS", 120000))

	// RoomMaxAgeMs is a hard ceiling on a room's lifetime.
	RoomMaxAgeMs = int64(getInt("LT_ROOM_MAX_AGE_MS", 12*60*60*1000))

	// PendingJoinTTLMs is how long a join request may sit unanswered.
	PendingJoinTTLMs = int64(getInt("LT_PENDING_JOIN_TTL_MS", 120000))

	// SweepIntervalMs is how often the reaper runs (disconnect grace expiry,
	// empty rooms, stale join requests, room max age).
	SweepIntervalMs = int64(getInt("LT_SWEEP_INTERVAL_MS", 5000))

	// MaxMessageBytes is the largest inbound WebSocket message accepted. It
	// must comfortably exceed a custom-avatar chat broadcast (the ArchiveTune
	// client relays up to 96 KiB of JPEG as base64 inside a chat message).
	MaxMessageBytes = int64(getInt("LT_MAX_MESSAGE_BYTES", 262144))

	// IdleTimeoutMs closes a WebSocket that has sent no frame at all for this
	// long. The client's application-level ping (25 s) and OkHttp's WebSocket
	// ping (30 s) keep healthy sockets far inside this.
	IdleTimeoutMs = int64(getInt("LT_IDLE_TIMEOUT_MS", 150000))

	// WriteTimeoutMs is per outbound frame.
	WriteTimeoutMs = int64(getInt("LT_WRITE_TIMEOUT_MS", 10000))

	// HandshakeTimeoutMs guards the 101 response write.
	HandshakeTimeoutMs = int64(getInt("LT_HANDSHAKE_TIMEOUT_MS", 10000))

	// FrameRatePerSecond caps decoded messages per member, ping frames
	// included, so one misbehaving client cannot saturate the room.
	FrameRatePerSecond = float64(getInt("LT_FRAME_RATE_PER_SECOND", 30))

	// CreateRatePerMinute caps new rooms per client IP.
	CreateRatePerMinute = getInt("LT_CREATE_RATE_PER_MINUTE", 5)

	// RateLimitMaxEntries caps tracked IPs before creations are rejected,
	// keeping the limiter itself bounded.
	RateLimitMaxEntries = getInt("LT_RATE_LIMIT_MAX_ENTRIES", 10000)

	// UsernameMax bounds a display name; ChatMax bounds a chat message.
	UsernameMax = getInt("LT_USERNAME_MAX", 64)
	ChatMax     = getInt("LT_CHAT_MAX", 200000)

	// AllowedOrigins is the browser Origin allowlist for the WebSocket
	// endpoint and the ops endpoints. Empty by default: native clients send
	// no Origin and stay supported, browsers are refused until listed.
	AllowedOrigins = getCSV("LT_ALLOWED_ORIGINS", "")
	PublicOrigin   = getOrigin("LT_PUBLIC_ORIGIN")
)
