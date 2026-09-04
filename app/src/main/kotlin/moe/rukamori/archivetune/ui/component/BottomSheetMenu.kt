/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    internal var dialogContent by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    @OptIn(ExperimentalMaterial3Api::class)
    fun show(content: @Composable ColumnScope.() -> Unit) {
        dialogContent = null
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        isVisible = false
    }

    fun showDialog(content: @Composable () -> Unit) {
        isVisible = false
        dialogContent = content
    }

    fun dismissDialog() {
        dialogContent = null
    }
}

/**
 * ── Frosted sheet surface (2026-09-04, revised) ────────────────────────────
 *
 * The song-overflow sheet's glass is now ON the sheet itself, Apple Music
 * lyrics-overflow style (user request 2026-09-04: "i don't want the background
 * of the popup to be blurred but the popup itself should be blurred"):
 *
 *  * The app content behind the dialog is NOT blurred anymore — the area
 *    around the sheet gets only the dialog's plain dim scrim.
 *
 *  * At open time the app window is captured once via [PixelCopy] (the sheet
 *    opens in its own dialog window, so the capture is taken from the app
 *    window's surface — clean, without the dialog on top). The snapshot is
 *    downscaled and box-blurred on a background dispatcher, then drawn
 *    pinned to screen coordinates inside the sheet's own bounds: wherever
 *    the sheet sits on screen, the glass shows the blurred version of
 *    exactly what is behind it. The sheet base color crossfades from the
 *    current opaque charcoal to a translucent one as the frost arrives, so
 *    there is no see-through flash while the capture is in flight, and a
 *    failed capture simply leaves today's opaque sheet.
 *
 *  * The snapshot approach needs no RenderEffect, no RuntimeShader and no
 *    cross-window layer sampling — it works on every supported device
 *    (minSdk 26 = PixelCopy) and costs nothing per frame: the bitmap is
 *    static, so the draw is a single texture blit even while the sheet
 *    slides (the pin offset is draw-phase, re-read from
 *    onGloballyPositioned every frame of the slide).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = Color.Unspecified,
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    if (state.isVisible) {
        // ── The frost: one-shot snapshot of the app window, blurred ──
        // Only when the caller did not pin an explicit background color.
        val frostEnabled = background.isUnspecified
        val frost = rememberMenuFrostState(visible = state.isVisible && frostEnabled)
        val frostReady = frostEnabled && frost.snapshot != null

        // The sheet base crossfades opaque -> translucent as the frost
        // arrives, and back to opaque if the capture fails or the device
        // can't produce one. Before the frost lands (a frame or two while
        // PixelCopy completes) the sheet looks exactly like today's opaque
        // charcoal — no see-through flash.
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val sheetColor =
            if (background.isUnspecified) {
                if (dark) {
                    animateColorAsState(
                        targetValue = if (frostReady) MenuFrostDarkBase else MenuOpaqueDark,
                        animationSpec = tween(durationMillis = 240),
                        label = "menuSheetDarkBase",
                    ).value
                } else {
                    val opaque = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)
                    val frosted = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
                    animateColorAsState(
                        targetValue = if (frostReady) frosted else opaque,
                        animationSpec = tween(durationMillis = 240),
                        label = "menuSheetLightBase",
                    ).value
                }
            } else {
                background
            }

        // Frost fade-in (the snapshot arrives a frame or two after open).
        val frostAlpha by animateFloatAsState(
            targetValue = if (frostReady) 1f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "menuFrostAlpha",
        )

        ModalBottomSheet(
            onDismissRequest = {
                focusManager.clearFocus()
                state.isVisible = false
            },
            containerColor = sheetColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            scrimColor = Color.Black.copy(alpha = 0.60f),
            shape = RoundedCornerShape(28.dp),
            dragHandle = {
                Box(
                    modifier =
                        Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            },
            modifier =
                modifier
                    .fillMaxHeight()
                    // Floating sheet margins (reference: ~16px sides, visible
                    // gap above the bottom edge).
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    // The frost layer. Runs BEFORE the sheet Surface's own
                    // clip in the chain, so it needs its own identical clip;
                    // it draws under the (translucent) containerColor, which
                    // doubles as the frost's dark tint.
                    .then(
                        if (frostEnabled) {
                            Modifier
                                .clip(RoundedCornerShape(28.dp))
                                .onGloballyPositioned { coords ->
                                    frost.sheetOffsetInWindow = coords.positionInWindow()
                                }.drawBehind {
                                    val snap = frost.snapshot ?: return@drawBehind
                                    if (frostAlpha <= 0.01f) return@drawBehind
                                    // Pin the snapshot to screen coordinates:
                                    // the sheet region always shows the
                                    // blurred version of whatever is behind
                                    // it, even while it slides up.
                                    translate(
                                        -frost.sheetOffsetInWindow.x,
                                        -frost.sheetOffsetInWindow.y,
                                    ) {
                                        drawImage(
                                            image = snap.image,
                                            srcOffset = IntOffset.Zero,
                                            srcSize = IntSize(snap.image.width, snap.image.height),
                                            dstOffset = IntOffset.Zero,
                                            dstSize = IntSize(snap.fullWidthPx, snap.fullHeightPx),
                                            alpha = frostAlpha,
                                            filterQuality = FilterQuality.Medium,
                                        )
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
        ) {
            // Status bar must NEVER be visible — even while this bottom popup
            // is showing (2026-09-01). The sheet creates its own OS window; when
            // it takes focus, the system re-shows the status bar that the app
            // window had hidden, and the inset change shifts the app behind it.
            // Mirroring the hidden state onto the sheet's own window fixes both.
            KeepStatusBarHiddenInDialog()

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(),
            ) {
                state.content(this)
            }
        }
    }
}

/** Today's near-opaque charcoal (dark) — kept as the no-frost fallback. */
private val MenuOpaqueDark = Color(0xF01C1C1E)

/** The translucent charcoal the dark sheet uses once the frost is showing. */
private val MenuFrostDarkBase = Color(0x8C1C1C1E)

/** Longest edge the frost bitmap is downscaled to before blurring. */
private const val MenuFrostTargetMaxDim = 360

/** Box-blur radius on the downscaled bitmap (reads ~5x heavier on screen). */
private const val MenuFrostBoxRadius = 14

/** Box-blur passes — three passes approximate a Gaussian closely enough. */
private const val MenuFrostBoxPasses = 3

/** The blurred, downscaled app-window snapshot plus its original size. */
private data class MenuFrostSnapshot(
    val image: ImageBitmap,
    val fullWidthPx: Int,
    val fullHeightPx: Int,
)

/** Live state for the sheet frost: the snapshot and the sheet's window pin. */
@Stable
private class MenuFrostState {
    var snapshot by mutableStateOf<MenuFrostSnapshot?>(null)
    var sheetOffsetInWindow by mutableStateOf(Offset.Zero)
}

/**
 * Captures the app window the moment the sheet opens, blurs the snapshot off
 * the main thread and publishes it. The capture happens in the APP window
 * (this composable is hosted in the app's composition, before the
 * [ModalBottomSheet] opens its own dialog window), so [LocalView] is the app
 * view and its context resolves to the hosting [Activity].
 */
@Composable
private fun rememberMenuFrostState(visible: Boolean): MenuFrostState {
    val view = LocalView.current
    val frost = remember { MenuFrostState() }
    LaunchedEffect(visible, view) {
        if (!visible) {
            frost.snapshot = null
            return@LaunchedEffect
        }
        val window = view.context.findActivityOrNull()?.window ?: return@LaunchedEffect
        // PixelCopy.request is asynchronous — invoked on the main thread (the
        // LaunchedEffect's dispatcher); only the downscale + blur hops to the
        // Default dispatcher.
        val raw = captureWindowBitmap(window) ?: return@LaunchedEffect
        val processed =
            withContext(Dispatchers.Default) {
                processFrostBitmap(raw)
            }
        frost.snapshot = processed
    }
    return frost
}

/** Walks the [ContextWrapper] chain from a view context to its [Activity]. */
private fun Context.findActivityOrNull(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** One-shot [PixelCopy] of a whole window, suspend-wrapped. Null on failure. */
private suspend fun captureWindowBitmap(window: Window): Bitmap? =
    suspendCancellableCoroutine { cont ->
        try {
            val width = window.decorView.width
            val height = window.decorView.height
            if (width <= 0 || height <= 0) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        if (cont.isActive) {
                            cont.resume(bitmap)
                        } else {
                            bitmap.recycle()
                        }
                    } else {
                        bitmap.recycle()
                        if (cont.isActive) cont.resume(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

/**
 * Downscales the capture (a heavy frost needs no resolution — blur destroys
 * the detail anyway) and applies the box blur. Returns the blurred bitmap
 * plus the ORIGINAL capture size so the draw can pin it 1:1 to the screen.
 */
private fun processFrostBitmap(src: Bitmap): MenuFrostSnapshot {
    val fullWidthPx = src.width
    val fullHeightPx = src.height
    val maxDim = maxOf(fullWidthPx, fullHeightPx)
    val scale = maxOf(1, (maxDim + MenuFrostTargetMaxDim - 1) / MenuFrostTargetMaxDim)
    val small =
        if (scale > 1) {
            Bitmap
                .createScaledBitmap(src, fullWidthPx / scale, fullHeightPx / scale, true)
                .also {
                    if (it != src) src.recycle()
                }
        } else {
            src
        }
    boxBlur(small, MenuFrostBoxRadius, MenuFrostBoxPasses)
    return MenuFrostSnapshot(
        image = small.asImageBitmap(),
        fullWidthPx = fullWidthPx,
        fullHeightPx = fullHeightPx,
    )
}

/** Three box-blur passes in-place on the bitmap's pixels. */
private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0 || radius <= 0 || passes <= 0) return
    val src = IntArray(w * h)
    bmp.getPixels(src, 0, w, 0, 0, w, h)
    val dst = IntArray(w * h)
    repeat(passes) {
        boxBlurPass(src, dst, w, h, radius, horizontal = true)
        boxBlurPass(dst, src, w, h, radius, horizontal = false)
    }
    bmp.setPixels(src, 0, w, 0, 0, w, h)
}

/**
 * One box-blur pass (horizontal or vertical) with prefix sums per line and
 * clamped windows at the edges. Alpha is forced opaque — a window capture
 * has no transparency and the blurred sheet must not either.
 */
private fun boxBlurPass(
    src: IntArray,
    dst: IntArray,
    w: Int,
    h: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val outer = if (horizontal) h else w
    val inner = if (horizontal) w else h
    val lineStride = if (horizontal) w else 1
    val pixelStride = if (horizontal) 1 else w
    val prefixR = IntArray(inner + 1)
    val prefixG = IntArray(inner + 1)
    val prefixB = IntArray(inner + 1)
    for (line in 0 until outer) {
        val base = line * lineStride
        for (i in 0 until inner) {
            val p = src[base + i * pixelStride]
            prefixR[i + 1] = prefixR[i] + ((p shr 16) and 0xFF)
            prefixG[i + 1] = prefixG[i] + ((p shr 8) and 0xFF)
            prefixB[i + 1] = prefixB[i] + (p and 0xFF)
        }
        for (i in 0 until inner) {
            val hi = minOf(i + radius, inner - 1)
            val lo = maxOf(i - radius, 0)
            val count = hi - lo + 1
            val r = (prefixR[hi + 1] - prefixR[lo]) / count
            val g = (prefixG[hi + 1] - prefixG[lo]) / count
            val b = (prefixB[hi + 1] - prefixB[lo]) / count
            dst[base + i * pixelStride] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
