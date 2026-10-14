/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FallbackColors =
    listOf(
        Color(0xFF3A1C71),
        Color(0xFFD76D77),
        Color(0xFF2B5876),
        Color(0xFFFFAF7B),
    )

@Immutable
data class MeshPalette internal constructor(val colors: List<Color>) {
    internal val base: Color get() = colors.first().dimmed()

    companion object {
        val Fallback = MeshPalette(FallbackColors.map { it.tuned() })
    }
}

private val paletteCache = object : LinkedHashMap<String, MeshPalette>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MeshPalette>): Boolean = size > 64
}

@Composable
fun rememberMeshPalette(imageUrl: String?): MeshPalette {
    val context = LocalContext.current
    val cached = imageUrl?.let { synchronized(paletteCache) { paletteCache[it] } }
    val state = remember(imageUrl) { mutableStateOf(cached ?: MeshPalette.Fallback) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || cached != null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .size(128)
                .allowHardware(false)
                .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect

        val palette = withContext(Dispatchers.Default) { MeshPalette(paletteOf(bitmap).map { it.tuned() }) }
        synchronized(paletteCache) { paletteCache[imageUrl] = palette }
        state.value = palette
    }
    return state.value
}

private fun paletteOf(bitmap: Bitmap): List<Color> {
    fun swatchesOf(builder: Palette.Builder): List<Color> =
        builder
            .maximumColorCount(24)
            .generate()
            .swatches
            .sortedByDescending { it.population }
            .map { Color(it.rgb) }

    val found =
        swatchesOf(Palette.from(bitmap)).ifEmpty {
            swatchesOf(Palette.from(bitmap).clearFilters())
        }

    val distinct = found.distinctEnough()
    return when {
        distinct.isEmpty() -> FallbackColors
        distinct.size >= 4 -> distinct.take(4)
        else -> distinct.expandedToFour()
    }
}

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

private fun Color.hsl(): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

private fun Color.tuned(): Color {
    val hsl = hsl()
    hsl[1] = (hsl[1] * 1.6f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.22f, 0.68f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = hsl()
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}
