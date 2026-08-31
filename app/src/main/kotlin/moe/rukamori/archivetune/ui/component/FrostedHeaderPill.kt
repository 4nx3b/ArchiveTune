/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.ProvideTextStyle
import androidx.compose.ui.unit.dp

/**
 * CompositionLocal that signals to descendant components (notably the custom
 * [IconButton]) that they are being rendered inside a `plain = true`
 * [FrostedHeaderPill]. When `true`, the custom [IconButton] overrides its
 * default `IconButtonColors` to use a transparent `containerColor` so no
 * circular background is drawn behind the icon — matching the user's
 * explicit request (2026-08-29): "There's still frosted header pills in
 * settings and it's submenus. Remove it." Even with `FrostedHeaderPill(
 * plain = true)`, the wrapping Row alone didn't remove the visual pill
 * appearance because the inner [IconButton] component still rendered a
 * `CircleShape`-clipped `containerColor` background, which read as a
 * circular "pill" behind the back arrow. This CompositionLocal lets the
 * IconButton self-detect the plain-header context and switch to a fully
 * transparent container without requiring every call site to explicitly
 * pass `colors = IconButtonDefaults.iconButtonColors(containerColor =
 * Color.Transparent)`.
 */
val LocalPlainHeaderPill = compositionLocalOf { false }

/**
 * A frosted-glass-looking pill that wraps header content (title text, icon buttons) so the
 * header can be transparent while the content inside it stays legible against any background.
 *
 * The pill renders as a semi-transparent `surfaceContainer` surface. Earlier iterations tried to
 * composite a real backdrop blur over [LocalNavigationBarBackdrop], but the pill lives
 * INSIDE the NavHost content that is recorded into that same backdrop layer every frame
 * (`drawWithContent` in `MainActivity`), so drawing `backdrop.layer` from inside the pill is
 * re-entrant and crashes the app — both the S+ `drawLayer` path and the pre-S bitmap capture
 * read a layer that is currently being recorded. Degrading to a plain surface is the same
 * fallback those paths already used when no backdrop was available, and matches how the pill
 * behaves in rail layouts where the backdrop is null.
 *
 * The fallback deliberately uses a dense frosted material rather than a nearly transparent
 * surface. Settings pages cannot safely sample the app-wide backdrop from inside the NavHost,
 * but their controls must still read as a visible glass surface instead of an empty pill.
 *
 * **Liquid glass mode:** Pass a non-null [backdrop] (typically created via [rememberBackdrop]
 * and applied to a sibling `LazyColumn` via [Modifier.layerBackdrop]) to switch the pill to
 * real kyant `drawBackdrop` rendering (vibrancy + blur + lens). The pill MUST be a sibling
 * of the composable carrying `layerBackdrop` — nesting it inside the source creates a
 * render-feedback loop that crashes the RuntimeShader. When [backdrop] is `null` (the
 * default), the pill degrades to the translucent `surfaceContainer` surface described above.
 *
 * **Plain mode (Settings + submenus):** Pass [plain] = `true` to skip the Surface / clip /
 * border entirely and just render the content in a `Row` with the same padding as the
 * pill path. The user explicitly requested "remove all the liquid glass from settings
 * and its submenus Page" — so Settings pages render a plain Material3 TopAppBar look
 * (just the back button + title inline) without any frosted pill chrome. The `plain`
 * flag is opt-in so that other callers (History, Library chrome, Apple Music-style
 * playlist pills) keep their frosted surface.
 *
 * Usage: wrap the title / actions of a `TopAppBar` (or any header) in this pill. The
 * outer `TopAppBar` should have `containerColor = Color.Transparent`.
 *
 * @param modifier Modifier for the pill's outer layout.
 * @param backdrop Optional kyant `LayerBackdrop` to sample for real liquid glass.
 *                 Pass `null` (default) to use the translucent surface fallback.
 * @param plain When `true`, skip the Surface/clip/border entirely and render the
 *              content in a plain `Row` with the same internal padding. Use this on
 *              Settings screens where the user wants zero glass chrome.
 * @param content The header content (text, icons) to display inside the pill.
 */
@Composable
fun FrostedHeaderPill(
    modifier: Modifier = Modifier,
    backdrop: PlatformBackdrop? = null,
    plain: Boolean = false,
    content: @Composable () -> Unit,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    if (plain) {
        // Plain path: no Surface, no clip, no border. Just a Row with the
        // same padding as the pill path so the layout doesn't shift when
        // toggling between plain and frosted. This is the "remove all the
        // liquid glass from settings" path the user explicitly requested.
        //
        // We wrap content in a CompositionLocalProvider that signals
        // `LocalPlainHeaderPill = true` so descendant IconButtons (the
        // custom moe.rukamori.archivetune.ui.component.IconButton) override
        // their default `containerColor` to transparent — without this, the
        // IconButton's CircleShape-clipped background would still render
        // as a circular "pill" behind the back arrow, which the user
        // explicitly reported seeing ("still frosted header pills in
        // settings and it's submenus").
        // Provide Material3 titleLarge typography (22sp) to all Text() children
        // in plain mode. Per user report (2026-08-31): "The header in settings
        // page and its submenus are extremely small. Revert it to how they were
        // before." Previously these titles lived in `TopAppBar(title = { Text(...) })`
        // which auto-applies titleLarge typography; the single-pill migration moved
        // the title into the navigationIcon slot's FrostedHeaderPill, losing the
        // implicit typography (default body size ~16sp). This wrap restores
        // titleLarge (22sp) for ALL 42 settings screens/submenus without requiring
        // per-file edits. The IconButton's Icon() is unaffected — icons use their
        // own size, not text style.
        CompositionLocalProvider(LocalPlainHeaderPill provides true) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                Row(
                    modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    content()
                }
            }
        }
    } else if (backdrop != null) {
        // Real liquid glass path: kyant `drawBackdrop` effect stack (vibrancy +
        // blur + lens). The pill samples whatever was recorded into the backdrop
        // by `Modifier.layerBackdrop(backdrop)` applied to a sibling composable
        // (typically the LazyColumn carrying the scrolling content beneath this
        // header). MUST be a sibling — nesting inside the source crashes the
        // RuntimeShader.
        Row(
            modifier =
                modifier
                    .clip(pillShape)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = pillShape,
                        interactive = false,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    } else {
        // Fallback path for NavHost-owned screens such as History/Library
        // chrome. Sampling the app-wide backdrop here would create a render-
        // feedback loop, so use Material's frosted container plus a subtle
        // highlight instead of degrading to an almost-transparent pill.
        val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            modifier = modifier.clip(pillShape),
            shape = pillShape,
            color = baseColor.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.72f)),
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}
