/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.lottie

import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import moe.rukamori.archivetune.R

/**
 * ArchiveTune's single reusable Lottie integration layer.
 *
 * Every screen that wants a Lottie animation goes through
 * [ArchiveTuneLottieAnimation] (one-shot, event-driven) or
 * [ArchiveTuneLottieLoop] (continuous, decorative) so no call site duplicates
 * composition loading/caching, recoloring or progress handling.
 *
 * Design rules (per the Lottie integration spec):
 *  - Animations are event-driven decorations. Application state (favorite
 *    state, download state, empty state) always remains the source of truth;
 *    Lottie only renders the visual transition.
 *  - Compositions are parsed once per resource via [rememberLottieComposition]
 *    (backed by Lottie's internal composition cache) — never re-parsed on
 *    recomposition.
 *  - Assets are small local files under `res/raw` (8-9 KB each) — no remote
 *    downloads.
 *  - All baked colors are white and are recolored through Lottie dynamic
 *    properties to the caller-provided theme color so the animations match
 *    ArchiveTune's Material theming in both light and dark mode.
 */
@Composable
fun rememberArchiveTuneLottieComposition(
    @RawRes rawRes: Int,
): LottieComposition? =
    // rememberLottieComposition keeps an internal cache keyed by the spec, so
    // recomposition (and re-entering a screen) reuses the parsed composition
    // instead of re-parsing the JSON.
    rememberLottieComposition(
        LottieCompositionSpec.RawRes(rawRes),
    )

/**
 * Shared dynamic-property builder: recolors every shape group named "Color"
 * (the naming convention of the bundled assets) to [tintColor]. The "**"
 * keypath prefix/suffix matches the group at any layer depth.
 */
@Composable
private fun archiveTuneTintColorProperties(tintColor: Color): LottieDynamicProperties =
    rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            LottieProperty.COLOR,
            tintColor.toArgb(),
            "**",
            "Color",
            "**",
        ),
        rememberLottieDynamicProperty(
            LottieProperty.STROKE_COLOR,
            tintColor.toArgb(),
            "**",
            "Color",
            "**",
        ),
    )

private typealias LottieDynamicProperties = com.airbnb.lottie.compose.LottieDynamicProperties

/**
 * One-shot Lottie animation driven by [trigger]. Every time `trigger` changes
 * to a new non-null value the animation restarts from frame 0 and plays once.
 *
 * @param trigger monotonically changing "event id" (e.g. an incrementing
 *   counter or the timestamp of the event). `null` keeps the animation idle.
 * @param tintColor theme color applied to every "Color" shape group in the
 *   composition (the assets are authored white).
 */
@Composable
fun ArchiveTuneLottieAnimation(
    @RawRes rawRes: Int,
    trigger: Any?,
    modifier: Modifier = Modifier,
    tintColor: Color? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(rawRes),
    )
    val parsed = composition ?: return // nothing parsed yet — render nothing

    val dynamicProperties = if (tintColor != null) archiveTuneTintColorProperties(tintColor) else null

    // Re-keying on the trigger resets the animation state, so each new event
    // plays from frame 0 exactly once and never loops.
    key(trigger) {
        val progress by animateLottieCompositionAsState(
            composition = parsed,
            iterations = 1,
            restartOnPlay = true,
            speed = 1f,
            isPlaying = trigger != null,
        )
        LottieAnimation(
            composition = parsed,
            progress = { progress },
            modifier = modifier,
            contentScale = contentScale,
            dynamicProperties = dynamicProperties,
        )
    }
}

/**
 * Continuously looping decorative Lottie animation (e.g. empty states).
 * Rendering stops while [isPlaying] is false so hidden loops cost nothing.
 */
@Composable
fun ArchiveTuneLottieLoop(
    @RawRes rawRes: Int,
    modifier: Modifier = Modifier,
    tintColor: Color? = null,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(rawRes),
    )
    val parsed = composition ?: return

    val dynamicProperties = if (tintColor != null) archiveTuneTintColorProperties(tintColor) else null

    val progress by animateLottieCompositionAsState(
        composition = parsed,
        iterations = LottieConstants.IterateForever,
        isPlaying = isPlaying,
        speed = 1f,
    )

    LottieAnimation(
        composition = parsed,
        progress = { progress },
        modifier = modifier,
        contentScale = contentScale,
        dynamicProperties = dynamicProperties,
    )
}

/** Raw resource ids of the bundled ArchiveTune animations. */
object ArchiveTuneLottie {
    const val LikeRes: Int = R.raw.lottie_like
    const val DownloadCompleteRes: Int = R.raw.lottie_download_complete
    const val EmptyStateRes: Int = R.raw.lottie_empty_state
}
