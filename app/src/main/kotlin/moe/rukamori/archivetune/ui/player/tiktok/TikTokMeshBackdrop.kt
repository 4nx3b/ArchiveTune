/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — the mesh-gradient backdrop.
 *
 * Ported from the Bitchord player style's BitChordMeshGradient.kt (itself
 * ported from BitChord, https://github.com/kushagrasinghx/BitChord,
 * app/src/main/java/com/music/bitchord/ui/player/MeshGradient.kt) per the
 * user request 2026-09-02: "the artwork blend in TikTok style is still
 * imperfect. Copy how Bitchord player style blends the artwork along with
 * the background and implement it in the TikTok style".
 *
 * Why this replaces the blurred-artwork-copy backdrop: the old backdrop was
 * a second, blurred rendering of the SAME image the hero artwork shows, so
 * the two renderings of the same picture met around the hero's edges — and
 * no alpha ramp could fully hide that "same image meets same image" seam
 * (user reports 2026-09-02: "straight lines / color inconsistency",
 * "artwork blend still imperfect"). The Bitchord approach never shows the
 * artwork twice: the backdrop is a mesh of soft colour blobs sampled from
 * the artwork's PALETTE, and the sharp hero dissolves into that. There is
 * nothing to seam against — the blend is perfect by construction.
 *
 * Adaptations for the TikTok style (kept minimal, documented inline):
 *  - The TikTok page draws its own legibility scrim on top (see
 *    `tiktokScrim` in TikTokSongPage.kt), so this port keeps the Bitchord
 *    parameters verbatim (64dp blur, 1.3x scale, 4 drifting blobs,
 *    ~1.4s colour crossfade on track change) but does NOT double up the
 *    scrim: the page-level scrim replaces it.
 *  - The TikTok page-level top/bottom scrim is lightened to sit over the
 *    tuned, dimmed mesh rather than a raw blurred artwork (see
 *    TIKTOK_SCRIM in TikTokSongPage.kt).
 *
 * Belongs exclusively to the TikTok player style; not shared with any other
 * player style, per the self-containment rule for player styles (2026-09-01).
 */

package moe.rukamori.archivetune.ui.player.tiktok

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val MeshFallbackColors = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

/** The four mesh colours, wrapped so the backdrop can skip recomposition. */
@Immutable
internal data class TikTokMeshPalette(val colors: List<Color>)

/**
 * Fraction of the layout size the mesh layer actually rasterizes at
 * (2026-09-04 "TikTok style lags for the first few seconds" fix).
 *
 * The mesh content is four soft radial-gradient blobs plus a vertical scrim
 * — inherently low-frequency, and it is blurred at 64dp on top of that. So
 * it is rasterized at a QUARTER of the layout size and scaled back up
 * (the same trick as a render-scale): the RenderEffect blur then works on
 * 1/16 of the pixels, and because every primitive in the layer is a smooth
 * gradient, the upscaled result is pixel-for-pixel indistinguishable from
 * the full-resolution render. The blob drift invalidated this blurred
 * full-screen canvas for ~8s after every track change, which was the
 * dominant frame cost of the TikTok player's enter/exit window; at
 * quarter resolution the same animation runs at a fraction of the GPU time.
 */
private const val MESH_RENDER_SCALE = 0.25f

/**
 * The Bitchord backdrop: four luminous colour blobs sampled from the album
 * art, drawn as soft radial gradients and blurred into a mesh. Colour
 * changes on track skip crossfade over ~1.4s instead of snapping.
 *
 * The blobs drift when there is a reason to — the page composing, or
 * [trackKey] changing — and then come to rest, exactly as in the Bitchord
 * style. The settled frame looks the same; the battery cost of an eternal
 * orbit is not paid.
 */
@Composable
internal fun TikTokMeshBackdrop(
    palette: TikTokMeshPalette,
    trackKey: Any? = null,
    reduceAnimation: Boolean = false,
    blurRadius: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    val tuned = (palette.colors.ifEmpty { MeshFallbackColors } + MeshFallbackColors)
        .take(4)
        .map { it.tuned() }

    // Each colour slot crossfades independently when the track (palette)
    // changes, unless "reduce animation" is on, in which case colours snap
    // straight to target.
    val colorSpec: AnimationSpec<Color> = if (reduceAnimation) snap() else tween(1400)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "tiktokMeshColor$index").value
    }
    val baseColor by animateColorAsState(tuned.first().dimmed(), colorSpec, label = "tiktokMeshBase")

    // Read in the draw lambda, not here: an Animatable read during draw
    // invalidates only the drawing, leaving composition out of the loop.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey, reduceAnimation) {
        when {
            reduceAnimation -> phase.snapTo(0f)
            // A full turn's worth of drift, restarted rather than looped
            // with an infinite spec: the blobs' speeds are irrational
            // multiples of each other, so the pattern never repeats.
            else -> phase.animateTo(
                targetValue = phase.value + MESH_DRIFT_RADIANS,
                animationSpec = tween(8_000, easing = FastOutSlowInEasing),
            )
        }
    }

    // Scale up slightly so the blur's clamped edges never show, then blur
    // the whole layer (RenderEffect, API 31+; a no-op below — the radial
    // falloff already reads soft there). Clipped on the way out, and from a
    // layer of its own rather than by setting `clip` on the one below: that
    // one clips what is drawn *into* it, in its own coordinates, and the
    // scale is applied after — so the overhang the scale creates survives
    // it. This has to sit outside the scale to contain it.
    //
    // PERF (2026-09-04): the layer itself renders at [MESH_RENDER_SCALE]
    // (quarter size) and is scaled back up by 1.3/MESH_RENDER_SCALE — the
    // blur radius is scaled down by the same factor so the DISPLAYED blur
    // (blur × display scale) is exactly the 1.3× blurRadius the full-res
    // version showed. The quarter-res canvas is centered in the clipping
    // Box so the 1.3 overscan still radiates from the middle.
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize(MESH_RENDER_SCALE)
                    .graphicsLayer {
                        scaleX = 1.3f / MESH_RENDER_SCALE
                        scaleY = 1.3f / MESH_RENDER_SCALE
                    }
                    .background(baseColor)
                    .blur(blurRadius * MESH_RENDER_SCALE),
        ) {
            val anchors = listOf(
                Offset(0.20f, 0.25f),
                Offset(0.80f, 0.20f),
                Offset(0.75f, 0.80f),
                Offset(0.25f, 0.75f),
            )
            val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
            val drift = phase.value

            animatedColors.forEachIndexed { index, color ->
                val anchor = anchors[index]
                val center = Offset(
                    x = (anchor.x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                    y = (anchor.y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
                )
                val radius = size.maxDimension * 0.62f
                drawCircle(
                    brush = Brush.radialGradient(
                        // 0.78 (was 0.85, the Bitchord value): lets more of the
                        // dimmed base (lightness 0.12) breathe between blobs so
                        // the mesh reads as a colour wash rather than a full
                        // saturation fill — the "too full" half of the report.
                        colors = listOf(color.copy(alpha = 0.78f), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }

            // The Bitchord scrim so the mesh never reads as pure brightness.
            // The TikTok page adds its own (lightened) legibility scrim on top
            // of this layer, so this one stays exactly as gentle as Bitchord's.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.38f),
                    ),
                ),
            )
        }
    }
}

/**
 * Loads the page's artwork with Coil (software bitmap, thumbnail-sized) and
 * pulls a 4-colour palette out of it. Recomputes when [imageUrl] changes.
 */
@Composable
internal fun rememberTikTokArtworkColors(imageUrl: String?): TikTokMeshPalette {
    val context = LocalContext.current
    var palette by remember(imageUrl) { mutableStateOf(TikTokMeshPalette(MeshFallbackColors)) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(128) // palette quality is fine at thumbnail size, and it's fast
            .allowHardware(false) // Palette needs pixel access
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        // Palette.generate() is synchronous pixel work; a 128px bitmap is
        // quick, but it still has no business on the main dispatcher.
        palette = TikTokMeshPalette(withContext(Dispatchers.Default) { paletteOf(bitmap) })
    }
    return palette
}

/**
 * How far the blobs travel in one settle. A shade under half a turn: enough
 * that the backdrop visibly reacts to a track change, short of a full orbit
 * that would land the blobs back where they started.
 */
private const val MESH_DRIFT_RADIANS = (PI * 0.45f).toFloat()

/**
 * Four mesh colours drawn from the artwork.
 *
 * The named swatches — vibrant, muted and friends — are a convenience over
 * the full set, and on dark or desaturated sleeves every vibrant slot comes
 * back null. Topping the rest up from [MeshFallbackColors] is what left
 * those covers sitting under the stock purple. So the whole swatch list is
 * read instead, and any shortfall is derived from the art's own colours
 * rather than borrowed.
 */
private fun paletteOf(bitmap: Bitmap): List<Color> {
    fun swatchesOf(builder: Palette.Builder): List<Color> =
        builder.maximumColorCount(24).generate().swatches
            .sortedByDescending { it.population }
            .map { Color(it.rgb) }

    val found = swatchesOf(Palette.from(bitmap)).ifEmpty {
        // The default filter discards near-black and near-white, which on a
        // monochrome sleeve can be everything there is.
        swatchesOf(Palette.from(bitmap).clearFilters())
    }

    val distinct = found.distinctEnough()
    return when {
        distinct.isEmpty() -> MeshFallbackColors
        distinct.size >= 4 -> distinct.take(4)
        else -> distinct.expandedToFour()
    }
}

/** Drop near-duplicates, so the four blobs don't collapse into one wash. */
private fun List<Color>.distinctEnough(): List<Color> {
    val kept = mutableListOf<Color>()
    forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
    return kept
}

private fun Color.isCloseTo(other: Color): Boolean {
    val a = hsl()
    val b = other.hsl()
    val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
    return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
}

/** Fill the empty slots off the art itself, fanning hue and lightness out. */
private fun List<Color>.expandedToFour(): List<Color> {
    val out = toMutableList()
    var step = 1
    while (out.size < 4) {
        out += this[(out.size - size) % size].shifted(24f * step, 0.12f * step)
        step++
    }
    return out
}

private fun Color.shifted(hue: Float, lightness: Float): Color {
    val hsl = hsl()
    hsl[0] = (hsl[0] + hue) % 360f
    hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.hsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

/**
 * Balance the palette colour for the mesh so the blend reads rich but never
 * garish (user report 2026-09-03: "Sometimes the artwork blend color is too
 * vibrant or too full which makes it look bad. the blend color's vibrance
 * should be balanced").
 *
 * The Bitchord-original boost (saturation * 1.35, capped at 1.0) overdrives
 * sleeves that already carry saturated colour: everything lands at full
 * neon saturation and the mesh reads as "too vibrant / too full". The
 * balanced tune keeps a mild lift for muted artwork (which genuinely needs
 * it) while TEMPERING already-saturated artwork below maximum — the
 * saturation ceiling is the balance point, not the multiplier:
 *  - muted colour (s=0.30) -> 0.35, still visibly lifted;
 *  - saturated colour (s=0.90) -> capped at 0.78, deliberately dimmed.
 * Lightness is likewise kept inside a slightly narrower band so bright
 * artwork cannot push the blobs toward white glare.
 */
private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.15f).coerceAtMost(0.78f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.56f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}
