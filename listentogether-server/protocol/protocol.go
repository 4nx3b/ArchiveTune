// Package protocol defines the ArchiveTune (vivi-music) Listen Together wire
// protocol: the envelope every WebSocket message travels in and the payload
// shapes for each message type.
//
// The JSON shapes are taken verbatim from ArchiveTune's
// listentogether/Protocol.kt and MessageCodec.kt (GPL-3.0), which themselves
// port the vivi-music protocol. Field names are snake_case to match the
// Kotlin @SerialName annotations, and omitempty placement mirrors the Kotlin
// defaults: a field that Kotlin declares with a default may be omitted, while
// a field without one must always be present (even when null).
//
// Server layout adapted from BitChord's Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
package protocol

import "encoding/json"

// Message types (client -> server). Mirrors MessageTypes in Protocol.kt.
const (
	TypeCreateRoom        = "create_room"
	TypeJoinRoom          = "join_room"
	TypeLeaveRoom         = "leave_room"
	TypeApproveJoin       = "approve_join"
	TypeRejectJoin        = "reject_join"
	TypePlaybackAction    = "playback_action"
	TypeBufferReady       = "buffer_ready"
	TypeKickUser          = "kick_user"
	TypeTransferHost      = "transfer_host"
	TypePing              = "ping"
	TypeChat              = "chat"
	TypeRequestSync       = "request_sync"
	TypeReconnect         = "reconnect"
	TypeSuggestTrack      = "suggest_track"
	TypeApproveSuggestion = "approve_suggestion"
	TypeRejectSuggestion  = "reject_suggestion"
)

// Message types (server -> client). Mirrors MessageTypes in Protocol.kt.
const (
	TypeRoomCreated        = "room_created"
	TypeJoinRequest        = "join_request"
	TypeJoinApproved       = "join_approved"
	TypeJoinRejected       = "join_rejected"
	TypeUserJoined         = "user_joined"
	TypeUserLeft           = "user_left"
	TypeSyncPlayback       = "sync_playback"
	TypeBufferWait         = "buffer_wait"
	TypeBufferComplete     = "buffer_complete"
	TypeError              = "error"
	TypePong               = "pong"
	TypeHostChanged        = "host_changed"
	TypeKicked             = "kicked"
	TypeSyncState          = "sync_state"
	TypeReconnected        = "reconnected"
	TypeUserReconnected    = "user_reconnected"
	TypeUserDisconnected   = "user_disconnected"
	TypeSuggestionReceived = "suggestion_received"
	TypeSuggestionApproved = "suggestion_approved"
	TypeSuggestionRejected = "suggestion_rejected"
)

// Playback actions. Mirrors PlaybackActions in Protocol.kt.
const (
	ActionPlay        = "play"
	ActionPause       = "pause"
	ActionSeek        = "seek"
	ActionSkipNext    = "skip_next"
	ActionSkipPrev    = "skip_prev"
	ActionChangeTrack = "change_track"
	ActionQueueAdd    = "queue_add"
	ActionQueueRemove = "queue_remove"
	ActionQueueClear  = "queue_clear"
	ActionSyncQueue   = "sync_queue"
	ActionSetVolume   = "set_volume"
)

// Error codes the ArchiveTune client reacts to specially (see its
// ListenTogetherClient.handleError): invalid_message retriggers the pending
// room action after a codec upgrade; the session codes trigger a transparent
// rejoin; the room codes clear the persisted session.
const (
	ErrCodeInvalidMessage  = "invalid_message"
	ErrCodeSessionNotFound = "session_not_found"
	ErrCodeSessionExpired  = "session_expired"
	ErrCodeInvalidSession  = "invalid_session"
	ErrCodeRoomNotFound    = "room_not_found"
	ErrCodeRoomClosed      = "room_closed"
	ErrCodeNotHost         = "not_host"
	ErrCodeRateLimited     = "rate_limited"
	ErrCodeRoomFull        = "room_full"
	ErrCodeInvalidPayload  = "invalid_payload"
)

// Envelope is the outer frame of every message:
//
//	{"type": "...", "payload": {...}}     payload omitted when nil
type Envelope struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// TrackInfo mirrors TrackInfo in Protocol.kt. album, thumbnail and
// suggested_by are nullable-with-default and may be omitted.
type TrackInfo struct {
	ID          string  `json:"id"`
	Title       string  `json:"title"`
	Artist      string  `json:"artist"`
	Album       *string `json:"album,omitempty"`
	Duration    int64   `json:"duration"`
	Thumbnail   *string `json:"thumbnail,omitempty"`
	SuggestedBy *string `json:"suggested_by,omitempty"`
}

// UserInfo mirrors UserInfo in Protocol.kt.
type UserInfo struct {
	UserID      string `json:"user_id"`
	Username    string `json:"username"`
	IsHost      bool   `json:"is_host"`
	IsConnected bool   `json:"is_connected"`
	AvatarIndex int    `json:"avatar_index"`
}

// RoomState mirrors RoomState in Protocol.kt. current_track and queue are
// nullable-with-default; volume defaults to 1 on the client.
type RoomState struct {
	RoomCode     string      `json:"room_code"`
	HostID       string      `json:"host_id"`
	Users        []UserInfo  `json:"users"`
	CurrentTrack *TrackInfo  `json:"current_track,omitempty"`
	IsPlaying    bool        `json:"is_playing"`
	Position     int64       `json:"position"`
	LastUpdate   int64       `json:"last_update"`
	Volume       float32     `json:"volume"`
	Queue        []TrackInfo `json:"queue,omitempty"`
}

// CreateRoomPayload is sent by the connection creating a room.
type CreateRoomPayload struct {
	Username    string `json:"username"`
	AvatarIndex int    `json:"avatar_index,omitempty"`
}

// JoinRoomPayload is sent by a connection asking to join a room.
type JoinRoomPayload struct {
	RoomCode    string `json:"room_code"`
	Username    string `json:"username"`
	AvatarIndex int    `json:"avatar_index,omitempty"`
}

// ApproveJoinPayload / RejectJoinPayload are host decisions about a pending
// joiner, addressed by the user_id the server generated in join_request.
type ApproveJoinPayload struct {
	UserID string `json:"user_id"`
}

type RejectJoinPayload struct {
	UserID string  `json:"user_id"`
	Reason *string `json:"reason,omitempty"`
}

// PlaybackActionPayload is the host's control frame and, echoed as
// sync_playback, the frame every member synchronises off. The server stamps
// server_time (its own epoch-millisecond clock) before broadcasting so guests
// can compensate transit delay — see ListenTogetherManager.handlePlaybackSync.
type PlaybackActionPayload struct {
	Action     string      `json:"action"`
	TrackID    *string     `json:"track_id,omitempty"`
	Position   *int64      `json:"position,omitempty"`
	TrackInfo  *TrackInfo  `json:"track_info,omitempty"`
	InsertNext *bool       `json:"insert_next,omitempty"`
	Queue      []TrackInfo `json:"queue,omitempty"`
	QueueTitle *string     `json:"queue_title,omitempty"`
	Volume     *float32    `json:"volume,omitempty"`
	ServerTime int64       `json:"server_time,omitempty"`
}

// BufferReadyPayload is sent by a guest once it has buffered the current
// track; the server answers with buffer_wait / buffer_complete.
type BufferReadyPayload struct {
	TrackID string `json:"track_id"`
}

// KickUserPayload and TransferHostPayload are host-only membership controls.
type KickUserPayload struct {
	UserID string  `json:"user_id"`
	Reason *string `json:"reason,omitempty"`
}

type TransferHostPayload struct {
	NewHostID string `json:"new_host_id"`
}

// RepliedMessage mirrors RepliedMessage in Protocol.kt.
type RepliedMessage struct {
	Username string `json:"username"`
	Message  string `json:"message"`
}

// ChatPayload is what a member sends; ChatMessagePayload is what everyone
// (the sender included — the client dedupes its own echo) receives back.
type ChatPayload struct {
	Message string          `json:"message"`
	ReplyTo *RepliedMessage `json:"reply_to,omitempty"`
}

// ChatMessagePayload mirrors ChatMessagePayload in Protocol.kt. The server
// fills user_id, username, message and timestamp; the optional decorations
// (reactions, pins, gifs, shared tracks, ...) ride inside the message text as
// client-side envelopes and are relayed verbatim.
type ChatMessagePayload struct {
	UserID    string          `json:"user_id"`
	Username  string          `json:"username"`
	Message   string          `json:"message"`
	Timestamp int64           `json:"timestamp"`
	ReplyTo   *RepliedMessage `json:"reply_to,omitempty"`
}

// SuggestTrackPayload carries a guest's track suggestion to the host.
type SuggestTrackPayload struct {
	TrackInfo TrackInfo `json:"track_info"`
}

type ApproveSuggestionPayload struct {
	SuggestionID string `json:"suggestion_id"`
}

type RejectSuggestionPayload struct {
	SuggestionID string  `json:"suggestion_id"`
	Reason       *string `json:"reason,omitempty"`
}

// ReconnectPayload carries the session token handed out on room creation or
// join approval.
type ReconnectPayload struct {
	SessionToken string `json:"session_token"`
}

// RoomCreatedPayload answers create_room.
type RoomCreatedPayload struct {
	RoomCode     string `json:"room_code"`
	UserID       string `json:"user_id"`
	SessionToken string `json:"session_token"`
}

// JoinRequestPayload is what the host sees for a pending joiner. user_id is
// generated by the server; approve_join/reject_join address it.
type JoinRequestPayload struct {
	UserID      string `json:"user_id"`
	Username    string `json:"username"`
	AvatarIndex int    `json:"avatar_index,omitempty"`
}

// JoinApprovedPayload answers a successful approval with the full room
// snapshot.
type JoinApprovedPayload struct {
	RoomCode     string    `json:"room_code"`
	UserID       string    `json:"user_id"`
	SessionToken string    `json:"session_token"`
	State        RoomState `json:"state"`
}

// JoinRejectedPayload answers a refused (or impossible) join.
type JoinRejectedPayload struct {
	Reason string `json:"reason"`
}

// UserJoinedPayload is broadcast to everyone except the new member (whose own
// view came from join_approved / reconnected).
type UserJoinedPayload struct {
	UserID      string `json:"user_id"`
	Username    string `json:"username"`
	AvatarIndex int    `json:"avatar_index,omitempty"`
}

type UserLeftPayload struct {
	UserID   string `json:"user_id"`
	Username string `json:"username"`
}

// BufferWaitPayload names the members still buffering; waiting_for carries
// display usernames.
type BufferWaitPayload struct {
	TrackID    string   `json:"track_id"`
	WaitingFor []string `json:"waiting_for"`
}

type BufferCompletePayload struct {
	TrackID string `json:"track_id"`
}

// ErrorPayload mirrors ErrorPayload in Protocol.kt — code is a machine
// string, message a human explanation.
type ErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type HostChangedPayload struct {
	NewHostID   string `json:"new_host_id"`
	NewHostName string `json:"new_host_name"`
}

// KickedPayload is delivered to the removed member before its socket closes.
type KickedPayload struct {
	Reason string `json:"reason"`
}

// SyncStatePayload answers request_sync (and describes the room at join
// time). current_track has no Kotlin default, so the key must always be
// present — null when no track is loaded.
type SyncStatePayload struct {
	CurrentTrack *TrackInfo  `json:"current_track"`
	IsPlaying    bool        `json:"is_playing"`
	Position     int64       `json:"position"`
	LastUpdate   int64       `json:"last_update"`
	Queue        []TrackInfo `json:"queue,omitempty"`
	Volume       *float32    `json:"volume,omitempty"`
}

// ReconnectedPayload answers a successful reconnect.
type ReconnectedPayload struct {
	RoomCode string    `json:"room_code"`
	UserID   string    `json:"user_id"`
	State    RoomState `json:"state"`
	IsHost   bool      `json:"is_host"`
}

type UserReconnectedPayload struct {
	UserID   string `json:"user_id"`
	Username string `json:"username"`
}

type UserDisconnectedPayload struct {
	UserID   string `json:"user_id"`
	Username string `json:"username"`
}

type SuggestionReceivedPayload struct {
	SuggestionID string    `json:"suggestion_id"`
	FromUserID   string    `json:"from_user_id"`
	FromUsername string    `json:"from_username"`
	TrackInfo    TrackInfo `json:"track_info"`
}

type SuggestionApprovedPayload struct {
	SuggestionID string     `json:"suggestion_id"`
	TrackInfo    *TrackInfo `json:"track_info,omitempty"`
}

type SuggestionRejectedPayload struct {
	SuggestionID string  `json:"suggestion_id"`
	Reason       *string `json:"reason,omitempty"`
}

// PongPayload rides on pong frames; the ArchiveTune client ignores it, but a
// server timestamp costs nothing and keeps other vivi-music clients happy.
type PongPayload struct {
	ServerTime int64 `json:"server_time"`
}

// NewEnvelope marshals a message with its payload. A nil payload produces
// {"type":"..."} with no payload key, which the client decodes as null.
func NewEnvelope(msgType string, payload any) ([]byte, error) {
	var raw json.RawMessage
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}
		raw = b
	}
	return json.Marshal(Envelope{Type: msgType, Payload: raw})
}

// MustNewEnvelope is NewEnvelope for statically-known payloads.
func MustNewEnvelope(msgType string, payload any) []byte {
	b, err := NewEnvelope(msgType, payload)
	if err != nil {
		panic(err)
	}
	return b
}
