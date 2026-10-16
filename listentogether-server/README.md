# ArchiveTune — Listen Together server

A self-hostable **Listen Together** server for the ArchiveTune Android app: one
room code, one host, everyone hears the same thing at the same time. Rooms
live in memory, host authority is enforced server-side, playback is
synchronised off server timestamps, and the whole thing is a **single Go
binary with zero dependencies beyond the standard library** — no database, no
Redis, no modules to fetch.

The server speaks the **vivi-music JSON wire protocol** that ArchiveTune's
`listentogether/MessageCodec.kt`, `Protocol.kt` and `ListenTogetherClient.kt`
implement (the protocol the public vivi-music / Metrolist servers speak), and
additionally exposes a BitChord-style REST surface for ops.

The server layout — `clock/`, `codes/`, `config/`, `hub/`, `party/`,
`protocol/`, the reaper loop, the per-IP room-create limiter, the systemd +
Caddy deploy files — is ported from **BitChord's Listen Together backend**
(GPL-3.0): https://github.com/kushagrasinghx/BitChord — adapted to the
vivi-music protocol and rewritten around a minimal, dependency-free RFC 6455
WebSocket implementation (`ws/`) so `go build` never needs the network.

```
go run .          # listens on :8080 (PORT env var)
go test ./...     # end-to-end tests over real WebSockets
go build -o listentogether-server . && ./listentogether-server
```

---

## Which URL the app connects to

The ArchiveTune client opens its WebSocket against the **server URL itself**
(`Request.Builder().url(serverUrl)` in `ListenTogetherClient.connect`) — it
appends no path. This server therefore accepts the WebSocket upgrade on **any
path**:

- `ws://host:8080` / `wss://host` → works
- `ws://host:8080/ws` / `wss://host/ws` → works (same handler)

A plain browser GET (no `Upgrade` header) gets a small JSON info page
instead. Point the app at your server with the URL you typed into
*Settings → Listen Together → Choose server → Custom server*
(`wss://your.host` behind TLS, or `ws://host:8080` for LAN testing).

> **Heads-up about the app (fixed client-side, not here):** as of writing,
> ArchiveTune's `ListenTogetherClient.getServerUrl()` silently falls back to
> the default public server when the saved URL is not one of the built-in
> entries — a custom URL saved from the settings screen is *stored* but never
> *used*. Until that client-side gate is relaxed, testing against this server
> from the app requires a client tweak (or adding your URL to the built-in
> `ListenTogetherServers` list). The server itself is fully compatible; see
> the "Protocol notes" section for everything the client actually does on the
> wire.

## REST surface (ops)

| | |
|---|---|
| `GET /healthz` | `{"ok":true,"serverMs":…}` — liveness, load-balancer checks |
| `GET /api/time` (alias `/time`) | `{"serverMs":…}` — a server clock sample |
| `GET /api/stats` (alias `/stats`) | `{"rooms":…,"sockets":…,"maxRooms":…,"uptimeSec":…,"serverMs":…}` |

## WebSocket protocol summary

Every frame is one JSON envelope — text **or** binary (the Android client
sends *binary* frames containing JSON; both are accepted, replies are text):

```jsonc
{"type": "create_room", "payload": { "username": "Alice" }}
{"type": "ping"}                       // no payload key when there is none
```

Kotlin-defaulted fields are omitted on the wire
(`MessageCodec` runs kotlinx.serialization with `encodeDefaults=false`), and
the server follows the same rule: fields the client declares with a default
are omitted when unset, fields without one (e.g. `sync_state.current_track`,
`chat.timestamp`) are always present.

### Client → server

| type | payload | notes |
|---|---|---|
| `create_room` | `{username, avatar_index?}` | one socket = one room; re-creating auto-leaves |
| `join_room` | `{room_code, username, avatar_index?}` | forwarded to the host as `join_request` |
| `approve_join` / `reject_join` | `{user_id, reason?}` | host-only decision on a pending joiner |
| `leave_room` | — | socket stays open in a lobby; no reply |
| `playback_action` | `{action, track_id?, position?, track_info?, insert_next?, queue?, queue_title?, volume?, server_time?}` | **host-only**, server-enforced |
| `buffer_ready` | `{track_id}` | guests report buffering of the current track |
| `kick_user` | `{user_id, reason?}` | host-only |
| `transfer_host` | `{new_host_id}` | host-only |
| `ping` | — | answered with `pong` |
| `chat` | `{message, reply_to?}` | relayed verbatim (reply/GIF/shared-track/custom-avatar envelopes ride in the message text) |
| `request_sync` | — | answered with `sync_state` |
| `reconnect` | `{session_token}` | reclaim your seat after a drop |
| `suggest_track` | `{track_info}` | guest → host suggestion |
| `approve_suggestion` / `reject_suggestion` | `{suggestion_id, reason?}` | host-only |

Playback actions: `play`, `pause`, `seek`, `skip_next`, `skip_prev`,
`change_track`, `queue_add`, `queue_remove`, `queue_clear`, `sync_queue`,
`set_volume`.

### Server → client

| type | payload | to whom |
|---|---|---|
| `room_created` | `{room_code, user_id, session_token}` | creator |
| `join_request` | `{user_id, username, avatar_index}` | host (`user_id` is server-generated) |
| `join_approved` | `{room_code, user_id, session_token, state}` | joiner |
| `join_rejected` | `{reason}` | joiner |
| `user_joined` | `{user_id, username, avatar_index}` | everyone **except** the new member |
| `user_left` | `{user_id, username}` | everyone except the departed |
| `sync_playback` | the host's `playback_action` + `server_time` | everyone, **including the host** |
| `buffer_wait` | `{track_id, waiting_for:[usernames]}` | everyone |
| `buffer_complete` | `{track_id}` | everyone |
| `sync_state` | `{current_track (always present), is_playing, position, last_update, queue?, volume?}` | requester |
| `reconnected` | `{room_code, user_id, state, is_host}` | reconnecting member |
| `user_disconnected` / `user_reconnected` | `{user_id, username}` | the room |
| `host_changed` | `{new_host_id, new_host_name}` | everyone (host transfer/leave/kick expiry) |
| `kicked` | `{reason}` | the removed member, then its socket closes |
| `suggestion_received` | `{suggestion_id, from_user_id, from_username, track_info}` | host |
| `suggestion_approved` / `suggestion_rejected` | `{suggestion_id, track_info?, reason?}` | the suggester |
| `chat` | `{user_id, username, message, timestamp, reply_to?}` | everyone **including the sender** (the client de-dupes its own echo) |
| `pong` | `{server_time}` | pinger (payload ignored by ArchiveTune) |
| `error` | `{code, message}` | the offender |

Error codes the ArchiveTune client reacts to: `invalid_message`,
`session_not_found` / `session_expired` / `invalid_session` (transparent
rejoin), `room_not_found` / `room_closed` (clear the persisted session). This
server also uses `not_host`, `not_in_room`, `room_full`, `rate_limited`,
`invalid_payload`, `user_not_found`, `suggestion_not_found` — unknown codes
are logged by the client and otherwise ignored.

### How devices stay in time

Same anchor rule BitChord runs on, expressed in the vivi protocol:

- the server holds `(positionMs, lastUpdateMs, isPlaying)` and answers
  `request_sync` with the position advanced to *now*;
- every `sync_playback` echo carries `server_time` (server epoch ms), and the
  guest adds `now - server_time` (clamped) to the position on `play` — a
  frame that lands late still lands the guest in the right place;
- the host's own client re-states `play` with its position every 10 s
  (`ListenTogetherManager.startHeartbeat`), so drift is bounded without any
  server-side state pushes;
- buffering is a barrier: `change_track` → guests buffer → `buffer_ready` →
  `buffer_wait` naming the stragglers → `buffer_complete` when every
  connected guest is ready.

## Lifecycle

- **Disconnect grace** (`LT_DISCONNECT_GRACE_MS`, 90 s): a dropped socket
  broadcasts `user_disconnected` and keeps its seat; `reconnect` reclaims it
  (`user_reconnected`). After the grace the seat is released (`user_left`)
  and its session token invalidated.
- **Host authority**: the host leaving (or being timed out) passes the crown
  to the longest-standing connected member via `host_changed`, and
  outstanding `join_request`s are re-delivered to the new host.
- **Empty rooms** live for `LT_EMPTY_ROOM_TTL_MS` (2 min), rooms die at
  `LT_ROOM_MAX_AGE_MS` (12 h), unanswered join requests expire after
  `LT_PENDING_JOIN_TTL_MS` (2 min).
- All rooms are in the memory of the one process — run **one instance**
  (scaling out means a pub/sub swap behind `party.Store`, nothing above it
  moves).

## Environment

| Variable | Default | |
|---|---|---|
| `PORT` | `8080` | HTTP/WS listen port |
| `LT_MAX_MEMBERS` | `8` | approved members per room |
| `LT_MAX_ROOMS` | `100` | concurrent rooms |
| `LT_DISCONNECT_GRACE_MS` | `90000` | how long a lost socket keeps its seat |
| `LT_EMPTY_ROOM_TTL_MS` | `120000` | empty-room lifetime |
| `LT_ROOM_MAX_AGE_MS` | `43200000` | hard room age ceiling (12 h) |
| `LT_PENDING_JOIN_TTL_MS` | `120000` | unanswered join request lifetime |
| `LT_SWEEP_INTERVAL_MS` | `5000` | reaper cadence |
| `LT_MAX_MESSAGE_BYTES` | `262144` | max inbound WS message (custom-avatar chat broadcasts reach ~128 KiB) |
| `LT_IDLE_TIMEOUT_MS` | `150000` | close a socket that sends nothing (client pings at 25 s / 30 s) |
| `LT_WRITE_TIMEOUT_MS` | `10000` | per outbound frame |
| `LT_FRAME_RATE_PER_SECOND` | `30` | decoded messages per member |
| `LT_CREATE_RATE_PER_MINUTE` | `5` | new rooms per client IP |
| `LT_RATE_LIMIT_MAX_ENTRIES` | `10000` | limiter bound |
| `LT_USERNAME_MAX` | `64` | display-name bound |
| `LT_CHAT_MAX` | `200000` | chat message bound |
| `LT_ALLOWED_ORIGINS` | *(none)* | browser Origin allowlist for WS + REST; the Android app sends no Origin and is always accepted |

## Deploying

`deploy/` carries the files; from a copy of this directory on a fresh box:

**systemd** (`deploy/listentogether-server.service`) — run as a dedicated
user on `:8080`:

```sh
sudo cp deploy/listentogether-server.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now listentogether-server
journalctl -u listentogether-server -f
```

**TLS / reverse proxy** (`deploy/Caddyfile`) — Caddy in front for HTTPS and
WebSocket pass-through:

```
listen.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Check it with `curl https://listen.example.com/healthz`, then point the app at
`wss://listen.example.com`. nginx works the same way as long as
`proxy_http_version 1.1`, `Upgrade` and `Connection` headers are forwarded
(the usual `map $http_upgrade` dance).

## Layout

```
clock/clock.go        the one clock, monotonic so anchors never jump
codes/codes.go        six-character room codes, charitably normalised
config/config.go      environment knobs
hub/hub.go            who holds a socket, and how a frame reaches everyone
party/party.go        room, members, playback anchor, queue, sessions — in-memory truth
protocol/protocol.go  vivi-music wire envelope + payload shapes
ws/ws.go              minimal RFC 6455 WebSocket (handshake, frames, ping/pong) — stdlib only
main.go               REST routes, upgrade handler, reaper, IP limiter
session.go            per-socket session: read loop + message dispatch
main_test.go          end-to-end tests using the client's exact JSON shapes
deploy/               systemd unit + Caddyfile
```

## Attribution & licence

- Server structure and sync/lifecycle design ported from **BitChord** —
  Listen Together backend, GPL-3.0:
  https://github.com/kushagrasinghx/BitChord
  (its `clock`, `codes`, `config`, `hub`, `party` and deploy layouts are the
  ancestors of the same packages here, adapted to the vivi-music protocol and
  to a stdlib-only WebSocket layer).
- Wire protocol compatible with **vivi-music's** Listen Together protocol as
  implemented by ArchiveTune (`listentogether/Protocol.kt`,
  `MessageCodec.kt`, `ListenTogetherClient.kt`, GPL-3.0) and the public
  vivi-music / Metrolist community servers.
- This server: GPL-3.0-or-later, like the rest of ArchiveTune.
