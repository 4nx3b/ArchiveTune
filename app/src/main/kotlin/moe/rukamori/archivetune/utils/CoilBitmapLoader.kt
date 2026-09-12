/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlin.math.roundToInt

internal const val NotificationArtworkSizePx = 1080

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                if (data.isEmpty()) {
                    throw IllegalArgumentException("Empty image data")
                }

                val mediaSessionBitmap =
                    decodeSampledBitmap(data)
                        ?.toOwnedMediaSessionBitmap()
                        ?.scaleToNotificationArtwork()
                if (mediaSessionBitmap != null) {
                    return@future mediaSessionBitmap
                }

                throw IllegalStateException("Could not decode image data")
            } catch (e: Exception) {
                reportException(e)
                return@future createBitmap(64, 64)
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            val attempts = 3
            for (attempt in 1..attempts) {
                try {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(uri)
                            .allowHardware(false)
                            .size(NotificationArtworkSizePx, NotificationArtworkSizePx)
                            .build()

                    val result = context.imageLoader.execute(request)

                    when (result) {
                        is SuccessResult -> {
                            try {
                                val mediaSessionBitmap =
                                    result.image
                                        .toBitmap()
                                        .toOwnedMediaSessionBitmap()
                                        ?.scaleToNotificationArtwork()
                                if (mediaSessionBitmap == null) {
                                    return@future createBitmap(64, 64)
                                }

                                return@future mediaSessionBitmap
                            } catch (e: Exception) {
                                reportException(e)
                            }
                        }

                        is ErrorResult -> {
                            result.throwable?.let { reportException(it) }
                        }
                    }
                } catch (e: Exception) {
                    reportException(e)
                }

                if (attempt < attempts) {
                    delay(250L * attempt)
                    continue
                }
            }
            createBitmap(64, 64)
        }
}

private fun decodeSampledBitmap(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
    while (largestDimension / (sampleSize * 2) >= NotificationArtworkSizePx) {
        sampleSize *= 2
    }

    return BitmapFactory.decodeByteArray(
        data,
        0,
        data.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

private fun Bitmap.scaleToNotificationArtwork(): Bitmap? {
    if (isRecycled || width <= 0 || height <= 0) return null
    if (width <= NotificationArtworkSizePx && height <= NotificationArtworkSizePx) return this

    val scale =
        minOf(
            NotificationArtworkSizePx.toFloat() / width.toFloat(),
            NotificationArtworkSizePx.toFloat() / height.toFloat(),
        )
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun Bitmap.toOwnedMediaSessionBitmap(): Bitmap? {
    if (isRecycled) return null
    return copy(Bitmap.Config.ARGB_8888, false)?.takeUnless(Bitmap::isRecycled)
}
