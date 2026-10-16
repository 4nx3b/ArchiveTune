// Package hub tracks which member of which room holds a WebSocket and how a
// frame reaches everyone in that room.
//
// Adapted from BitChord's Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
package hub

import (
	"sync"

	"moe.rukamori.archivetune/listentogether-server/ws"
)

// Hub coordinates active WebSocket connections across rooms: room code ->
// member id -> connection. Pending joiners live here too, under the
// server-generated user id they were announced with, so approve_join can
// reach the socket that asked to join.
type Hub struct {
	mu      sync.RWMutex
	sockets map[string]map[string]*ws.Conn
}

func NewHub() *Hub {
	return &Hub{sockets: make(map[string]map[string]*ws.Conn)}
}

// Attach registers a connection for (code, userID). A previous connection for
// the same member is closed outside the lock — one member, one socket, the
// freshest one wins.
func (h *Hub) Attach(code, userID string, conn *ws.Conn) {
	h.mu.Lock()
	room, ok := h.sockets[code]
	if !ok {
		room = make(map[string]*ws.Conn)
		h.sockets[code] = room
	}
	prev := room[userID]
	room[userID] = conn
	h.mu.Unlock()

	if prev != nil && prev != conn {
		_ = prev.Close()
	}
}

// Detach removes the connection only if it is still the registered one; it
// reports whether this connection was in fact the owner (a false return
// means a newer socket has already taken the seat).
func (h *Hub) Detach(code, userID string, conn *ws.Conn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()

	room, ok := h.sockets[code]
	if !ok {
		return false
	}
	if room[userID] != conn {
		return false
	}
	delete(room, userID)
	if len(room) == 0 {
		delete(h.sockets, code)
	}
	return true
}

// DetachUser removes whatever connection is registered for the member WITHOUT
// closing it — used when a member leaves the room but keeps its socket open
// in the lobby (the client does not disconnect on leave).
func (h *Hub) DetachUser(code, userID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	room := h.sockets[code]
	if room == nil {
		return
	}
	delete(room, userID)
	if len(room) == 0 {
		delete(h.sockets, code)
	}
}

// DropRoom closes every socket in a room and forgets the room.
func (h *Hub) DropRoom(code string) {
	h.mu.Lock()
	room := h.sockets[code]
	delete(h.sockets, code)
	h.mu.Unlock()

	if room != nil {
		for _, conn := range room {
			_ = conn.Close()
		}
	}
}

// CloseMember closes one member's socket (kick), leaving the registry entry
// to be cleaned up by the read loop that owns it.
func (h *Hub) CloseMember(code, userID string) {
	h.mu.RLock()
	room := h.sockets[code]
	var target *ws.Conn
	if room != nil {
		target = room[userID]
	}
	h.mu.RUnlock()
	if target != nil {
		_ = target.Close()
	}
}

// Send delivers a pre-marshalled envelope to one member, if connected.
func (h *Hub) Send(code, userID string, frame []byte) {
	h.mu.RLock()
	room := h.sockets[code]
	var target *ws.Conn
	if room != nil {
		target = room[userID]
	}
	h.mu.RUnlock()

	if target != nil {
		_ = target.WriteMessage(ws.OpcodeText, frame)
	}
}

// Broadcast delivers a pre-marshalled envelope to every connection in the
// room except skip ("" skips nobody). Writes fan out concurrently so one slow
// mobile network does not delay the others.
// BroadcastMembers delivers a frame ONLY to the room's approved members:
// pending joiners stay behind the approval gate for every room broadcast —
// the gate is server-side, not just hidden in the app UI.
func (h *Hub) BroadcastMembers(code string, members []string, frame []byte, skip string) {
	allowed := make(map[string]struct{}, len(members))
	for _, uid := range members {
		allowed[uid] = struct{}{}
	}
	h.mu.RLock()
	room := h.sockets[code]
	if len(room) == 0 {
		h.mu.RUnlock()
		return
	}
	targets := make([]*ws.Conn, 0, len(room))
	for uid, conn := range room {
		if uid == skip {
			continue
		}
		if _, ok := allowed[uid]; !ok {
			continue
		}
		targets = append(targets, conn)
	}
	h.mu.RUnlock()

	var wg sync.WaitGroup
	for _, conn := range targets {
		wg.Add(1)
		go func(c *ws.Conn) {
			defer wg.Done()
			_ = c.WriteMessage(ws.OpcodeText, frame)
		}(conn)
	}
	wg.Wait()
}

func (h *Hub) Broadcast(code string, frame []byte, skip string) {
	h.mu.RLock()
	room := h.sockets[code]
	if len(room) == 0 {
		h.mu.RUnlock()
		return
	}
	targets := make([]*ws.Conn, 0, len(room))
	for uid, conn := range room {
		if uid != skip {
			targets = append(targets, conn)
		}
	}
	h.mu.RUnlock()

	var wg sync.WaitGroup
	for _, conn := range targets {
		wg.Add(1)
		go func(c *ws.Conn) {
			defer wg.Done()
			_ = c.WriteMessage(ws.OpcodeText, frame)
		}(conn)
	}
	wg.Wait()
}

// IsAttached reports whether the member currently holds a socket.
func (h *Hub) IsAttached(code, userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	room := h.sockets[code]
	return room != nil && room[userID] != nil
}

// Counts returns rooms and sockets for the ops endpoints.
func (h *Hub) Counts() (rooms int, sockets int) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	rooms = len(h.sockets)
	for _, room := range h.sockets {
		sockets += len(room)
	}
	return rooms, sockets
}

// Rekey moves a connection from one registry key to another (pending joiner
// promoted to member) without touching the socket itself.
func (h *Hub) Rekey(code, oldKey, newKey string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	room, ok := h.sockets[code]
	if !ok {
		return
	}
	conn := room[oldKey]
	if conn == nil {
		return
	}
	delete(room, oldKey)
	room[newKey] = conn
}
