#!/usr/bin/env python3
"""Reconstruct worklog.md after the sandbox reset wiped the uncommitted tail.

The git-committed worklog at daemon commit 349d5b413 holds lines 1-883
(through Task 21). The uncommitted on-disk tail (Task 22's entry, lines
884-962) was recovered from this session's Read-tool output and is
embedded below verbatim. The current on-disk file holds only the Task 23
entry; this script reassembles: base + Task 22 tail + Task 23 entry.
"""

import subprocess

BASE_SHA = "349d5b413"

TASK22_TAIL = """
---
Task ID: 22
Agent: main (Super Z)
Task: ArchiveTune — 6-item user report: (1) YT downloads visible but export skipped "legacy WebM/Opus"; (2) download source priority change re-downloads from the previous top cached provider (+ FLAC export extremely slow); (3) abrupt video controls overlay animation; (4) TikTok double pause icon near video's upper edge + post-seek "video never loads, audio keeps going"; (5) audio/video loading desync at video start in all styles; (6) per-source download state in overflow menus, per-source offline/export entries, no re-download when returning to a downloaded source

Work Log:
- Root-caused (1)+(2): the download resolver served YouTube downloads from the
  PLAYBACK cache (plain mediaId key) — the player writes whatever itag it
  picked there (opus/webm), so YT downloads stored unexportable opus bytes;
  and prewarm's "already downloaded" probe treated plain playback spans as a
  download, so re-downloads reused the previous provider's bytes.
- Root-caused (4): TikTokPausedOverlay centered in the square ARTWORK slot
  while the letterboxed 16:9 video centers in the full page — the icon
  floated ~65dp above the video center ("upper edge").
- Root-caused (5): (a) video ExoPlayer's playWhenReady bypassed the main
  player's buffering state (video ran silently ahead); (b) beginAudioHold
  only paused an already-playing main — a main that turns READY mid video
  load raced ahead of a black video surface.
- Implemented per-source download identities (commit 1cee903e8, 14 files):
  1) DownloadSourceConfig: added "ytm:" prefix + cacheKeyPrefix/downloadCacheKey/
     downloadIdToSongId/songIdToDownloadIds/downloadSourceForCacheKey helpers;
     CACHE_KEY_PREFIXES now includes ytm:.
  2) DownloadUtil: DownloadTarget (per-song override first, else top of the
     order) drives currentSourceDownloadTarget/Ids/clearCurrentTargetCacheSpans;
     menus issue DownloadRequests whose id+customCacheKey carry the source
     identity; resolver rewritten — no playback-cache fallback, target-key
     short-circuit, direct per-source resolution, YouTube path keyed "ytm:"
     with preferM4A; prewarm probes the DOWNLOAD cache (not playerCache
     playback spans) and fetches YT streams into "ytm:"; failure sweep strips
     prefixes; onDownloadRemoved removes ONLY that source's key (+ytm twin for
     legacy plain); getDownload() is source-aware (target first, any completed
     source second) for row icons.
  3) Menus (SongMenu ×2, YouTubeSongMenu ×2, PlayerMenu): per-source
     Download/Remove state (legacy plain-id fallback), remove by actual entry
     id, targeted span clearing that preserves other sources' copies.
  4) ExportDownloadedSongsScreen: one labeled row per (song, source) key —
     the same song from Qobuz + YouTube shows 2 entries; export resolves each
     row's own key directly; per-source delete; 1 MiB buffered copies (FLAC
     export speed); distinct temp names per row.
  5) ManageDownloadsUseCase: raw-id normalization for DB/playlist/album
     matching, per-source song entries with "Artist · Source" labels,
     collections act on all of a song's source entries.
  6) MusicService: playback candidate keys include "ytm:" (+all prefixes via
     config) for offline playback; offline-recovery + source-switch sweeps
     cover ytm:.
  7) MediaLibrarySessionCallback: offline browse/search normalize
     source-scoped download ids.
- Video fixes (same commit): TikTok + inline tap-to-show overlays now
  fade/scale (AnimatedVisibility ~220ms, auto-hide 3.5s while playing, kept
  while paused); TikTok paused indicator anchored to the video's
  aspectRatio box; video freezes while the MAIN player buffers
  (isMainAudioBuffering) and the main is held when it turns READY mid
  video-load; stuck-buffering watchdog 20s→8s with re-anchor+re-prepare,
  bounded at 3 attempts → artwork fallback; frozen-renderer kicks escalate
  to hard re-anchor+re-prepare.
- First push (72d6bec1d): check gate GREEN in ~2.5 min; build/nightly jobs
  failed on a duplicate string resource (download_source_jiosaavn already in
  fork_strings.xml) — removed mine and re-pushed as 1cee903e8.
- CI polling in progress for 1cee903e8 at worklog-write time.

Stage Summary:
- YouTube downloads now fetch preferM4A streams into their own "ytm:" slot
  and export as .m4a; playback-cache opus bytes can never masquerade as a
  download again.
- Changing the download priority (or a song's pinned source) downloads the
  NEW top source; other sources' completed copies coexist (one labeled
  offline/export entry each); the overflow menu flips Download/Remove per
  current source with legacy fallback and never re-downloads a source whose
  copy already exists.
- Export copies use 1 MiB buffers (large FLACs no longer crawl); per-row
  temp names prevent same-song cross-contamination.
- Video controls animate in/out and auto-hide; the TikTok double pause icon
  is gone; post-seek/buffering stalls recover in seconds with a bounded
  artwork fallback; audio and video start and recover together in all styles.
- Next: confirm all 12 check-runs green on 1cee903e8, then continue pending
  batch items (TDLib→mtcute assessment in Task 20; per-source download
  migration UX polish if the user reports legacy duplicates in the export
  page — legacy plain rows are deletable per-row from that page).
"""


def main() -> int:
    base = subprocess.run(
        ["git", "show", f"{BASE_SHA}:worklog.md"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if not base.endswith("\n"):
        base += "\n"

    with open("worklog.md", "r", encoding="utf-8") as fh:
        task23 = fh.read()  # starts with "---\nTask ID: 23"

    reconstructed = base + TASK22_TAIL
    if not reconstructed.endswith("\n"):
        reconstructed += "\n"
    reconstructed += "\n" + task23
    if not reconstructed.endswith("\n"):
        reconstructed += "\n"

    with open("worklog.md", "w", encoding="utf-8") as fh:
        fh.write(reconstructed)

    print(f"reconstructed worklog.md: {len(reconstructed.splitlines())} lines")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
