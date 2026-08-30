#!/usr/bin/env python3
"""Carefully strip only the Paxsenix* helpers from LyricsSettings.kt.

The script:
1. Restores LyricsSettings.kt to git HEAD (assumed done by caller).
2. Re-applies the small "show dialog block + rememberPreference calls" surgical
   edits using string replace (NOT a regex block-trim).
3. Removes ONLY these specific top-level declarations (and their @Composable
   annotations), preserving `displayName()` and `LyricsProviderOrderDialog`
   that interleave with them:
   - `internal enum class PaxsenixServerStatus`
   - `internal fun successRateToStatus`
   - `internal fun formatUptimeSeconds`
   - `internal fun PaxsenixStatsDialog`
   - `internal fun PaxsenixStatsContent`
   - `internal fun PaxsenixStatusBar`
   - `internal fun PaxsenixProviderRow`

Also drops imports that were only used by the removed helpers (PaxsenixStats,
ProviderStats, PaxsenixStatsState, EnablePaxsenix*Keys, EnableSimpMusicLyricsKey).
"""
import re
import sys
from pathlib import Path

PATH = Path("/home/z/my-project/ArchiveTune/app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/settings/LyricsSettings.kt")
src = PATH.read_text()

# ─────────────────────── 1. Import line drops ──────────────────────────────
IMPORT_DROPS = [
    "import moe.rukamori.archivetune.constants.EnablePaxsenixAppleMusicLyricsKey\n",
    "import moe.rukamori.archivetune.constants.EnablePaxsenixLyricsKey\n",
    "import moe.rukamori.archivetune.constants.EnablePaxsenixMusixmatchLyricsKey\n",
    "import moe.rukamori.archivetune.constants.EnablePaxsenixNeteaseLyricsKey\n",
    "import moe.rukamori.archivetune.constants.EnablePaxsenixSpotifyLyricsKey\n",
    "import moe.rukamori.archivetune.constants.EnablePaxsenixYouTubeLyricsKey\n",
    "import moe.rukamori.archivetune.constants.EnableSimpMusicLyricsKey\n",
    "import moe.rukamori.archivetune.paxsenix.models.PaxsenixStats\n",
    "import moe.rukamori.archivetune.paxsenix.models.ProviderStats\n",
    "import moe.rukamori.archivetune.viewmodels.PaxsenixStatsState\n",
]
for line in IMPORT_DROPS:
    if line not in src:
        print(f"WARN: import not found: {line.strip()!r}", file=sys.stderr)
    src = src.replace(line, "")

# ─────────────────── 2. The show-PaxsenixStatsDialog block ─────────────────
PAXSENIK_DIALOG_BLOCK = """    var showPaxsenixStatsDialog by remember { mutableStateOf(false) }

    if (showPaxsenixStatsDialog) {
        val statsState by viewModel.paxsenixStatsState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            viewModel.fetchPaxsenixStats()
        }

        PaxsenixStatsDialog(
            state = statsState,
            onDismiss = { showPaxsenixStatsDialog = false },
            onRetry = { viewModel.fetchPaxsenixStats() },
        )
    }
"""
REPLACEMENT = """    // PaxsenixStatsDialog and its state plumbing removed (2026-08-30) along
    // with the PaxsenixLyrics backend that the dialog queried. The
    // fetchPaxsenixStats / paxsenixStatsState surface has been removed from
    // ContentSettingsViewModel, and the PaxsenixStatsContent /
    // PaxsenixStatusBar / PaxsenixProviderRow / PaxsenixServerStatus /
    // successRateToStatus helpers below have been deleted too.

"""
if PAXSENIK_DIALOG_BLOCK not in src:
    print("ERROR: PaxsenixStatsDialog block not found verbatim", file=sys.stderr)
    sys.exit(1)
src = src.replace(PAXSENIK_DIALOG_BLOCK, REPLACEMENT)

# ─────────────── 3. The enablePaxsenix* rememberPreference calls ───────────
PAXSENIK_PREFS = """    val (enablePaxsenixLyrics, onEnablePaxsenixLyricsChange) = rememberPreference(key = EnablePaxsenixLyricsKey, defaultValue = true)
    val (enablePaxsenixAppleMusicLyrics, onEnablePaxsenixAppleMusicLyricsChange) =
        rememberPreference(
            key = EnablePaxsenixAppleMusicLyricsKey,
            defaultValue = true,
        )
    val (enablePaxsenixNeteaseLyrics, onEnablePaxsenixNeteaseLyricsChange) =
        rememberPreference(
            key = EnablePaxsenixNeteaseLyricsKey,
            defaultValue = true,
        )
    val (enablePaxsenixSpotifyLyrics, onEnablePaxsenixSpotifyLyricsChange) =
        rememberPreference(
            key = EnablePaxsenixSpotifyLyricsKey,
            defaultValue = true,
        )
    val (enablePaxsenixMusixmatchLyrics, onEnablePaxsenixMusixmatchLyricsChange) =
        rememberPreference(
            key = EnablePaxsenixMusixmatchLyricsKey,
            defaultValue = true,
        )
    val (enablePaxsenixYouTubeLyrics, onEnablePaxsenixYouTubeLyricsChange) =
        rememberPreference(
            key = EnablePaxsenixYouTubeLyricsKey,
            defaultValue = true,
        )
"""
# Also widen the SimpMusic comment block to mention Paxsenix removal.
SIMPMUSIC_COMMENT = """    val (enableSimpMusicLyrics, onEnableSimpMusicLyricsChange) = rememberPreference(key = EnableSimpMusicLyricsKey, defaultValue = true)
    // Megalobiz lyrics provider removed per user request (2026-08-28):
    // "Remove megalobiz lyrics provider". The MegalobizLyricsProvider
    // file was deleted; the PreferredLyricsProvider.MEGALOBIZ enum value
    // and the DefaultLyricsProviderOrder entry are also gone."""
SIMPMUSIC_REPLACEMENT = """    // SimpMusic / BiniLyrics lyrics providers removed per user request
    // (2026-08-30): "Remove simpmusic and binilyrics lyrics provider and
    // their entire code too". The provider files, settings toggles, enum
    // entries, gradle module includes and the underlying :lyrics:simpmusic
    // / :lyrics:paxsenix gradle modules have all been deleted.
    //
    // The Paxsenix* enable keys / rememberPreference calls below were also
    // removed because the PaxsenixLyrics backend was the only consumer; the
    // keys remain defined in PreferenceKeys.kt as no-ops for source compat.
    // Megalobiz lyrics provider removed per user request (2026-08-28):
    // "Remove megalobiz lyrics provider". The MegalobizLyricsProvider
    // file was deleted; the PreferredLyricsProvider.MEGALOBIZ enum value
    // and the DefaultLyricsProviderOrder entry are also gone."""

if SIMPMUSIC_COMMENT not in src:
    print("ERROR: SimpMusic comment block not found verbatim", file=sys.stderr)
    sys.exit(1)
src = src.replace(SIMPMUSIC_COMMENT, SIMPMUSIC_REPLACEMENT)

if PAXSENIK_PREFS not in src:
    print("ERROR: enablePaxsenix* rememberPreference block not found verbatim", file=sys.stderr)
    sys.exit(1)
src = src.replace(PAXSENIK_PREFS, "")

# ─────────────────── 4. Remove the displayName SIMPMUSIC/BINI_LYRICS ─────────
DISPLAY_PAXSENIK = """        PreferredLyricsProvider.SIMPMUSIC -> "SimpMusic"
        PreferredLyricsProvider.BINI_LYRICS -> "BiniLyrics"
"""
DISPLAY_REPLACEMENT = """        // SIMPMUSIC and BINI_LYRICS cases removed per user request (2026-08-30).
"""
if DISPLAY_PAXSENIK not in src:
    print("ERROR: displayName SIMPMUSIC/BINI_LYRICS branches not found verbatim", file=sys.stderr)
    sys.exit(1)
src = src.replace(DISPLAY_PAXSENIK, DISPLAY_REPLACEMENT)

# ─────────── 5. Remove each top-level helper one at a time ──────────────────
# Pattern: optional @Composable line, then `internal ...` declaration,
# followed by balanced braces until the matching closing `}` at column 0.

def strip_top_level_decl(src: str, signature_prefix: str) -> str:
    """Remove a top-level `internal` declaration with its @Composable
    annotation line if present. The declaration must end with a `}` at
    column 0 followed by a blank line.
    """
    lines = src.split("\n")
    n = len(lines)
    # Find the line where signature_prefix starts.
    sig_idx = None
    for i, ln in enumerate(lines):
        if ln.startswith(signature_prefix):
            sig_idx = i
            break
    if sig_idx is None:
        print(f"  (skip) signature not found: {signature_prefix!r}", file=sys.stderr)
        return src
    # Walk back to include a single preceding @Composable annotation if present.
    start = sig_idx
    while start > 0 and lines[start - 1].strip() == "@Composable":
        start -= 1
        break
    # Find the matching closing brace at column 0.
    depth = 0
    end = sig_idx
    for i in range(sig_idx, n):
        ln = lines[i]
        # Count braces, ignoring strings and line comments.
        # Strip line comments.
        stripped_ln = re.sub(r'//.*$', '', ln)
        # Strip string literals.
        clean = re.sub(r'"(?:\\.|[^"\\])*"', '""', stripped_ln)
        depth += clean.count("{") - clean.count("}")
        if depth == 0 and "{" in clean:
            # The first line that opens AND closes the brace group, with
            # depth returning to 0, ends the declaration.
            end = i
            break
        if depth == 0 and i > sig_idx and ln.strip() == "":
            # Bare line after the declaration started — only happens for
            # one-liner enum declarations. Skip.
            continue
    # Move end forward to the first line whose content is exactly "}" at
    # column 0 — this is the declaration's terminator.
    while end < n and lines[end].strip() != "}":
        end += 1
    # Consume any trailing blank line(s) immediately after.
    after = end + 1
    while after < n and lines[after].strip() == "":
        after += 1
    # Drop one trailing blank line if there's another blank line before the
    # next declaration (preserves single-blank-line separation).
    new_lines = lines[:start] + lines[after:]
    return "\n".join(new_lines)


for prefix in [
    "internal enum class PaxsenixServerStatus",
    "internal fun successRateToStatus",
    "internal fun formatUptimeSeconds",
    "internal fun PaxsenixStatsDialog",
    "internal fun PaxsenixStatsContent",
    "internal fun PaxsenixStatusBar",
    "internal fun PaxsenixProviderRow",
]:
    before = len(src.split("\n"))
    src = strip_top_level_decl(src, prefix)
    after = len(src.split("\n"))
    print(f"  stripped {prefix!r}: {before} -> {after} lines")

# ─────────────────── 6. Write back ─────────────────────────────────────────
# Ensure single trailing newline.
while src.endswith("\n\n"):
    src = src[:-1]
if not src.endswith("\n"):
    src += "\n"
PATH.write_text(src)
print(f"Final file length: {len(src.split(chr(10)))} lines")
