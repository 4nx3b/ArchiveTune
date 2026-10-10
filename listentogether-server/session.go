// ArchiveTune — Listen Together WebSocket session.
//
// One session wraps one socket. The socket starts in a lobby state; it enters
// a room through create_room, join_room (as a pending joiner) or reconnect,
// and the room itself is the source of truth for whether its user id is a
// member, a pending joiner or the host — the session keeps only (room, userID)
// and asks the room, which removes a whole class of promote/drop races.
//
// Message handling follows the vivi-music protocol exactly as ArchiveTune's
// MessageCodec.kt / ListenTogetherClient.kt define it (JSON envelopes in text
// or binary frames; the client actually sends binary frames containing JSON).
//
// Session lifecycle and dispatch structure adapted from BitChord's Listen
// Together backend (GPL-3.0): https://github.com/kushagrasinghx/BitChord
//
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"log"
	"sync"

	"moe.rukamori.archivetune/listentogether-server/clock"
	"moe.rukamori.archivetune/listentogether-server/codes"
	"moe.rukamori.archivetune/listentogether-server/config"
	"moe.rukamori.archivetune/listentogether-server/party"
	"moe.rukamori.archivetune/listentogether-server/protocol"
	"moe.rukamori.archivetune/listentogether-server/ws"
)

// session owns one WebSocket connection.
type session struct {
	conn   *ws.Conn
	room   *party.Room // nil while in the lobby
	userID string      // member id, or pending-joiner id, or "" in the lobby
	ip     string      // remote address, for the room-create limiter
	bucket *frameBucket
}

// frameBucket is a small token bucket capping decoded messages per second.
type frameBucket struct {
	mu     sync.Mutex
	tokens float64
	lastMs int64
}

func newFrameBucket() *frameBucket {
	return &frameBucket{tokens: config.FrameRatePerSecond * 2, lastMs: clock.NowMs()}
}

func (b *frameBucket) allow(nowMs int64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	rate := config.FrameRatePerSecond
	if rate <= 0 {
		rate = 30
	}
	burst := rate * 2
	elapsed := float64(nowMs-b.lastMs) / 1000
	if elapsed > 0 {
		b.tokens += elapsed * rate
		if b.tokens > burst {
			b.tokens = burst
		}
		b.lastMs = nowMs
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// serveWS runs the read loop for one connection. Returning from it tears the
// session down.
func serveWS(conn *ws.Conn, remoteIP string) {
	s := &session{conn: conn, ip: remoteIP, bucket: newFrameBucket()}
	defer s.cleanup()

	for {
		opcode, data, err := conn.ReadMessage()
		if err != nil {
			if ce, ok := err.(*ws.CloseError); ok {
				log.Printf("ws %s: closed by peer (code %d)", remoteIP, ce.Code)
			}
			return
		}
		if opcode != ws.OpcodeText && opcode != ws.OpcodeBinary {
			continue
		}
		now := clock.NowMs()
		if !s.bucket.allow(now) {
			s.sendError(protocol.ErrCodeRateLimited, "Too many messages; slow down.")
			continue
		}
		s.handleData(data, now)
	}
}

// handleData decodes one envelope and dispatches it.
func (s *session) handleData(data []byte, now int64) {
	var env protocol.Envelope
	if err := json.Unmarshal(data, &env); err != nil || env.Type == "" {
		s.sendError(protocol.ErrCodeInvalidMessage, "Message is not a JSON envelope with a type.")
		return
	}

	switch env.Type {
	case protocol.TypeCreateRoom:
		s.handleCreateRoom(env.Payload, now)
	case protocol.TypeJoinRoom:
		s.handleJoinRoom(env.Payload, now)
	case protocol.TypeLeaveRoom:
		s.handleLeaveRoom(now)
	case protocol.TypeApproveJoin:
		s.handleApproveJoin(env.Payload, now)
	case protocol.TypeRejectJoin:
		s.handleRejectJoin(env.Payload)
	case protocol.TypePlaybackAction:
		s.handlePlaybackAction(env.Payload, now)
	case protocol.TypeBufferReady:
		s.handleBufferReady(env.Payload, now)
	case protocol.TypeKickUser:
		s.handleKickUser(env.Payload, now)
	case protocol.TypeTransferHost:
		s.handleTransferHost(env.Payload, now)
	case protocol.TypePing:
		s.send(protocol.TypePong, protocol.PongPayload{ServerTime: now})
	case protocol.TypeChat:
		s.handleChat(env.Payload, now)
	case protocol.TypeRequestSync:
		s.handleRequestSync(now)
	case protocol.TypeReconnect:
		s.handleReconnect(env.Payload, now)
	case protocol.TypeSuggestTrack:
		s.handleSuggestTrack(env.Payload, now)
	case protocol.TypeApproveSuggestion:
		s.handleSuggestionDecision(env.Payload, true)
	case protocol.TypeRejectSuggestion:
		s.handleSuggestionDecision(env.Payload, false)
	default:
		s.sendError(protocol.ErrCodeInvalidMessage, "Unknown message type "+env.Type)
	}
}

// --- send helpers ----------------------------------------------------------

func (s *session) send(msgType string, payload any) {
	frame, err := protocol.NewEnvelope(msgType, payload)
	if err != nil {
		return
	}
	_ = s.conn.WriteMessage(ws.OpcodeText, frame)
}

func (s *session) sendError(code, message string) {
	s.send(protocol.TypeError, protocol.ErrorPayload{Code: code, Message: message})
}

func (s *session) broadcast(msgType string, payload any, skipUserID string) {
	frame, err := protocol.NewEnvelope(msgType, payload)
	if err != nil {
		return
	}
	if s.room != nil {
		// Approved members only — a pending joiner must not overhear the
		// room (chat, playback, membership) before the host approves.
		theHub.BroadcastMembers(s.room.Code, s.room.MemberIDs(), frame, skipUserID)
	}
}

// --- membership helpers ----------------------------------------------------

// isMember reports whether this session's user is an approved member of its
// room (pending joiners and lobby connections are not).
func (s *session) isMember() bool {
	return s.room != nil && s.room.Member(s.userID) != nil
}

// requireMember answers with an error frame and returns nil when the session
// is not an approved room member.
func (s *session) requireMember() *party.Room {
	if s.room == nil || s.room.Member(s.userID) == nil {
		s.sendError("not_in_room", "You are not a member of a room.")
		return nil
	}
	return s.room
}

// requireHost is requireMember plus host authority — playback and membership
// control are host-only, enforced server-side, not just hidden in the app.
func (s *session) requireHost() *party.Room {
	room := s.requireMember()
	if room == nil {
		return nil
	}
	if !room.IsHost(s.userID) {
		s.sendError(protocol.ErrCodeNotHost, "Only the host can do that.")
		return nil
	}
	return room
}

// pendingKey is the hub registry key for a not-yet-approved joiner's socket.
// Isolating pending sockets under their own prefix keeps room broadcasts
// (chat, sync_playback, …) from reaching people the host has not let in yet —
// the approval gate is real, not just UI.
func pendingKey(userID string) string {
	return "p:" + userID
}

// leaveCurrentRoom removes the session's user from its room (member or
// pending) without closing the socket — the client keeps the connection open
// after leaving and may create or join another room on the same socket.
func (s *session) leaveCurrentRoom(now int64) {
	if s.room == nil {
		return
	}
	room := s.room
	userID := s.userID
	s.room = nil
	s.userID = ""

	if member := room.Member(userID); member != nil {
		left, newHost := room.RemoveMember(userID)
		if left != nil {
			store.DropSession(left.Token)
			frame, err := protocol.NewEnvelope(protocol.TypeUserLeft, protocol.UserLeftPayload{
				UserID:   left.UserID,
				Username: left.Username,
			})
			if err == nil {
				theHub.Broadcast(room.Code, frame, userID)
			}
		}
		if newHost != nil {
			s.announceNewHost(room, newHost, userID)
		}
		theHub.DetachUser(room.Code, userID)
	} else {
		room.TakePending(userID)
		theHub.DetachUser(room.Code, pendingKey(userID))
	}
}

// announceNewHost broadcasts host_changed and hands outstanding join requests
// to the new host so they are not lost with the old one. skipUserID spares a
// member on its way out (the leaver/kicked member must not hear it).
func (s *session) announceNewHost(room *party.Room, newHost *party.Member, skipUserID string) {
	frame, err := protocol.NewEnvelope(protocol.TypeHostChanged, protocol.HostChangedPayload{
		NewHostID:   newHost.UserID,
		NewHostName: newHost.Username,
	})
	if err != nil {
		return
	}
	theHub.Broadcast(room.Code, frame, skipUserID)
	for _, pj := range room.PendingList() {
		theHub.Send(room.Code, newHost.UserID, protocol.MustNewEnvelope(protocol.TypeJoinRequest, protocol.JoinRequestPayload{
			UserID:      pj.UserID,
			Username:    pj.Username,
			AvatarIndex: pj.AvatarIndex,
		}))
	}
}

// --- message handlers ------------------------------------------------------

func (s *session) handleCreateRoom(payload json.RawMessage, now int64) {
	var p protocol.CreateRoomPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.Username == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "create_room needs a username.")
		return
	}
	if !createLimiter.Allow(s.ip) {
		s.sendError(protocol.ErrCodeRateLimited, "Too many rooms created from this address; wait a minute.")
		return
	}
	if store.Count() >= config.MaxRooms {
		s.sendError(protocol.ErrCodeRoomFull, "This server is at its room limit; try again later.")
		return
	}

	s.leaveCurrentRoom(now)

	room, host := store.CreateRoom(party.SanitiseUsername(p.Username, config.UsernameMax), p.AvatarIndex, now)
	if room == nil {
		s.sendError("room_create_failed", "Could not allocate a room code; try again.")
		return
	}

	s.room = room
	s.userID = host.UserID
	theHub.Attach(room.Code, host.UserID, s.conn)

	s.send(protocol.TypeRoomCreated, protocol.RoomCreatedPayload{
		RoomCode:     room.Code,
		UserID:       host.UserID,
		SessionToken: host.Token,
	})
	log.Printf("room %s created by %s (%s)", room.Code, host.Username, host.UserID)
}

func (s *session) handleJoinRoom(payload json.RawMessage, now int64) {
	var p protocol.JoinRoomPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.Username == "" || p.RoomCode == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "join_room needs a room_code and a username.")
		return
	}

	code := codes.Normalise(p.RoomCode)
	room := store.Room(code)
	if room == nil {
		s.send(protocol.TypeJoinRejected, protocol.JoinRejectedPayload{Reason: "Room not found"})
		return
	}
	if room.ApprovedCount() >= config.MaxMembers {
		s.send(protocol.TypeJoinRejected, protocol.JoinRejectedPayload{Reason: "Room is full"})
		return
	}
	host := room.Host()
	if host == nil || !theHub.IsAttached(room.Code, host.UserID) {
		s.send(protocol.TypeJoinRejected, protocol.JoinRejectedPayload{Reason: "Host is not available"})
		return
	}

	s.leaveCurrentRoom(now)

	pj := room.AddPendingJoiner(party.SanitiseUsername(p.Username, config.UsernameMax), p.AvatarIndex, now)
	s.room = room
	s.userID = pj.UserID
	// Registered under the pending prefix: the joiner waits on this socket,
	// but no room broadcast reaches it until the host approves.
	theHub.Attach(room.Code, pendingKey(pj.UserID), s.conn)

	// The host decides; the joiner waits on this same socket.
	theHub.Send(room.Code, host.UserID, protocol.MustNewEnvelope(protocol.TypeJoinRequest, protocol.JoinRequestPayload{
		UserID:      pj.UserID,
		Username:    pj.Username,
		AvatarIndex: pj.AvatarIndex,
	}))
	log.Printf("room %s: %s (%s) requested to join", room.Code, pj.Username, pj.UserID)
}

func (s *session) handleApproveJoin(payload json.RawMessage, now int64) {
	room := s.requireHost()
	if room == nil {
		return
	}
	var p protocol.ApproveJoinPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.UserID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "approve_join needs a user_id.")
		return
	}

	member, err := room.ApprovePending(p.UserID)
	if err != nil {
		s.sendError("user_not_found", "No pending join request for that user_id.")
		return
	}
	store.RegisterSession(member.Token, room.Code, member.UserID)

	// Promote the joiner's socket from the pending key to its member key so
	// it starts receiving room broadcasts.
	theHub.Rekey(room.Code, pendingKey(member.UserID), member.UserID)

	// The joiner's socket is registered under the same user id the pending
	// entry used, so the approval finds it.
	theHub.Send(room.Code, member.UserID, protocol.MustNewEnvelope(protocol.TypeJoinApproved, protocol.JoinApprovedPayload{
		RoomCode:     room.Code,
		UserID:       member.UserID,
		SessionToken: member.Token,
		State:        room.Snapshot(now),
	}))

	// Everyone but the new member hears user_joined — the new member's own
	// view arrived in join_approved, and the client would otherwise append
	// itself twice.
	s.broadcast(protocol.TypeUserJoined, protocol.UserJoinedPayload{
		UserID:      member.UserID,
		Username:    member.Username,
		AvatarIndex: member.AvatarIndex,
	}, member.UserID)
	log.Printf("room %s: %s (%s) joined", room.Code, member.Username, member.UserID)
}

func (s *session) handleRejectJoin(payload json.RawMessage) {
	room := s.requireHost()
	if room == nil {
		return
	}
	var p protocol.RejectJoinPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.UserID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "reject_join needs a user_id.")
		return
	}

	pj := room.TakePending(p.UserID)
	if pj == nil {
		s.sendError("user_not_found", "No pending join request for that user_id.")
		return
	}
	reason := "Rejected by host"
	if p.Reason != nil && *p.Reason != "" {
		reason = *p.Reason
	}
	theHub.Send(room.Code, pendingKey(pj.UserID), protocol.MustNewEnvelope(protocol.TypeJoinRejected, protocol.JoinRejectedPayload{Reason: reason}))
	theHub.DetachUser(room.Code, pendingKey(pj.UserID))
}

func (s *session) handleLeaveRoom(now int64) {
	if s.room == nil {
		return
	}
	s.leaveCurrentRoom(now)
}

func (s *session) handlePlaybackAction(payload json.RawMessage, now int64) {
	room := s.requireHost()
	if room == nil {
		return
	}
	var p protocol.PlaybackActionPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.Action == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "playback_action needs an action.")
		return
	}

	room.ApplyPlayback(p, now)

	// Echo to everyone including the host: the host's client ignores its own
	// play/pause echoes but applies queue operations from the echo, so the
	// shared queue stays consistent. server_time is stamped by the server so
	// guests can compensate transit delay (ListenTogetherManager adds
	// now - server_time to the position on play).
	p.ServerTime = now
	s.broadcast(protocol.TypeSyncPlayback, p, "")
}

func (s *session) handleBufferReady(payload json.RawMessage, now int64) {
	room := s.requireMember()
	if room == nil {
		return
	}
	var p protocol.BufferReadyPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.TrackID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "buffer_ready needs a track_id.")
		return
	}

	result := room.MarkBufferReady(s.userID, p.TrackID)
	if result.Complete {
		s.broadcast(protocol.TypeBufferComplete, protocol.BufferCompletePayload{TrackID: result.TrackID}, "")
	} else {
		s.broadcast(protocol.TypeBufferWait, protocol.BufferWaitPayload{
			TrackID:    result.TrackID,
			WaitingFor: result.WaitingFor,
		}, "")
	}
}

func (s *session) handleKickUser(payload json.RawMessage, now int64) {
	room := s.requireHost()
	if room == nil {
		return
	}
	var p protocol.KickUserPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.UserID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "kick_user needs a user_id.")
		return
	}
	if p.UserID == s.userID {
		s.sendError(protocol.ErrCodeInvalidPayload, "The host cannot be kicked.")
		return
	}
	target := room.Member(p.UserID)
	if target == nil {
		s.sendError("user_not_found", "That user is not a member of this room.")
		return
	}

	reason := ""
	if p.Reason != nil {
		reason = *p.Reason
	}
	theHub.Send(room.Code, target.UserID, protocol.MustNewEnvelope(protocol.TypeKicked, protocol.KickedPayload{Reason: reason}))

	left, newHost := room.RemoveMember(target.UserID)
	if left != nil {
		store.DropSession(left.Token)
		frame, err := protocol.NewEnvelope(protocol.TypeUserLeft, protocol.UserLeftPayload{
			UserID:   left.UserID,
			Username: left.Username,
		})
		if err == nil {
			// Skip the kicked member: they already got their own `kicked`
			// frame and their client clears its state on it.
			theHub.Broadcast(room.Code, frame, target.UserID)
		}
	}
	if newHost != nil {
		s.announceNewHost(room, newHost, target.UserID)
	}
	// Close the kicked socket after the frame is on the wire.
	theHub.CloseMember(room.Code, target.UserID)
}

func (s *session) handleTransferHost(payload json.RawMessage, now int64) {
	room := s.requireHost()
	if room == nil {
		return
	}
	var p protocol.TransferHostPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.NewHostID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "transfer_host needs a new_host_id.")
		return
	}
	newHost := room.SetHost(p.NewHostID)
	if newHost == nil {
		s.sendError("user_not_found", "That user is not a member of this room.")
		return
	}
	// Everyone, the old host included: the old host's client demotes itself
	// on this frame.
	s.announceNewHost(room, newHost, "")
	log.Printf("room %s: host transferred to %s", room.Code, newHost.Username)
}

func (s *session) handleChat(payload json.RawMessage, now int64) {
	room := s.requireMember()
	if room == nil {
		return
	}
	var p protocol.ChatPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.Message == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "chat needs a message.")
		return
	}
	if len(p.Message) > config.ChatMax {
		s.sendError(protocol.ErrCodeInvalidPayload, "Chat message is too long.")
		return
	}
	member := room.Member(s.userID)

	// Echo to everyone including the sender — the client de-duplicates its
	// own echo (isSelfEchoAlreadyInHistory). The message body is relayed
	// verbatim: reply markers, shared-track/GIF/custom-avatar envelopes and
	// chat control envelopes are all client-side conventions.
	s.broadcast(protocol.TypeChat, protocol.ChatMessagePayload{
		UserID:    member.UserID,
		Username:  member.Username,
		Message:   p.Message,
		Timestamp: now,
		ReplyTo:   p.ReplyTo,
	}, "")
}

func (s *session) handleRequestSync(now int64) {
	room := s.requireMember()
	if room == nil {
		return
	}
	s.send(protocol.TypeSyncState, room.SyncState(now))
}

func (s *session) handleReconnect(payload json.RawMessage, now int64) {
	var p protocol.ReconnectPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.SessionToken == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "reconnect needs a session_token.")
		return
	}

	sess, ok := store.LookupSession(p.SessionToken)
	if !ok {
		s.sendError(protocol.ErrCodeSessionNotFound, "Session not found; rejoin the room.")
		return
	}
	room := store.Room(sess.Code)
	if room == nil || room.Member(sess.UserID) == nil {
		store.DropSession(p.SessionToken)
		s.sendError(protocol.ErrCodeSessionNotFound, "Session not found; rejoin the room.")
		return
	}

	s.leaveCurrentRoom(now)

	member, wasDisconnected := room.MarkConnected(sess.UserID)
	if member == nil {
		s.sendError(protocol.ErrCodeSessionNotFound, "Session not found; rejoin the room.")
		return
	}
	s.room = room
	s.userID = member.UserID
	// Attach closes any zombie socket still holding the seat.
	theHub.Attach(room.Code, member.UserID, s.conn)

	isHost := room.IsHost(member.UserID)
	s.send(protocol.TypeReconnected, protocol.ReconnectedPayload{
		RoomCode: room.Code,
		UserID:   member.UserID,
		State:    room.Snapshot(now),
		IsHost:   isHost,
	})
	if wasDisconnected {
		frame, err := protocol.NewEnvelope(protocol.TypeUserReconnected, protocol.UserReconnectedPayload{
			UserID:   member.UserID,
			Username: member.Username,
		})
		if err == nil {
			theHub.Broadcast(room.Code, frame, member.UserID)
		}
	}
	log.Printf("room %s: %s reconnected (host=%v)", room.Code, member.Username, isHost)
}

func (s *session) handleSuggestTrack(payload json.RawMessage, now int64) {
	room := s.requireMember()
	if room == nil {
		return
	}
	var p protocol.SuggestTrackPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.TrackInfo.ID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "suggest_track needs a track_info with an id.")
		return
	}
	host := room.Host()
	if host == nil {
		return
	}
	member := room.Member(s.userID)
	sug := room.AddSuggestion(member, p.TrackInfo, now)
	theHub.Send(room.Code, host.UserID, protocol.MustNewEnvelope(protocol.TypeSuggestionReceived, protocol.SuggestionReceivedPayload{
		SuggestionID: sug.ID,
		FromUserID:   sug.FromUserID,
		FromUsername: sug.FromUsername,
		TrackInfo:    sug.Track,
	}))
}

func (s *session) handleSuggestionDecision(payload json.RawMessage, approve bool) {
	room := s.requireHost()
	if room == nil {
		return
	}
	if approve {
		var p protocol.ApproveSuggestionPayload
		if err := json.Unmarshal(payload, &p); err != nil || p.SuggestionID == "" {
			s.sendError(protocol.ErrCodeInvalidPayload, "approve_suggestion needs a suggestion_id.")
			return
		}
		sug := room.TakeSuggestion(p.SuggestionID)
		if sug == nil {
			s.sendError("suggestion_not_found", "No such suggestion.")
			return
		}
		track := sug.Track
		theHub.Send(room.Code, sug.FromUserID, protocol.MustNewEnvelope(protocol.TypeSuggestionApproved, protocol.SuggestionApprovedPayload{
			SuggestionID: sug.ID,
			TrackInfo:    &track,
		}))
		return
	}

	var p protocol.RejectSuggestionPayload
	if err := json.Unmarshal(payload, &p); err != nil || p.SuggestionID == "" {
		s.sendError(protocol.ErrCodeInvalidPayload, "reject_suggestion needs a suggestion_id.")
		return
	}
	sug := room.TakeSuggestion(p.SuggestionID)
	if sug == nil {
		s.sendError("suggestion_not_found", "No such suggestion.")
		return
	}
	theHub.Send(room.Code, sug.FromUserID, protocol.MustNewEnvelope(protocol.TypeSuggestionRejected, protocol.SuggestionRejectedPayload{
		SuggestionID: sug.ID,
		Reason:       p.Reason,
	}))
}

// cleanup runs when the read loop exits: the socket is gone. A member's seat
// survives the disconnect grace period (the reaper decides later); a pending
// joiner's request dies with its socket.
func (s *session) cleanup() {
	if s.room == nil {
		return
	}
	room := s.room
	s.room = nil
	userID := s.userID
	s.userID = ""

	if member := room.Member(userID); member != nil {
		// Only orphan the seat if this socket still owned it — a reconnect
		// may already have replaced us, in which case the seat is alive.
		if theHub.Detach(room.Code, userID, s.conn) {
			if m := room.MarkDisconnectedAt(userID, clock.NowMs()); m != nil {
				frame, err := protocol.NewEnvelope(protocol.TypeUserDisconnected, protocol.UserDisconnectedPayload{
					UserID:   m.UserID,
					Username: m.Username,
				})
				if err == nil {
					theHub.Broadcast(room.Code, frame, userID)
				}
			}
		}
		return
	}

	room.TakePending(userID)
	theHub.Detach(room.Code, pendingKey(userID), s.conn)
}
