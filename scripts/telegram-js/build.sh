#!/usr/bin/env bash
# ArchiveTune (2026) — builds the mtcute host bundle for the Android app.
#
# Concatenates:
#   1. license header
#   2. host/banner.js  (QuickJS environment shims over the Kotlin bridge)
#   3. esbuild IIFE bundle of host/main.ts (mtcute glue)
#
# Output: app/src/main/assets/telegram/mtcute_host.js
#
# The bundle is committed to the repo so CI never needs node/esbuild.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT_DIR="$ROOT/app/src/main/assets/telegram"
OUT="$OUT_DIR/mtcute_host.js"

mkdir -p "$OUT_DIR"

cd "$HERE"

npx esbuild host/main.ts \
  --bundle \
  --format=iife \
  --target=es2020 \
  --platform=neutral \
  --minify \
  --log-level=warning \
  --outfile=build/bundle.js

cat > "$OUT" <<'HDR'
/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * mtcute host bundle — runs the mtcute (MTProto) TypeScript client inside a
 * QuickJS runtime bridged to Kotlin (timers, WebSocket, crypto, storage).
 *
 * Built by scripts/telegram-js/build.sh; do not edit by hand.
 *
 * THIRD-PARTY NOTICES (bundled software):
 *   - mtcute v0.32.1 — MIT License — https://github.com/mtcute/mtcute
 *   - @fuman/io, @fuman/net, @fuman-utils v0.0.21 — MIT License —
 *       https://github.com/teidesu/fuman
 *   - long v5.x — Apache License 2.0 — https://github.com/dcodeIO/long.js
 *   - @mtcute/tl-runtime, @mtcute/file-id — MIT License
 * MIT/Apache-2.0 licensed code is bundled unmodified in object form and
 * remains under its original license.
 */
HDR

cat host/banner.js >> "$OUT"
echo "" >> "$OUT"
cat build/bundle.js >> "$OUT"

echo "--- bundle written: $OUT ---"
wc -c "$OUT"
