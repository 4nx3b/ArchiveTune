/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Ported from Flamingo (yos.music.player) ui/widgets/effects/YosFloatingLightView.kt — GPLv3,
 * https://github.com/shouryadixitisverycool/Flamingo
 *
 * The color behind the artwork: the cover is center-cropped, downscaled,
 * saturated x3, darkened with two overlay fills, then stack-blurred (radius 25).
 * With the background effect enabled it slowly pans/zooms (Ken Burns, 12s
 * transitions); when leaving the lyrics page an identical copy is laid over it
 * with a 0x33000000 Overlay tint fading to alpha 0.618.
 */

package moe.rukamori.archivetune.ui.player.flamingo

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Page keys of the Flamingo player's in-page state machine. */
object FlamingoPage {
    const val Album = "Album"
    const val PlayingList = "PlayingList"
    const val Lyric = "Lyric"
}

/**
 * Ported from Flamingo's YosFloatingLight. Renders the processed artwork as the
 * player background with an optional Ken Burns drift, plus the dim layer that
 * fades in (alpha 0.618, 300ms FastOutSlowIn) whenever the lyrics page is not
 * showing.
 */
@Composable
fun FlamingoFloatingLight(
    modifier: Modifier,
    albumUrl: () -> String?,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    backgroundEffect: Boolean,
) {
    val context = LocalContext.current

    val processedBitmap by produceState<android.graphics.Bitmap?>(null, albumUrl()) {
        val url = albumUrl()
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val source: Bitmap = result.image.toBitmap()
                    val compressed = FlamingoBitmapResolver.bitmapCompress(
                        source,
                        px = if (backgroundEffect) 96 else 32,
                    )
                    flamingoImageResolve(compressed)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    FlamingoWrapper {
        val lossEffect = remember("FlamingoFloatingLight_lossEffect") {
            androidx.compose.runtime.derivedStateOf {
                nowPage() != FlamingoPage.Lyric
            }
        }

        val useBackground = remember("FlamingoFloatingLight_useBackground") {
            androidx.compose.runtime.derivedStateOf {
                albumUrl() == null
            }
        }

        val bitmap = processedBitmap

        if (bitmap != null) {
            if (backgroundEffect) {
                KenBurnsImage(
                    bitmap = bitmap,
                    isPlaying = isPlaying,
                    modifier = modifier
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            onDrawBehind {
                                if (useBackground.value) {
                                    drawRect(Color.Black)
                                }
                            }
                        },
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            onDrawBehind {
                                if (useBackground.value) {
                                    drawRect(Color.Black)
                                }
                            }
                        },
                )
            }
        } else {
            androidx.compose.foundation.layout.Box(
                modifier = modifier.drawWithCache {
                    onDrawBehind {
                        drawRect(Color.Black)
                    }
                },
            )
        }

        FlamingoWrapper {
            val alpha = animateFloatAsState(
                targetValue = if (lossEffect.value) 0.618f else 0f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing,
                ),
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.tint(Color(0x33000000), BlendMode.Overlay),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            this.alpha = alpha.value
                        },
                )
            }
        }
    }
}

/**
 * Compose reimplementation of Flamingo's KenBurnsView usage
 * (RandomTransitionGenerator, 12000ms, AccelerateDecelerateInterpolator):
 * an endless sequence of 12-second scale/pan transitions between randomized
 * targets, frozen in place while playback is paused.
 */
@Composable
private fun KenBurnsImage(
    bitmap: android.graphics.Bitmap,
    isPlaying: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scaleAnim = remember(bitmap) { Animatable(1f) }
    val translationXAnim = remember(bitmap) { Animatable(0f) }
    val translationYAnim = remember(bitmap) { Animatable(0f) }

    LaunchedEffect(bitmap, isPlaying()) {
        if (!isPlaying()) return@LaunchedEffect
        val random = Random(bitmap.hashCode())
        while (true) {
            val targetScale = 1.12f + random.nextFloat() * 0.25f
            val targetTx = (random.nextFloat() - 0.5f) * 0.07f
            val targetTy = (random.nextFloat() - 0.5f) * 0.07f

            val scaleTo = targetScale / scaleAnim.value
            val txTo = targetTx - translationXAnim.value
            val tyTo = targetTy - translationYAnim.value

            val scaleSpec = tween<Float>(durationMillis = 12000, easing = FastOutSlowInEasing)
            val txSpec = tween<Float>(
                durationMillis = (12000 * (if (txTo != 0f) (scaleTo / txTo).let { c ->
                    (c * 0.07f).coerceIn(0.2f, 1f)
                } else 1f)).toInt(),
                easing = FastOutSlowInEasing,
            )
            val tySpec = tween<Float>(
                durationMillis = (12000 * (if (tyTo != 0f) (scaleTo / tyTo).let { c ->
                    (c * 0.07f).coerceIn(0.2f, 1f)
                } else 1f)).toInt(),
                easing = FastOutSlowInEasing,
            )

            coroutineScope {
                launch { scaleAnim.animateTo(targetScale, scaleSpec) }
                launch { translationXAnim.animateTo(targetTx, txSpec) }
                launch { translationYAnim.animateTo(targetTy, tySpec) }
            }
        }
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                translationX = translationXAnim.value * size.width
                translationY = translationYAnim.value * size.height
            },
    )
}
