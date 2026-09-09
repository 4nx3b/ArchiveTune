#!/usr/bin/env python3
"""Update PR #214 body without shell mangling."""
import json
import os
import urllib.request

token = os.environ["GITHUB_TOKEN"]

body = """## mtcute swap (TDLib -> MTProto over QuickJS)

### What changed
The Telegram integration no longer uses the TDLib native library (tdlibx td 1.8.56, ~8-10 MB per ABI, runtime-downloaded). It now runs **mtcute 0.32.1** — the TypeScript MTProto client — bundled as a 1.26 MB asset and executed inside the app's embedded QuickJS runtime (quickjs-kt, the same engine the YouTube cipher uses), with everything performance-critical bridged to native Kotlin:

- **Transport**: WebSocket (wss://<dc>.web.telegram.org/apiws) over OkHttp, incl. the required "binary" subprotocol (verified empirically — the endpoint answers 404 without it)
- **Crypto**: javax.crypto (AES-CTR/IGE packet crypto, SHA/HMAC, PBKDF2 for 2FA SRP, gzip, PQ factorization)
- **Storage**: write-through file-backed mtcute session store under filesDir/telegram-js
- **Timers/events**: bridge-driven, with a queued event pump

### Feature parity
Login (phone -> code -> 2FA), channel search/browse/sync, **streaming playback with FLAC seeking** (a spool-file cache reproduces TDLib's downloadOffset/downloadedPrefixSize semantics on mtcute precise download chunks), bot chats incl. inline keyboards + forwarding, full-res avatars, and artwork (TDLib-compatible stripped minithumbnail reconstruction, header byte-exact from td/telegram/PhotoSize.cpp).

### Worth knowing
- **TDLib sessions cannot migrate** — signed-in users sign in once more after updating
- Media ids moved to a stable v2 scheme (telegram://track/v2/<chat>/<msg>/<uniqueFileId>); old v1 ids still decode (chat+message survive)
- The bundle is committed (app/src/main/assets/telegram/mtcute_host.js); rebuild via scripts/telegram-js (npm install; bash build.sh; node test/smoke.js — 14/14 checks)
- APK footprint: the per-ABI native lib is gone; TDLib's runtime download + digest machinery is deleted

### Verification
- Node smoke suite (bare-vm QuickJS simulation): 14/14 — shims, transport URL, reconnection loop through the timer bridge, event pump, error envelopes
- JS<->Kotlin binding contract cross-checked programmatically (25/25 names)
- CI on 0efd06e46: **all 12 check-runs green** — check, build (debug + unit tests + lint), 7 nightly APK variants (incl. 32-bit armeabi), 2 release APKs, create-nightly

(This PR also carries the previous batch: per-source downloads, bounded YouTube download pipeline, liquid-glass popup cost cut, faster song start.)
"""

data = json.dumps({"body": body}).encode()
req = urllib.request.Request(
    "https://api.github.com/repos/4nx3B/ArchiveTune/pulls/214",
    data=data,
    method="PATCH",
    headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"},
)
with urllib.request.urlopen(req) as resp:
    result = json.load(resp)
    print("PR body updated, length:", len(result.get("body", "")))
    print("mergeable_state:", result.get("mergeable_state"))
