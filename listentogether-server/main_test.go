// ArchiveTune — Listen Together server integration tests.
//
// These tests drive the WebSocket handler end to end using the exact JSON the
// ArchiveTune client puts on the wire: envelopes of {"type": ..., "payload":
// ...} sent as BINARY frames (ListenTogetherClient sends
// okio.ByteString.of(*data)), with Kotlin-defaulted fields omitted, matching
// MessageCodec.kt (kotlinx.serialization, encodeDefaults=false).
//
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"moe.rukamori.archivetune/listentogether-server/clock"
	"moe.rukamori.archivetune/listentogether-server/party"
	"moe.rukamori.archivetune/listentogether-server/protocol"
	"moe.rukamori.archivetune/listentogether-server/ws"
)

// --- test client -----------------------------------------------------------

type testClient struct {
	t    *testing.T
	conn *ws.Conn
}

func dialWS(t *testing.T, rawURL string) *testClient {
	t.Helper()
	conn, resp, err := ws.Dial(rawURL, nil, ws.Limits{
		MaxMessageSize: 1 << 20,
		IdleTimeout:    3 * time.Second,
		WriteTimeout:   5 * time.Second,
	})
	if err != nil {
		t.Fatalf("ws.Dial(%s): %v (resp=%v)", rawURL, err, resp)
	}
	return &testClient{t: t, conn: conn}
}

// send writes one envelope as a BINARY frame, exactly like the Android
// client (okio.ByteString.of(*data) -> WebSocket.send(ByteString)).
func (c *testClient) send(envelope string) {
	c.t.Helper()
	if err := c.conn.WriteMessage(ws.OpcodeBinary, []byte(envelope)); err != nil {
		c.t.Fatalf("send %s: %v", envelope, err)
	}
}

// sendText writes one envelope as a text frame; the server must accept both.
func (c *testClient) sendText(envelope string) {
	c.t.Helper()
	if err := c.conn.WriteMessage(ws.OpcodeText, []byte(envelope)); err != nil {
		c.t.Fatalf("sendText %s: %v", envelope, err)
	}
}

// recv reads the next envelope and returns its type and decoded payload.
func (c *testClient) recv() (string, map[string]any) {
	c.t.Helper()
	_, data, err := c.conn.ReadMessage()
	if err != nil {
		c.t.Fatalf("recv: %v", err)
	}
	var env struct {
		Type    string          `json:"type"`
		Payload json.RawMessage `json:"payload"`
	}
	if err := json.Unmarshal(data, &env); err != nil {
		c.t.Fatalf("recv: bad envelope %q: %v", string(data), err)
	}
	payload := map[string]any{}
	if len(env.Payload) > 0 {
		if err := json.Unmarshal(env.Payload, &payload); err != nil {
			c.t.Fatalf("recv: bad payload %q: %v", string(env.Payload), err)
		}
	}
	return env.Type, payload
}

func (c *testClient) expectType(want string) map[string]any {
	c.t.Helper()
	got, payload := c.recv()
	if got != want {
		c.t.Fatalf("expected %s, got %s (payload %v)", want, got, payload)
	}
	return payload
}

func (c *testClient) expectNoMessage(within time.Duration, why string) {
	c.t.Helper()
	deadline := time.Now().Add(within)
	for time.Now().Before(deadline) {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			return // idle timeout: nothing arrived
		}
		c.t.Fatalf("expected silence (%s), but a message arrived", why)
	}
	c.t.Fatalf("expected silence (%s)", why)
}

func (c *testClient) close() {
	_ = c.conn.CloseNow()
}

func newTestServer(t *testing.T) string {
	t.Helper()
	ts := httptest.NewServer(newRouter())
	t.Cleanup(ts.Close)
	return strings.Replace(ts.URL, "http://", "ws://", 1)
}

// --- REST ------------------------------------------------------------------

func TestRESTEndpoints(t *testing.T) {
	ts := httptest.NewServer(newRouter())
	defer ts.Close()

	for _, path := range []string{"/healthz", "/api/time", "/time", "/api/stats", "/stats"} {
		resp, err := http.Get(ts.URL + path)
		if err != nil {
			t.Fatalf("GET %s: %v", path, err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET %s: status %d", path, resp.StatusCode)
		}
		var decoded map[string]any
		if err := json.Unmarshal(body, &decoded); err != nil {
			t.Fatalf("GET %s: body is not JSON: %s", path, string(body))
		}
		if path == "/healthz" && decoded["ok"] != true {
			t.Fatalf("healthz ok != true: %s", string(body))
		}
		if path == "/api/time" || path == "/time" {
			if _, ok := decoded["serverMs"]; !ok {
				t.Fatalf("time endpoint missing serverMs: %s", string(body))
			}
		}
	}

	// The root answers a plain browser GET with an info page, not an upgrade.
	resp, err := http.Get(ts.URL + "/")
	if err != nil {
		t.Fatalf("GET /: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /: status %d", resp.StatusCode)
	}
}

// --- WebSocket handshake on any path ----------------------------------------

func TestWebSocketAcceptsRootAndSubpath(t *testing.T) {
	base := newTestServer(t)
	for _, path := range []string{"/", "/ws", "/listentogether"} {
		c := dialWS(t, base+path)
		// The ArchiveTune client's ping is a bare envelope with no payload.
		c.send(`{"type":"ping"}`)
		got, _ := c.recv()
		if got != "pong" {
			t.Fatalf("path %s: expected pong, got %s", path, got)
		}
		c.close()
	}
}

// --- the full room lifecycle -------------------------------------------------

func TestCreateJoinPlaybackFlow(t *testing.T) {
	base := newTestServer(t)

	// Host connects and creates a room. MessageCodec.kt omits Kotlin-default
	// fields (encodeDefaults=false), so avatar_index:0 is absent.
	host := dialWS(t, base+"/")
	host.send(`{"type":"create_room","payload":{"username":"HostUser"}}`)
	created := host.expectType("room_created")
	roomCode, _ := created["room_code"].(string)
	hostUserID, _ := created["user_id"].(string)
	hostToken, _ := created["session_token"].(string)
	if len(roomCode) != 6 || hostUserID == "" || hostToken == "" {
		t.Fatalf("bad room_created payload: %v", created)
	}

	// A second create on the same socket auto-leaves the old room.
	host.send(`{"type":"create_room","payload":{"username":"HostUser2"}}`)
	created2 := host.expectType("room_created")
	roomCode2, _ := created2["room_code"].(string)
	if roomCode2 == roomCode {
		t.Fatalf("expected a fresh room code, got %s twice", roomCode)
	}
	host.send(`{"type":"create_room","payload":{"username":"HostUser"}}`)
	created3 := host.expectType("room_created")
	roomCode, _ = created3["room_code"].(string)
	hostUserID, _ = created3["user_id"].(string)
	hostToken, _ = created3["session_token"].(string)

	// Joining a bogus room is rejected with a human-readable reason.
	stranger := dialWS(t, base+"/")
	stranger.send(`{"type":"join_room","payload":{"room_code":"ZZZZZZ","username":"Nobody"}}`)
	stranger.expectType("join_rejected")
	stranger.close()

	// Guest asks to join the live room.
	guest := dialWS(t, base+"/")
	guest.send(fmt.Sprintf(`{"type":"join_room","payload":{"room_code":%q,"username":"GuestUser"}}`, roomCode))

	// The host sees join_request with the server-generated user_id.
	joinReq := host.expectType("join_request")
	guestPendingID, _ := joinReq["user_id"].(string)
	if guestPendingID == "" || joinReq["username"] != "GuestUser" {
		t.Fatalf("bad join_request payload: %v", joinReq)
	}

	// While pending, the guest cannot control playback.
	guest.send(`{"type":"playback_action","payload":{"action":"play","position":1000}}`)
	errPayload := guest.expectType("error")
	if errPayload["code"] != "not_in_room" {
		t.Fatalf("pending guest playback_action should error not_in_room, got %v", errPayload)
	}

	// While pending, room broadcasts must NOT reach the joiner — the approval
	// gate is server-side, not just UI. The host chats; the guest hears
	// nothing until it is approved.
	host.send(`{"type":"chat","payload":{"message":"secret before approval"}}`)
	hostChatEcho := host.expectType("chat")
	if hostChatEcho["message"] != "secret before approval" {
		t.Fatalf("bad host chat echo: %v", hostChatEcho)
	}

	// Host approves; the guest is handed the full state.
	host.send(fmt.Sprintf(`{"type":"approve_join","payload":{"user_id":%q}}`, guestPendingID))
	approved := guest.expectType("join_approved")
	if approved["room_code"] != roomCode {
		t.Fatalf("join_approved room_code mismatch: %v", approved)
	}
	guestUserID, _ := approved["user_id"].(string)
	guestToken, _ := approved["session_token"].(string)
	if guestUserID != guestPendingID || guestToken == "" {
		t.Fatalf("bad join_approved payload: %v", approved)
	}
	state, _ := approved["state"].(map[string]any)
	if state == nil || state["host_id"] != hostUserID {
		t.Fatalf("join_approved state.host_id mismatch: %v", approved)
	}
	users, _ := state["users"].([]any)
	if len(users) != 2 {
		t.Fatalf("join_approved state should list 2 users, got %v", users)
	}
	if _, hasCurrent := state["current_track"]; hasCurrent {
		t.Fatalf("current_track should be omitted when null: %v", state)
	}
	if state["is_playing"] != false || state["volume"] != float64(1) {
		t.Fatalf("join_approved initial playback state wrong: %v", state)
	}

	// Everyone except the new member is told user_joined.
	userJoined := host.expectType("user_joined")
	if userJoined["user_id"] != guestUserID || userJoined["username"] != "GuestUser" {
		t.Fatalf("bad user_joined payload: %v", userJoined)
	}

	// Host plays: broadcast to everyone including the host itself (the host's
	// client applies queue ops from its own echo).
	host.send(`{"type":"playback_action","payload":{"action":"play","position":42000}}`)
	for _, who := range []*testClient{host, guest} {
		sync := who.expectType("sync_playback")
		action, _ := sync["action"].(string)
		if action != "play" {
			t.Fatalf("sync_playback action mismatch: %v", sync)
		}
		if sync["position"] != float64(42000) {
			t.Fatalf("sync_playback position mismatch: %v", sync)
		}
		if serverTime, _ := sync["server_time"].(float64); serverTime <= 0 {
			t.Fatalf("sync_playback missing server_time: %v", sync)
		}
	}

	// Guests cannot control playback — host authority is server-enforced.
	guest.send(`{"type":"playback_action","payload":{"action":"pause","position":1}}`)
	errPayload = guest.expectType("error")
	if errPayload["code"] != "not_host" {
		t.Fatalf("guest playback_action should error not_host, got %v", errPayload)
	}

	// Host changes track with a track_info + queue, the exact shape the
	// Android host sends (sendPlaybackAction(CHANGE_TRACK, queueTitle=...,
	// trackInfo=..., queue=...)).
	host.send(`{"type":"playback_action","payload":{"action":"change_track","queue_title":"Listen Together","track_info":{"id":"track1","title":"Song One","artist":"Artist A","duration":180000},"queue":[{"id":"track2","title":"Song Two","artist":"Artist B","duration":200000}]}}`)
	change := guest.expectType("sync_playback")
	if change["action"] != "change_track" {
		t.Fatalf("expected change_track, got %v", change)
	}
	track, _ := change["track_info"].(map[string]any)
	if track == nil || track["id"] != "track1" || track["title"] != "Song One" {
		t.Fatalf("bad change_track track_info: %v", change)
	}
	if queue, _ := change["queue"].([]any); len(queue) != 1 {
		t.Fatalf("change_track queue should carry 1 item: %v", change)
	}
	host.expectType("sync_playback") // host echo

	// Buffering: the guest reports ready, and with the guest being the only
	// one waited for, everyone gets buffer_complete.
	guest.send(`{"type":"buffer_ready","payload":{"track_id":"track1"}}`)
	bc := host.expectType("buffer_complete")
	if bc["track_id"] != "track1" {
		t.Fatalf("bad buffer_complete: %v", bc)
	}
	guest.expectType("buffer_complete")

	// Chat echoes to the sender as well (the client dedupes its own echo).
	guest.send(`{"type":"chat","payload":{"message":"hello room"}}`)
	for _, who := range []*testClient{host, guest} {
		chat := who.expectType("chat")
		if chat["message"] != "hello room" {
			t.Fatalf("bad chat echo: %v", chat)
		}
		if chat["user_id"] != guestUserID || chat["username"] != "GuestUser" {
			t.Fatalf("chat identity must come from the server: %v", chat)
		}
		if ts, _ := chat["timestamp"].(float64); ts <= 0 {
			t.Fatalf("chat timestamp must be a server epoch ms value: %v", chat)
		}
	}

	// Text frames (any other vivi-music client) are accepted too.
	guest.sendText(`{"type":"ping"}`)
	if got, _ := guest.recv(); got != "pong" {
		t.Fatalf("expected pong for text ping, got %s", got)
	}

	// request_sync returns the anchored state, with current_track always
	// present as a key (SyncStatePayload declares it without a default).
	guest.send(`{"type":"request_sync"}`)
	syncState := guest.expectType("sync_state")
	if ct, _ := syncState["current_track"].(map[string]any); ct == nil || ct["id"] != "track1" {
		t.Fatalf("sync_state current_track mismatch: %v", syncState)
	}
	if syncState["is_playing"] != false {
		t.Fatalf("sync_state should be paused after change_track: %v", syncState)
	}
	if pos, _ := syncState["position"].(float64); pos != 0 {
		t.Fatalf("sync_state position should be 0: %v", syncState)
	}
	if lu, _ := syncState["last_update"].(float64); lu <= 0 {
		t.Fatalf("sync_state last_update missing: %v", syncState)
	}
	if q, _ := syncState["queue"].([]any); len(q) != 1 {
		t.Fatalf("sync_state queue mismatch: %v", syncState)
	}

	// Play and check the anchored position advances while playing.
	host.send(`{"type":"playback_action","payload":{"action":"play","position":60000}}`)
	host.expectType("sync_playback")
	guest.expectType("sync_playback")
	guest.send(`{"type":"request_sync"}`)
	syncState = guest.expectType("sync_state")
	pos, _ := syncState["position"].(float64)
	if pos < 60000 || pos > 62000 {
		t.Fatalf("sync_state position should advance from 60000 while playing, got %v", pos)
	}

	// Volume sync.
	host.send(`{"type":"playback_action","payload":{"action":"set_volume","volume":0.5}}`)
	volSync := guest.expectType("sync_playback")
	if volSync["volume"] != 0.5 {
		t.Fatalf("set_volume mismatch: %v", volSync)
	}
	host.expectType("sync_playback")

	// Track suggestions: guest suggests, host approves, suggester is told.
	guest.send(`{"type":"suggest_track","payload":{"track_info":{"id":"track9","title":"Suggested","artist":"Artist C","duration":90000}}}`)
	suggestion := host.expectType("suggestion_received")
	sugID, _ := suggestion["suggestion_id"].(string)
	if sugID == "" || suggestion["from_user_id"] != guestUserID || suggestion["from_username"] != "GuestUser" {
		t.Fatalf("bad suggestion_received: %v", suggestion)
	}
	host.send(fmt.Sprintf(`{"type":"approve_suggestion","payload":{"suggestion_id":%q}}`, sugID))
	approvedSuggestion := guest.expectType("suggestion_approved")
	if approvedSuggestion["suggestion_id"] != sugID {
		t.Fatalf("bad suggestion_approved: %v", approvedSuggestion)
	}
	if st, _ := approvedSuggestion["track_info"].(map[string]any); st == nil || st["id"] != "track9" {
		t.Fatalf("suggestion_approved should echo the track: %v", approvedSuggestion)
	}

	// Kick: the target is told, then removed, then its socket is closed.
	host.send(fmt.Sprintf(`{"type":"kick_user","payload":{"user_id":%q,"reason":"bye"}}`, guestUserID))
	kicked := guest.expectType("kicked")
	if kicked["reason"] != "bye" {
		t.Fatalf("bad kicked payload: %v", kicked)
	}
	left := host.expectType("user_left")
	if left["user_id"] != guestUserID || left["username"] != "GuestUser" {
		t.Fatalf("bad user_left payload: %v", left)
	}
	if _, _, err := guest.conn.ReadMessage(); err == nil {
		t.Fatalf("kicked socket should have been closed by the server")
	}

	// The kicked member's session token is dead.
	reconnected := dialWS(t, base+"/")
	reconnected.send(fmt.Sprintf(`{"type":"reconnect","payload":{"session_token":%q}}`, guestToken))
	errPayload = reconnected.expectType("error")
	if errPayload["code"] != "session_not_found" {
		t.Fatalf("kicked member's token should be invalid, got %v", errPayload)
	}
	reconnected.close()

	// Host transfer: a second guest joins while the host is still present, so
	// the host leaving hands the crown to the remaining member.
	guest2 := dialWS(t, base+"/")
	guest2.send(fmt.Sprintf(`{"type":"join_room","payload":{"room_code":%q,"username":"GuestTwo"}}`, roomCode))
	joinReq = host.expectType("join_request")
	guest2ID, _ := joinReq["user_id"].(string)
	host.send(fmt.Sprintf(`{"type":"approve_join","payload":{"user_id":%q}}`, guest2ID))
	guest2.expectType("join_approved")
	host.expectType("user_joined")

	host.send(`{"type":"leave_room"}`)
	left = guest2.expectType("user_left")
	if left["user_id"] != hostUserID || left["username"] != "HostUser" {
		t.Fatalf("user_left should be the departing host: %v", left)
	}
	changed := guest2.expectType("host_changed")
	if changed["new_host_id"] != guest2ID || changed["new_host_name"] != "GuestTwo" {
		t.Fatalf("bad host_changed payload: %v", changed)
	}

	// leave_room has no reply: the leaver's socket stays open in the lobby
	// (the client does not disconnect on leave) and hears nothing further.
	host.expectNoMessage(700*time.Millisecond, "leave_room has no reply")

	// The promoted guest can now control playback.
	guest2.send(`{"type":"playback_action","payload":{"action":"pause","position":10}}`)
	guest2.expectType("sync_playback")

	guest2.close()
	host.close()
}

// --- reconnect --------------------------------------------------------------

func TestReconnectFlow(t *testing.T) {
	base := newTestServer(t)

	host := dialWS(t, base+"/")
	host.send(`{"type":"create_room","payload":{"username":"ReconnectHost"}}`)
	created := host.expectType("room_created")
	roomCode, _ := created["room_code"].(string)
	hostToken, _ := created["session_token"].(string)
	if hostToken == "" {
		t.Fatalf("create_room must hand out a session token: %v", created)
	}

	guest := dialWS(t, base+"/")
	guest.send(fmt.Sprintf(`{"type":"join_room","payload":{"room_code":%q,"username":"ReconnectGuest"}}`, roomCode))
	joinReq := host.expectType("join_request")
	pendingID, _ := joinReq["user_id"].(string)
	host.send(fmt.Sprintf(`{"type":"approve_join","payload":{"user_id":%q}}`, pendingID))
	approved := guest.expectType("join_approved")
	guestToken, _ := approved["session_token"].(string)
	host.expectType("user_joined")

	// The guest's socket dies without a close frame (tunnel drop).
	guest.close()

	// Members are told user_disconnected, not user_left.
	disconnected := host.expectType("user_disconnected")
	if disconnected["user_id"] != pendingID || disconnected["username"] != "ReconnectGuest" {
		t.Fatalf("bad user_disconnected: %v", disconnected)
	}

	// While inside the grace period the seat survives a manual sweep.
	events, drops := store.Sweep(clock.NowMs(), party.Thresholds{
		DisconnectGraceMs: 90_000,
		EmptyRoomTTLMs:    120_000,
		RoomMaxAgeMs:      12 * 60 * 60 * 1000,
		PendingJoinTTLMs:  120_000,
	})
	if len(drops) != 0 || len(events) != 0 {
		t.Fatalf("nothing should be swept inside the grace period, got events=%v drops=%v", events, drops)
	}

	// The guest comes back with its session token on a fresh socket.
	guest2 := dialWS(t, base+"/")
	guest2.send(fmt.Sprintf(`{"type":"reconnect","payload":{"session_token":%q}}`, guestToken))
	reconnected := guest2.expectType("reconnected")
	if reconnected["room_code"] != roomCode || reconnected["user_id"] != pendingID {
		t.Fatalf("bad reconnected payload: %v", reconnected)
	}
	if reconnected["is_host"] != false {
		t.Fatalf("reconnected is_host should be false: %v", reconnected)
	}
	if state, _ := reconnected["state"].(map[string]any); state == nil {
		t.Fatalf("reconnected must carry the room state: %v", reconnected)
	}
	ur := host.expectType("user_reconnected")
	if ur["user_id"] != pendingID || ur["username"] != "ReconnectGuest" {
		t.Fatalf("bad user_reconnected: %v", ur)
	}

	// Unknown tokens answer session_not_found so the client can rejoin.
	stranger := dialWS(t, base+"/")
	stranger.send(`{"type":"reconnect","payload":{"session_token":"deadbeef"}}`)
	errPayload := stranger.expectType("error")
	if errPayload["code"] != "session_not_found" {
		t.Fatalf("expected session_not_found, got %v", errPayload)
	}
	stranger.close()

	// After the grace period expires with the socket gone, the seat is
	// released: user_left (+ host_changed when it was the host).
	host.close()
	// Reading the user_disconnected frame on the guest proves the server's
	// cleanup for the host socket has completed.
	if got, payload := guest2.recv(); got != "user_disconnected" {
		t.Fatalf("expected user_disconnected for the dropped host, got %s %v", got, payload)
	}
	events, drops = store.Sweep(clock.NowMs()+95_000, party.Thresholds{
		DisconnectGraceMs: 90_000,
		EmptyRoomTTLMs:    120_000,
		RoomMaxAgeMs:      12 * 60 * 60 * 1000,
		PendingJoinTTLMs:  120_000,
	})
	sawUserLeft, sawHostChanged := false, false
	for _, ev := range events {
		if ev.Type == protocol.TypeUserLeft {
			sawUserLeft = true
		}
		if ev.Type == protocol.TypeHostChanged {
			sawHostChanged = true
		}
	}
	if !sawUserLeft || !sawHostChanged {
		t.Fatalf("grace expiry should emit user_left and host_changed, got %v", events)
	}
	// Delivering those events drops the expired tokens (the host's) while the
	// still-connected guest keeps its session and its room.
	for _, ev := range events {
		deliverSweepEvent(ev)
	}
	// The remaining member receives the frames the reaper produced.
	if got, _ := guest2.recv(); got != "user_left" {
		t.Fatalf("expected user_left from the reaper, got %s", got)
	}
	if got, payload := guest2.recv(); got != "host_changed" {
		t.Fatalf("expected host_changed from the reaper, got %s %v", got, payload)
	}
	if _, ok := store.LookupSession(hostToken); ok {
		t.Fatalf("expired host's session token should have been dropped")
	}
	if _, ok := store.LookupSession(guestToken); !ok {
		t.Fatalf("connected guest's session token must survive the host's expiry")
	}
	if len(drops) != 0 {
		t.Fatalf("room with a connected member must not be dropped, got %v", drops)
	}

	// The promoted guest can now steer playback.
	guest2.send(`{"type":"playback_action","payload":{"action":"pause","position":5}}`)
	guest2.expectType("sync_playback")

	guest2.close()
}

// --- protocol details -------------------------------------------------------

func TestSyncStateAlwaysCarriesCurrentTrackKey(t *testing.T) {
	// SyncStatePayload.currentTrack has no Kotlin default, so the server must
	// always emit the key — null when no track is loaded.
	base := newTestServer(t)
	host := dialWS(t, base+"/")
	host.send(`{"type":"create_room","payload":{"username":"Trackless"}}`)
	host.expectType("room_created")
	host.send(`{"type":"request_sync"}`)
	_, payload := host.recv()
	// Recv() decodes the payload map; a JSON null payload decodes to nil map.
	if payload == nil {
		t.Fatalf("request_sync must answer a sync_state payload, got none")
	}
	if _, present := payload["current_track"]; !present {
		t.Fatalf("sync_state payload must always carry the current_track key: %v", payload)
	}
	host.close()
}

func TestEnvelopeOmitsNullPayload(t *testing.T) {
	// Messages without payloads (ping, leave_room) must serialise without a
	// "payload" key: the Kotlin Message data class defaults it to null.
	frame, err := protocol.NewEnvelope("ping", nil)
	if err != nil {
		t.Fatalf("NewEnvelope: %v", err)
	}
	if strings.Contains(string(frame), "payload") {
		t.Fatalf("null-payload envelope should omit the key: %s", string(frame))
	}
}
