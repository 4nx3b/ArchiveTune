// Package party holds the in-memory room state for the Listen Together
// server: membership, pending joiners, host authority, the shared playback
// state with its server-clock anchor, the shared queue, per-track buffering
// and the session tokens that let a dropped socket reclaim its seat.
//
// The party package is deliberately transport-free: methods mutate state and
// return the frames the caller must deliver, so main.go stays the single place
// that talks to the hub. Room state is the truth; clients report, the server
// decides — same rule BitChord runs on.
//
// Adapted from BitChord's Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
package party

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strings"
	"sync"

	"moe.rukamori.archivetune/listentogether-server/codes"
	"moe.rukamori.archivetune/listentogether-server/protocol"
)

// Member is an approved room participant. The host is the member whose UserID
// equals Room.HostID.
type Member struct {
	UserID           string
	Username         string
	AvatarIndex      int
	Token            string
	Connected        bool
	DisconnectedAtMs int64
}

// PendingJoiner is someone waiting on the host's approve/reject decision.
type PendingJoiner struct {
	UserID        string
	Username      string
	AvatarIndex   int
	Token         string
	RequestedAtMs int64
}

// Suggestion is a guest's track proposal awaiting the host.
type Suggestion struct {
	ID           string
	FromUserID   string
	FromUsername string
	Track        protocol.TrackInfo
	CreatedAtMs  int64
}

// Session resolves a reconnect token to its seat.
type Session struct {
	Code   string
	UserID string
}

type playback struct {
	current     *protocol.TrackInfo
	isPlaying   bool
	positionMs  int64
	updatedAtMs int64 // server clock instant at which positionMs was true
	volume      float32
	queue       []protocol.TrackInfo
	queueTitle  *string
	bufferTrack string          // track currently in a buffering round
	bufferReady map[string]bool // userID -> has reported buffer_ready
}

// Room is one listen-together room. All methods are safe for concurrent use.
type Room struct {
	Code string
	mu   sync.Mutex

	HostID      string
	members     map[string]*Member
	order       []string // join order, used to elect a new host
	pending     map[string]*PendingJoiner
	suggestions map[string]*Suggestion
	play        playback

	CreatedAtMs  int64
	emptySinceMs int64 // 0 while at least one member is connected
}

// Store owns every live room and the token -> session index.
type Store struct {
	mu       sync.Mutex
	rooms    map[string]*Room
	sessions map[string]Session
}

func NewStore() *Store {
	return &Store{
		rooms:    make(map[string]*Room),
		sessions: make(map[string]Session),
	}
}

func newID(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return prefix + "_" + hex.EncodeToString(b)
}

func newToken() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// CreateRoom mints a room with a fresh code and installs the creator as host.
func (s *Store) CreateRoom(username string, avatarIndex int, nowMs int64) (*Room, *Member) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var code string
	for i := 0; i < 32; i++ {
		candidate := codes.NewCode()
		if _, taken := s.rooms[candidate]; !taken {
			code = candidate
			break
		}
	}
	if code == "" {
		return nil, nil
	}

	host := &Member{
		UserID:      newID("u"),
		Username:    username,
		AvatarIndex: avatarIndex,
		Token:       newToken(),
		Connected:   true,
	}
	room := &Room{
		Code:        code,
		HostID:      host.UserID,
		members:     map[string]*Member{host.UserID: host},
		order:       []string{host.UserID},
		pending:     make(map[string]*PendingJoiner),
		suggestions: make(map[string]*Suggestion),
		play:        playback{volume: 1, updatedAtMs: nowMs, bufferReady: make(map[string]bool)},
		CreatedAtMs: nowMs,
	}
	room.play.current = nil
	s.rooms[code] = room
	s.sessions[host.Token] = Session{Code: code, UserID: host.UserID}
	return room, host
}

// Room returns the live room for a (already normalised) code.
func (s *Store) Room(code string) *Room {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.rooms[code]
}

// LookupSession resolves a reconnect token.
func (s *Store) LookupSession(token string) (Session, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[token]
	return sess, ok
}

// RegisterSession indexes a token after an approval.
func (s *Store) RegisterSession(token, code, userID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[token] = Session{Code: code, UserID: userID}
}

// DropSession invalidates one token (leave, kick, grace expiry).
func (s *Store) DropSession(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.sessions, token)
}

// DropSessionsFor removes every token pointing at a room.
func (s *Store) DropSessionsFor(code string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for token, sess := range s.sessions {
		if sess.Code == code {
			delete(s.sessions, token)
		}
	}
}

// DropRoom forgets the room and its sessions. The caller closes sockets.
func (s *Store) DropRoom(code string) bool {
	s.mu.Lock()
	_, ok := s.rooms[code]
	delete(s.rooms, code)
	s.mu.Unlock()
	if ok {
		s.DropSessionsFor(code)
	}
	return ok
}

// Count returns the number of live rooms.
func (s *Store) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.rooms)
}

// SweepEvent is a frame the reaper wants delivered. TargetUserID == "" means
// broadcast to the room (honouring SkipUserID). DroppedTokens are session
// tokens to invalidate once the frames are out.
type SweepEvent struct {
	Code          string
	Type          string
	Payload       any
	TargetUserID  string
	SkipUserID    string
	DroppedTokens []string
}

// Thresholds parameterise Sweep so tests can shrink the grace periods.
type Thresholds struct {
	DisconnectGraceMs int64
	EmptyRoomTTLMs    int64
	RoomMaxAgeMs      int64
	PendingJoinTTLMs  int64
}

// Sweep is the reaper: it expires unanswered join requests, seats whose
// disconnect grace has elapsed, rooms nobody returned to, and rooms past the
// hard age ceiling. It returns the frames to deliver and the rooms to close.
func (s *Store) Sweep(nowMs int64, th Thresholds) ([]SweepEvent, []string) {
	s.mu.Lock()
	rooms := make([]*Room, 0, len(s.rooms))
	for _, r := range s.rooms {
		rooms = append(rooms, r)
	}
	s.mu.Unlock()

	var events []SweepEvent
	var drops []string

	for _, room := range rooms {
		var roomEvents []SweepEvent
		drop := false

		func() {
			room.mu.Lock()
			defer room.mu.Unlock()

			// Expire stale join requests.
			for uid, pj := range room.pending {
				if nowMs-pj.RequestedAtMs > th.PendingJoinTTLMs {
					delete(room.pending, uid)
					roomEvents = append(roomEvents, SweepEvent{
						Code:         room.Code,
						Type:         protocol.TypeJoinRejected,
						Payload:      protocol.JoinRejectedPayload{Reason: "Join request timed out"},
						TargetUserID: uid,
					})
				}
			}

			// Expire members whose disconnect grace has elapsed.
			for uid, m := range room.members {
				if !m.Connected && m.DisconnectedAtMs != 0 &&
					nowMs-m.DisconnectedAtMs > th.DisconnectGraceMs {
					left, newHost := room.removeMemberLocked(uid)
					if left != nil {
						roomEvents = append(roomEvents, SweepEvent{
							Code:          room.Code,
							Type:          protocol.TypeUserLeft,
							Payload:       protocol.UserLeftPayload{UserID: left.UserID, Username: left.Username},
							DroppedTokens: []string{left.Token},
						})
					}
					if newHost != nil {
						roomEvents = append(roomEvents, SweepEvent{
							Code:    room.Code,
							Type:    protocol.TypeHostChanged,
							Payload: protocol.HostChangedPayload{NewHostID: newHost.UserID, NewHostName: newHost.Username},
						})
						roomEvents = append(roomEvents, room.redeliverPendingLocked()...)
					}
				}
			}

			// Track emptiness by connected members.
			connected := 0
			for _, m := range room.members {
				if m.Connected {
					connected++
				}
			}
			if connected == 0 && len(room.members) > 0 && room.emptySinceMs == 0 {
				room.emptySinceMs = nowMs
			}
			if connected > 0 {
				room.emptySinceMs = 0
			}

			if len(room.members) == 0 && room.emptySinceMs == 0 {
				room.emptySinceMs = nowMs
			}

			if room.emptySinceMs != 0 && nowMs-room.emptySinceMs > th.EmptyRoomTTLMs {
				drop = true
			}
			if th.RoomMaxAgeMs > 0 && nowMs-room.CreatedAtMs > th.RoomMaxAgeMs {
				drop = true
			}
		}()

		if drop {
			drops = append(drops, room.Code)
		} else {
			events = append(events, roomEvents...)
		}
	}

	return events, drops
}

// --- room state -----------------------------------------------------------

// Host returns the current host member, or nil if the seat is vacant.
func (r *Room) Host() *Member {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.members[r.HostID]
}

// Member looks up an approved member.
func (r *Room) Member(userID string) *Member {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.members[userID]
}

// IsHost reports whether userID holds host authority.
func (r *Room) IsHost(userID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.HostID == userID && r.members[userID] != nil
}

// ApprovedCount returns the number of approved members (connected or not).
func (r *Room) ApprovedCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.members)
}

// ConnectedMembers returns connected members in join order.
func (r *Room) ConnectedMembers() []*Member {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]*Member, 0, len(r.members))
	for _, uid := range r.order {
		if m := r.members[uid]; m != nil && m.Connected {
			out = append(out, m)
		}
	}
	return out
}

// Snapshot renders the full room state. position is advanced to nowMs and
// anchored there, so the client can use it directly.
func (r *Room) Snapshot(nowMs int64) protocol.RoomState {
	r.mu.Lock()
	defer r.mu.Unlock()

	users := make([]protocol.UserInfo, 0, len(r.members))
	for _, uid := range r.order {
		if m := r.members[uid]; m != nil {
			users = append(users, protocol.UserInfo{
				UserID:      m.UserID,
				Username:    m.Username,
				IsHost:      m.UserID == r.HostID,
				IsConnected: m.Connected,
				AvatarIndex: m.AvatarIndex,
			})
		}
	}
	queue := make([]protocol.TrackInfo, len(r.play.queue))
	copy(queue, r.play.queue)
	var current *protocol.TrackInfo
	if r.play.current != nil {
		track := *r.play.current
		current = &track
	}

	return protocol.RoomState{
		RoomCode:     r.Code,
		HostID:       r.HostID,
		Users:        users,
		CurrentTrack: current,
		IsPlaying:    r.play.isPlaying,
		Position:     r.positionAtLocked(nowMs),
		LastUpdate:   nowMs,
		Volume:       r.play.volume,
		Queue:        queue,
	}
}

// SyncState renders the answer to request_sync. current_track must always be
// present as a key (the Kotlin data class declares it without a default), so
// it marshals to null when no track is loaded.
func (r *Room) SyncState(nowMs int64) protocol.SyncStatePayload {
	r.mu.Lock()
	defer r.mu.Unlock()

	queue := make([]protocol.TrackInfo, len(r.play.queue))
	copy(queue, r.play.queue)
	var current *protocol.TrackInfo
	if r.play.current != nil {
		track := *r.play.current
		current = &track
	}
	volume := r.play.volume

	return protocol.SyncStatePayload{
		CurrentTrack: current,
		IsPlaying:    r.play.isPlaying,
		Position:     r.positionAtLocked(nowMs),
		LastUpdate:   nowMs,
		Queue:        queue,
		Volume:       &volume,
	}
}

// PositionAt returns where the room playhead is at the given server instant.
func (r *Room) PositionAt(nowMs int64) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.positionAtLocked(nowMs)
}

func (r *Room) positionAtLocked(nowMs int64) int64 {
	if !r.play.isPlaying {
		return r.play.positionMs
	}
	pos := r.play.positionMs
	if elapsed := nowMs - r.play.updatedAtMs; elapsed > 0 {
		pos += elapsed
	}
	if r.play.current != nil && r.play.current.Duration > 0 && pos > r.play.current.Duration {
		pos = r.play.current.Duration
	}
	return pos
}

// AddPendingJoiner registers a join request and mints the user id + session
// token the host decision will be addressed with. The token becomes a live
// session only on approval.
func (r *Room) AddPendingJoiner(username string, avatarIndex int, nowMs int64) *PendingJoiner {
	r.mu.Lock()
	defer r.mu.Unlock()
	pj := &PendingJoiner{
		UserID:        newID("u"),
		Username:      username,
		AvatarIndex:   avatarIndex,
		Token:         newToken(),
		RequestedAtMs: nowMs,
	}
	r.pending[pj.UserID] = pj
	return pj
}

// MemberIDs lists the room's APPROVED member ids (pending joiners excluded)
// — the approval gate for room-wide broadcasts.
func (r *Room) MemberIDs() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	ids := make([]string, 0, len(r.members))
	for uid := range r.members {
		ids = append(ids, uid)
	}
	return ids
}

// Pending returns the pending joiner with the server-generated user id.
func (r *Room) Pending(userID string) *PendingJoiner {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.pending[userID]
}

// TakePending removes a pending joiner (rejection, timeout or drop).
func (r *Room) TakePending(userID string) *PendingJoiner {
	r.mu.Lock()
	defer r.mu.Unlock()
	pj := r.pending[userID]
	delete(r.pending, userID)
	return pj
}

// ApprovePending promotes a pending joiner to a member. The returned member
// carries the token the client should persist for reconnects.
func (r *Room) ApprovePending(userID string) (*Member, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	pj := r.pending[userID]
	if pj == nil {
		return nil, errors.New("no such pending joiner")
	}
	delete(r.pending, userID)

	member := &Member{
		UserID:      pj.UserID,
		Username:    pj.Username,
		AvatarIndex: pj.AvatarIndex,
		Token:       pj.Token,
		Connected:   true,
	}
	r.members[member.UserID] = member
	r.order = append(r.order, member.UserID)
	r.emptySinceMs = 0
	return member, nil
}

// RemoveMember takes a member out of the room. If the host left, host
// authority passes to the longest-standing remaining member (who must be
// connected — a disconnected host is only replaced once their grace expires).
func (r *Room) RemoveMember(userID string) (left *Member, newHost *Member) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.removeMemberLocked(userID)
}

func (r *Room) removeMemberLocked(userID string) (*Member, *Member) {
	left := r.members[userID]
	if left == nil {
		return nil, nil
	}
	delete(r.members, userID)
	for i, uid := range r.order {
		if uid == userID {
			r.order = append(r.order[:i], r.order[i+1:]...)
			break
		}
	}
	delete(r.play.bufferReady, userID)

	var newHost *Member
	if r.HostID == userID {
		r.HostID = ""
		for _, uid := range r.order {
			if m := r.members[uid]; m != nil && m.Connected {
				r.HostID = uid
				newHost = m
				break
			}
		}
	}
	return left, newHost
}

// MarkDisconnectedAt orphans the seat when the member's socket dies; the
// reaper decides later whether the seat is reclaimed or released.
func (r *Room) MarkDisconnectedAt(userID string, nowMs int64) *Member {
	r.mu.Lock()
	defer r.mu.Unlock()
	m := r.members[userID]
	if m == nil {
		return nil
	}
	if m.Connected {
		m.Connected = false
		m.DisconnectedAtMs = nowMs
	}
	return m
}

// MarkConnected reattaches a seat after a reconnect. It reports whether the
// member had been flagged disconnected (driving user_reconnected).
func (r *Room) MarkConnected(userID string) (*Member, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	m := r.members[userID]
	if m == nil {
		return nil, false
	}
	wasDisconnected := !m.Connected
	m.Connected = true
	m.DisconnectedAtMs = 0
	r.emptySinceMs = 0
	return m, wasDisconnected
}

// SetHost transfers host authority to an existing member.
func (r *Room) SetHost(userID string) *Member {
	r.mu.Lock()
	defer r.mu.Unlock()
	m := r.members[userID]
	if m == nil {
		return nil
	}
	r.HostID = userID
	return m
}

// ApplyPlayback folds a host playback action into the authoritative state.
func (r *Room) ApplyPlayback(action protocol.PlaybackActionPayload, nowMs int64) {
	r.mu.Lock()
	defer r.mu.Unlock()

	switch action.Action {
	case protocol.ActionPlay:
		if action.Position != nil {
			r.play.positionMs = *action.Position
		}
		r.play.isPlaying = true
		r.play.updatedAtMs = nowMs

	case protocol.ActionPause:
		if action.Position != nil {
			r.play.positionMs = *action.Position
		} else {
			r.play.positionMs = r.positionAtLocked(nowMs)
		}
		r.play.isPlaying = false
		r.play.updatedAtMs = nowMs

	case protocol.ActionSeek:
		if action.Position != nil {
			r.play.positionMs = *action.Position
			r.play.updatedAtMs = nowMs
		}

	case protocol.ActionChangeTrack:
		if action.TrackInfo != nil {
			track := *action.TrackInfo
			r.play.current = &track
		}
		r.play.positionMs = 0
		r.play.isPlaying = false
		r.play.updatedAtMs = nowMs
		if action.Queue != nil {
			queue := make([]protocol.TrackInfo, len(action.Queue))
			copy(queue, action.Queue)
			r.play.queue = queue
		}
		if action.QueueTitle != nil {
			title := *action.QueueTitle
			r.play.queueTitle = &title
		}
		r.play.bufferTrack = ""
		r.play.bufferReady = make(map[string]bool)

	case protocol.ActionQueueAdd:
		if action.TrackInfo != nil {
			track := *action.TrackInfo
			if action.InsertNext != nil && *action.InsertNext {
				r.play.queue = append([]protocol.TrackInfo{track}, r.play.queue...)
			} else {
				r.play.queue = append(r.play.queue, track)
			}
		}

	case protocol.ActionQueueRemove:
		if action.TrackID != nil {
			for i, t := range r.play.queue {
				if t.ID == *action.TrackID {
					r.play.queue = append(r.play.queue[:i], r.play.queue[i+1:]...)
					break
				}
			}
		}

	case protocol.ActionQueueClear:
		r.play.queue = nil

	case protocol.ActionSyncQueue:
		queue := make([]protocol.TrackInfo, len(action.Queue))
		copy(queue, action.Queue)
		r.play.queue = queue
		if action.QueueTitle != nil {
			title := *action.QueueTitle
			r.play.queueTitle = &title
		}

	case protocol.ActionSetVolume:
		if action.Volume != nil {
			v := *action.Volume
			if v < 0 {
				v = 0
			} else if v > 1 {
				v = 1
			}
			r.play.volume = v
		}
	}
}

// BufferRoundResult reports the buffering state after a member declared
// itself ready for a track.
type BufferRoundResult struct {
	TrackID    string
	WaitingFor []string // display usernames of connected guests not ready yet
	Complete   bool
}

// MarkBufferReady records buffer_ready from a member. Only connected guests
// are waited for — the host never reports buffering (it is the one everyone
// else is catching up to).
func (r *Room) MarkBufferReady(userID, trackID string) BufferRoundResult {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.play.bufferTrack != trackID {
		r.play.bufferTrack = trackID
		r.play.bufferReady = make(map[string]bool)
	}
	r.play.bufferReady[userID] = true

	waiting := make([]string, 0)
	for _, uid := range r.order {
		m := r.members[uid]
		if m == nil || !m.Connected || m.UserID == r.HostID {
			continue
		}
		if !r.play.bufferReady[m.UserID] {
			waiting = append(waiting, m.Username)
		}
	}

	if len(waiting) == 0 {
		r.play.bufferTrack = ""
		r.play.bufferReady = make(map[string]bool)
		return BufferRoundResult{TrackID: trackID, WaitingFor: waiting, Complete: true}
	}
	return BufferRoundResult{TrackID: trackID, WaitingFor: waiting, Complete: false}
}

// ResetBuffering drops the buffering round (e.g. the host changed track).
func (r *Room) ResetBuffering() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.play.bufferTrack = ""
	r.play.bufferReady = make(map[string]bool)
}

// AddSuggestion files a guest suggestion for the host.
func (r *Room) AddSuggestion(from *Member, track protocol.TrackInfo, nowMs int64) *Suggestion {
	r.mu.Lock()
	defer r.mu.Unlock()
	sug := &Suggestion{
		ID:           newID("s"),
		FromUserID:   from.UserID,
		FromUsername: from.Username,
		Track:        track,
		CreatedAtMs:  nowMs,
	}
	r.suggestions[sug.ID] = sug
	return sug
}

// TakeSuggestion resolves and removes a suggestion by id.
func (r *Room) TakeSuggestion(id string) *Suggestion {
	r.mu.Lock()
	defer r.mu.Unlock()
	sug := r.suggestions[id]
	delete(r.suggestions, id)
	return sug
}

// redeliverPendingLocked rebuilds join_request frames for a new host after a
// host transfer, so requests are not lost with the old host's socket.
func (r *Room) redeliverPendingLocked() []SweepEvent {
	events := make([]SweepEvent, 0, len(r.pending))
	host := r.members[r.HostID]
	if host == nil {
		return events
	}
	for _, pj := range r.pending {
		events = append(events, SweepEvent{
			Code:         r.Code,
			Type:         protocol.TypeJoinRequest,
			Payload:      protocol.JoinRequestPayload{UserID: pj.UserID, Username: pj.Username, AvatarIndex: pj.AvatarIndex},
			TargetUserID: host.UserID,
		})
	}
	return events
}

// SanitiseUsername trims and bounds a display name.
func SanitiseUsername(name string, max int) string {
	name = strings.TrimSpace(name)
	if name == "" {
		return "Listener"
	}
	runes := []rune(name)
	if len(runes) > max {
		runes = runes[:max]
	}
	return string(runes)
}

// PendingList returns the outstanding join requests in a stable order, so a
// new host can be handed the ones the old host never answered.
func (r *Room) PendingList() []*PendingJoiner {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]*PendingJoiner, 0, len(r.pending))
	for _, pj := range r.pending {
		out = append(out, pj)
	}
	return out
}
