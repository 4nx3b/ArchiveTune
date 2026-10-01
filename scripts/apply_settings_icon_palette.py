#!/usr/bin/env python3
"""
Apply the iOS-style vivid per-row icon palette to SettingsDataBuilders.kt
and wire the signed-in Google account's profile picture into the account row.

- Inserts a `SettingsIconPalette` object (fixed vivid colors — they read on
  both light and dark cards, unlike theme roles which collapse to 3 tones).
- Replaces every `accentColor = MaterialTheme.colorScheme.<role>` inside each
  SettingsItem block with the palette color for that item's key.
- Special-cases the `updates` item's conditional accentColor expression.
- Adds an `accountImageUrl: String? = null` parameter to buildSettingsGroups
  and sets `iconUrl = accountImageUrl` on the account item.
- Drops the MaterialTheme import if nothing else uses it anymore.
"""

import re
import sys

PATH = "app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/settings/SettingsDataBuilders.kt"

PALETTE = [
    # (item key, palette val name, hex)
    ("account", "Account", "0xFF4285F4"),  # Google blue
    ("stats", "Stats", "0xFFEC407A"),  # pink
    ("appearance", "Appearance", "0xFFAB47BC"),  # purple
    ("appearance_extras", "AppearanceExtras", "0xFF7E57C2"),  # deep purple
    ("aod", "Aod", "0xFF3949AB"),  # indigo
    ("navigation_bar", "NavigationBar", "0xFF29B6F6"),  # light blue
    ("playback", "Playback", "0xFFEF5350"),  # red
    ("sources", "Sources", "0xFF26A69A"),  # teal
    ("jiosaavn", "JioSaavn", "0xFFFFA726"),  # orange
    ("amazon", "Amazon", "0xFFFF7043"),  # deep orange
    ("qqmusic", "QqMusic", "0xFF7CB342"),  # light green
    ("deezer", "Deezer", "0xFF9C27B0"),  # purple
    ("lyrics", "Lyrics", "0xFF42A5F5"),  # blue
    ("lyrics_providers", "LyricsProviders", "0xFF26C6DA"),  # cyan
    ("lyrics_romanisation", "LyricsRomanisation", "0xFF66BB6A"),  # green
    ("language_packs", "LanguagePacks", "0xFF8D6E63"),  # brown
    ("content", "Content", "0xFFFFB300"),  # amber
    ("behavior", "Behavior", "0xFF78909C"),  # blue gray
    ("android_auto", "AndroidAuto", "0xFF00ACC1"),  # cyan
    ("integration", "Integration", "0xFF7C4DFF"),  # deep purple A200
    ("ai_integration", "AiIntegration", "0xFFF06292"),  # pink
    ("discord_experimental", "DiscordExperimental", "0xFF5865F2"),  # blurple
    ("tidal", "Tidal", "0xFF00BFA5"),  # teal
    ("qobuz", "Qobuz", "0xFFFF8A65"),  # deep orange 300
    ("telegram", "Telegram", "0xFF29A9EB"),  # Telegram blue
    ("internet", "Internet", "0xFF5C6BC0"),  # indigo 400
    ("po_token", "PoToken", "0xFFFFB300"),  # amber
    ("storage", "Storage", "0xFF78909C"),  # blue gray
    ("downloads", "Downloads", "0xFF66BB6A"),  # green
    ("backup_restore", "BackupRestore", "0xFF8D6E63"),  # brown
    ("developer_options", "DeveloperOptions", "0xFF90A4AE"),  # blue gray 300
    ("updates", "Updates", "0xFF26A69A"),  # teal
    ("about", "About", "0xFF29B6F6"),  # light blue
    ("default_links", "DefaultLinks", "0xFF42A5F5"),  # blue (hidden row)
]

PALETTE_OBJECT = """
/**
 * The settings home's vivid icon-tile palette — one saturated color per row
 * (iOS Settings-style colorful icons), chosen to read equally well on the
 * light and dark grouped cards and to keep neighboring rows distinguishable.
 * The values are FIXED (not theme roles): the old primary/secondary/tertiary
 * rotation collapsed to at most three near-identical tones.
 */
internal object SettingsIconPalette {
""" + "".join(
    f"    val {name} = Color({hex})\n" for _, name, hex in PALETTE
) + "}\n"


def main() -> int:
    with open(PATH, encoding="utf-8") as f:
        src = f.read()

    # 1) Insert the palette object right before the first @Composable marker.
    if "SettingsIconPalette" not in src:
        anchor = "@Composable\nprivate fun SearchResultSwitch("
        if anchor not in src:
            print("ERROR: palette anchor not found", file=sys.stderr)
            return 1
        src = src.replace(anchor, PALETTE_OBJECT + "\n" + anchor, 1)

    # 2) Make sure Color is imported.
    if not re.search(r"^import androidx\.compose\.ui\.graphics\.Color$", src, re.M):
        src = src.replace(
            "import androidx.compose.material3.MaterialTheme\n",
            "import androidx.compose.material3.MaterialTheme\n"
            "import androidx.compose.ui.graphics.Color\n",
            1,
        )

    # 3) Replace each item's accentColor by key.
    replaced = []
    for key, name, _hex in PALETTE:
        # Find the item's key = "..." position, then the next accentColor
        # assignment before the following key = "..." (block boundary).
        key_pat = re.compile(r'key = "' + re.escape(key) + r'"')
        m = key_pat.search(src)
        if not m:
            print(f"WARN: key {key!r} not found", file=sys.stderr)
            continue
        next_key = re.compile(r'key = "')
        following = next_key.search(src, m.end())
        block_end = following.start() if following else len(src)
        block = src[m.start():block_end]

        # Conditional accentColor (the `updates` item).
        cond_pat = re.compile(
            r"accentColor =\s*\n?\s*if \(hasUpdate\) \{\s*\n?\s*"
            r"MaterialTheme\.colorScheme\.tertiary\s*\n?\s*\} else \{\s*\n?\s*"
            r"MaterialTheme\.colorScheme\.primary\s*\n?\s*\},",
        )
        new_block, n = cond_pat.subn(
            f"accentColor = SettingsIconPalette.{name},", block
        )
        if n == 0:
            plain_pat = re.compile(
                r"accentColor = MaterialTheme\.colorScheme\.(?:primary|secondary|tertiary),"
            )
            new_block, n = plain_pat.subn(
                f"accentColor = SettingsIconPalette.{name},", block
            )
        if n == 0:
            print(f"WARN: no accentColor replaced for {key!r}", file=sys.stderr)
            continue
        src = src[: m.start()] + new_block + src[block_end:]
        replaced.append(key)

    # 4) buildSettingsGroups: accept the account avatar URL.
    sig_old = """fun buildSettingsGroups(
    navController: NavController,
    isAndroid12OrLater: Boolean,
    hasUpdate: Boolean,
    context: Context,
): List<SettingsGroup> {"""
    sig_new = """fun buildSettingsGroups(
    navController: NavController,
    isAndroid12OrLater: Boolean,
    hasUpdate: Boolean,
    context: Context,
    accountImageUrl: String? = null,
): List<SettingsGroup> {"""
    if sig_old in src:
        src = src.replace(sig_old, sig_new, 1)
    elif "accountImageUrl: String? = null" not in src:
        print("ERROR: buildSettingsGroups signature not matched", file=sys.stderr)
        return 1

    # 5) Account item carries the avatar URL for the row's icon slot.
    if "iconUrl = accountImageUrl" not in src:
        account_marker = "accentColor = SettingsIconPalette.Account,\n"
        idx = src.find(account_marker)
        if idx < 0:
            print("ERROR: account accentColor marker not found", file=sys.stderr)
            return 1
        insert_at = idx + len(account_marker)
        src = (
            src[:insert_at]
            + "            iconUrl = accountImageUrl,\n"
            + src[insert_at:]
        )

    # 6) Drop the MaterialTheme import if it is now unused.
    if not re.search(r"MaterialTheme\.", re.sub(
            r"^import .*$", "", src, flags=re.M)):
        src = src.replace("import androidx.compose.material3.MaterialTheme\n", "", 1)

    with open(PATH, "w", encoding="utf-8") as f:
        f.write(src)

    print(f"Replaced accentColor for {len(replaced)}/{len(PALETTE)} items: {replaced}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
