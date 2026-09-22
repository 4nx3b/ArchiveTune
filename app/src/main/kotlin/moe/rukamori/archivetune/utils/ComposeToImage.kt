/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.PixelCopy
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.view.drawToBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.LyricsShareImageOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

object ComposeToImage {
    private tailrec fun Context.findActivity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        val config = bitmap.config
        if (config != Bitmap.Config.HARDWARE && config != null) return bitmap
        return runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: bitmap
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private suspend fun pixelCopyViewBitmap(view: View): Bitmap? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val activity = view.context.findActivity() ?: return null
        val window = activity.window ?: return null

        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect =
            Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val copyResult =
            suspendCancellableCoroutine { cont ->
                PixelCopy.request(
                    window,
                    rect,
                    bitmap,
                    { result -> cont.resume(result) },
                    Handler(Looper.getMainLooper()),
                )
            }
        return if (copyResult == PixelCopy.SUCCESS) bitmap else null
    }

    suspend fun captureViewBitmap(
        view: View,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        backgroundColor: Int? = null,
    ): Bitmap {
        val fallbackBitmap =
            runCatching {
                view.drawToBitmap()
            }.getOrElse {
                val w = view.width.coerceAtLeast(1)
                val h = view.height.coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    backgroundColor?.let { Canvas(bmp).drawColor(it) }
                }
            }

        val original =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pixelCopyViewBitmap(view) ?: fallbackBitmap
            } else {
                fallbackBitmap
            }
        val needsScale =
            (targetWidth != null && targetWidth > 0 && targetWidth != original.width) ||
                (targetHeight != null && targetHeight > 0 && targetHeight != original.height)
        val base =
            if (needsScale) {
                val safeOriginal = ensureSoftwareBitmap(original)
                val tw = targetWidth ?: original.width
                val th = targetHeight ?: (original.height * tw / original.width)
                ensureSoftwareBitmap(Bitmap.createScaledBitmap(safeOriginal, tw, th, true))
            } else {
                ensureSoftwareBitmap(original)
            }
        if (backgroundColor != null) {
            val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(backgroundColor)
            c.drawBitmap(base, 0f, 0f, null)
            return out
        }
        return base
    }

    fun cropBitmap(
        source: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        val safeSource = ensureSoftwareBitmap(source)
        val safeLeft = left.coerceIn(0, safeSource.width.coerceAtLeast(1) - 1)
        val safeTop = top.coerceIn(0, safeSource.height.coerceAtLeast(1) - 1)
        val safeWidth = width.coerceIn(1, safeSource.width - safeLeft)
        val safeHeight = height.coerceIn(1, safeSource.height - safeTop)
        return ensureSoftwareBitmap(Bitmap.createBitmap(safeSource, safeLeft, safeTop, safeWidth, safeHeight))
    }

    fun coverBitmap(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        val safeSource = ensureSoftwareBitmap(source)
        val outW = targetWidth.coerceAtLeast(1)
        val outH = targetHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val scale =
            maxOf(
                outW.toFloat() / safeSource.width.coerceAtLeast(1),
                outH.toFloat() / safeSource.height.coerceAtLeast(1),
            )
        val scaledW = (safeSource.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (safeSource.height * scale).toInt().coerceAtLeast(1)
        val scaled =
            if (scaledW != safeSource.width || scaledH != safeSource.height) {
                ensureSoftwareBitmap(Bitmap.createScaledBitmap(safeSource, scaledW, scaledH, true))
            } else {
                safeSource
            }

        val dx = ((outW - scaled.width) / 2f)
        val dy = ((outH - scaled.height) / 2f)
        canvas.drawBitmap(scaled, dx, dy, null)
        return out
    }

    private fun stepScaleBitmap(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        step: Float = 1.35f,
    ): Bitmap {
        var current = source
        while (current.width < targetWidth * (1f / step) - 1f && current.height < targetHeight * (1f / step) - 1f) {
            val nextW = (current.width * step).toInt().coerceAtMost(targetWidth)
            val nextH = (current.height * step).toInt().coerceAtMost(targetHeight)
            current = ensureSoftwareBitmap(Bitmap.createScaledBitmap(current, nextW, nextH, true))
        }
        if (current.width != targetWidth || current.height != targetHeight) {
            current = ensureSoftwareBitmap(Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true))
        }
        return current
    }

    fun exportBitmapAtFhdFloor(
        source: Bitmap,
        minLongSide: Int = 1920,
        minShortSide: Int = 1080,
    ): Bitmap {
        val safeSource = ensureSoftwareBitmap(source)
        val longSide = maxOf(safeSource.width, safeSource.height)
        val shortSide = minOf(safeSource.width, safeSource.height)
        if (longSide >= minLongSide && shortSide >= minShortSide) {
            return safeSource
        }
        val longTarget = maxOf(minLongSide, longSide)
        val shortTarget = maxOf(minShortSide, shortSide)
        val scale = longTarget.toFloat() / longSide
        val targetShort = (shortSide * scale).toInt().coerceAtLeast(1)
        return if (safeSource.width >= safeSource.height) {
            stepScaleBitmap(safeSource, longTarget, targetShort)
        } else {
            stepScaleBitmap(safeSource, targetShort, longTarget)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun createLyricsImage(
        context: Context,
        coverArtUrl: String?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        width: Int,
        height: Int,
        textColor: Int? = null,
        shareOptions: LyricsShareImageOptions = LyricsShareImageOptions(),
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val canvasWidth = width.coerceAtLeast(1)
            val canvasHeight = height.coerceAtLeast(1)
            val baseSize = minOf(canvasWidth, canvasHeight).toFloat()
            val bitmap = createBitmap(canvasWidth, canvasHeight)
            val canvas = Canvas(bitmap)

            // ---- reference palette: dark frosted glass, warm off-white type ----
            // A custom text color (dialog swatches) overrides the whole lyric set.
            val emphasizedColor = textColor ?: 0xFFF7F0EB.toInt()
            val normalColor = (emphasizedColor and 0x00FFFFFF) or (0xE6 shl 24)
            val secondaryColor =
                if (textColor != null) {
                    (textColor and 0x00FFFFFF) or (0xBF shl 24)
                } else {
                    0xFFC9BFBC.toInt()
                }

            // ---- artwork (loaded once, reused for background + header) ----
            var coverArtBitmap: Bitmap? = null
            if (coverArtUrl != null) {
                try {
                    val imageLoader = ImageLoader(context)
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(coverArtUrl)
                            .size(max(canvasWidth, canvasHeight))
                            .allowHardware(false)
                            .build()
                    val result = imageLoader.execute(request)
                    coverArtBitmap = result.image?.toBitmap()
                } catch (e: Exception) {
                    reportException(e)
                }
            }

            // ---- ambient background: enlarged, heavily blurred artwork with a
            // dark vignette; adapts to every album's palette automatically ----
            val blurredBackground: Bitmap? =
                coverArtBitmap?.let { art ->
                    blurBitmap(coverBitmap(art, canvasWidth, canvasHeight), shareOptions.sanitizedBlurRadius.coerceAtLeast(14f))
                }
            if (blurredBackground != null) {
                val bleed = baseSize * 0.03f
                val bgRect = RectF(-bleed, -bleed, canvasWidth + bleed, canvasHeight + bleed)
                canvas.drawBitmap(blurredBackground, null, bgRect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawColor(0xFF151014.toInt())
            }

            val fullRect = RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
            canvas.drawRect(fullRect, Paint().apply { color = 0x2E0A0608.toInt() })
            canvas.drawRect(
                fullRect,
                Paint().apply {
                    shader =
                        RadialGradient(
                            canvasWidth / 2f,
                            canvasHeight / 2f,
                            maxOf(canvasWidth, canvasHeight) * 0.75f,
                            0x00000000,
                            0x8C000000,
                            Shader.TileMode.CLAMP,
                        )
                },
            )

            // ---- the frosted-glass card ----
            val cardWidth = canvasWidth * 0.92f
            val cardHeight = canvasHeight * 0.93f
            val cardLeft = (canvasWidth - cardWidth) / 2f
            val cardTop = (canvasHeight - cardHeight) / 2f
            val cardRight = cardLeft + cardWidth
            val cardBottom = cardTop + cardHeight
            val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)
            val cardRadius = baseSize * 0.028f

            // Soft shadow lifting the card off the background (soft glow ring).
            canvas.drawRoundRect(
                cardRect,
                cardRadius,
                cardRadius,
                Paint().apply {
                    color = 0x33000000
                    isAntiAlias = true
                    setShadowLayer(baseSize * 0.030f, 0f, baseSize * 0.005f, 0x66000000)
                },
            )

            val cardPath =
                Path().apply {
                    addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW)
                }
            if (blurredBackground != null) {
                val bleed = baseSize * 0.03f
                val bgRect = RectF(-bleed, -bleed, canvasWidth + bleed, canvasHeight + bleed)
                canvas.withClip(cardPath) {
                    drawBitmap(blurredBackground, null, bgRect, Paint(Paint.FILTER_BITMAP_FLAG))
                }
            } else {
                canvas.withClip(cardPath) { drawColor(0xFF2B2024.toInt()) }
            }
            // Dark translucent veil + slight tonal variation toward the bottom —
            // the reference's glass reads darker in the middle and lighter where
            // the artwork behind it is brighter.
            canvas.withClip(cardPath) {
                drawRect(fullRect, Paint().apply { color = 0x42140D10.toInt() })
                drawRect(
                    fullRect,
                    Paint().apply {
                        shader =
                            LinearGradient(
                                0f,
                                cardTop,
                                0f,
                                cardBottom,
                                0x00FFFFFF,
                                0x1AFFFFFF,
                                Shader.TileMode.CLAMP,
                            )
                    },
                )
            }
            canvas.drawRoundRect(
                cardRect,
                cardRadius,
                cardRadius,
                Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (baseSize * 0.0012f).coerceAtLeast(1.5f)
                    color = 0x66FFFFFF
                    isAntiAlias = true
                },
            )

            // ---- header: artwork upper-left, title/artist to its right ----
            val contentInset = cardWidth * 0.042f
            val artSize = baseSize * 0.20f
            val artTop = cardTop + cardHeight * 0.045f
            val artLeft = cardLeft + contentInset
            val artRadius = artSize * 0.075f

            val showingArtwork = shareOptions.showArtwork && coverArtBitmap != null
            if (showingArtwork) {
                val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
                val artPath =
                    Path().apply {
                        addRoundRect(artRect, artRadius, artRadius, Path.Direction.CW)
                    }
                canvas.withClip(artPath) {
                    drawBitmap(coverArtBitmap!!, null, artRect, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                canvas.drawRoundRect(
                    artRect,
                    artRadius,
                    artRadius,
                    Paint().apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (baseSize * 0.0008f).coerceAtLeast(1f)
                        color = 0x33FFFFFF
                        isAntiAlias = true
                    },
                )
            }

            val titlePaint =
                TextPaint().apply {
                    color = emphasizedColor
                    textSize = baseSize * 0.036f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                    letterSpacing = -0.01f
                }
            val artistPaint =
                TextPaint().apply {
                    color = secondaryColor
                    textSize = baseSize * 0.020f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    isAntiAlias = true
                }

            val textStartX =
                if (showingArtwork) {
                    artLeft + artSize + baseSize * 0.030f
                } else {
                    cardLeft + contentInset
                }
            val textMaxWidth = (cardRight - contentInset - textStartX).coerceAtLeast(1f).toInt()

            val titleLayout =
                StaticLayout.Builder
                    .obtain(songTitle, 0, songTitle.length, titlePaint, textMaxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.08f)
                    .setIncludePad(false)
                    .setMaxLines(3)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            val artistLayout =
                StaticLayout.Builder
                    .obtain(artistName, 0, artistName.length, artistPaint, textMaxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()

            val headerGap = baseSize * 0.010f
            val headerBlockHeight = titleLayout.height + headerGap + artistLayout.height
            val headerTop =
                if (showingArtwork) {
                    artTop + (artSize - headerBlockHeight) * 0.56f
                } else {
                    cardTop + cardHeight * 0.055f
                }
            canvas.withTranslation(textStartX, headerTop) {
                titleLayout.draw(this)
                translate(0f, titleLayout.height + headerGap)
                artistLayout.draw(this)
            }
            val headerAnchorBottom =
                if (showingArtwork) {
                    artTop + artSize
                } else {
                    headerTop + headerBlockHeight
                }
            val headerBottom = maxOf(headerAnchorBottom, headerTop + headerBlockHeight)

            // ---- footer: logo + ArchiveTune + separator + tagline ----
            val footerCenterY = cardBottom - cardHeight * 0.085f
            val footerTop = footerCenterY - baseSize * 0.032f

            val logoDiameter = baseSize * 0.058f
            val logoCenterX = cardLeft + contentInset + logoDiameter / 2f
            canvas.drawCircle(
                logoCenterX,
                footerCenterY,
                logoDiameter / 2f,
                Paint().apply {
                    color = 0xFF1C1C22.toInt()
                    isAntiAlias = true
                },
            )
            val monogramSize = (logoDiameter * 0.56f).toInt().coerceAtLeast(8)
            context.getDrawable(R.drawable.small_icon)?.toBitmap(monogramSize, monogramSize)?.let { source ->
                val tinted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                Canvas(tinted).drawBitmap(
                    source,
                    0f,
                    0f,
                    Paint().apply {
                        colorFilter = PorterDuffColorFilter(0xE6FFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                        isAntiAlias = true
                    },
                )
                canvas.drawBitmap(
                    tinted,
                    logoCenterX - tinted.width / 2f,
                    footerCenterY - tinted.height / 2f,
                    null,
                )
            }

            val brandPaint =
                TextPaint().apply {
                    color = 0xFFF2EDE8.toInt()
                    textSize = baseSize * 0.023f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isAntiAlias = true
                    letterSpacing = 0.01f
                }
            val appName = context.getString(R.string.app_name)
            val brandX = logoCenterX + logoDiameter / 2f + baseSize * 0.014f
            drawVerticallyCenteredText(canvas, appName, brandX, footerCenterY, brandPaint)

            val taglinePaint =
                TextPaint().apply {
                    color = 0xB8D6CFC9.toInt()
                    textSize = baseSize * 0.0165f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    isAntiAlias = true
                    letterSpacing = 0.20f
                }
            val tagline = context.getString(R.string.archivetune_tagline)
            val taglineWidth = taglinePaint.measureText(tagline)
            val taglineEnd = cardRight - contentInset
            drawVerticallyCenteredText(canvas, tagline, taglineEnd - taglineWidth, footerCenterY, taglinePaint)

            val brandWidth = brandPaint.measureText(appName)
            val separatorStart = brandX + brandWidth + baseSize * 0.022f
            val separatorEnd = taglineEnd - taglineWidth - baseSize * 0.020f
            if (separatorEnd > separatorStart) {
                canvas.drawLine(
                    separatorStart,
                    footerCenterY,
                    separatorEnd,
                    footerCenterY,
                    Paint().apply {
                        strokeWidth = (baseSize * 0.0012f).coerceAtLeast(1.2f)
                        color = 0x47FFFFFF
                        isAntiAlias = true
                    },
                )
            }

            // ---- lyrics: the hero content, centered with generous rhythm ----
            val lyricLines =
                lyrics
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() }
                    .toList()
            if (lyricLines.isNotEmpty()) {
                val lyricsTop = headerBottom + cardHeight * 0.045f
                val lyricsBottom = footerTop - cardHeight * 0.035f
                val availableLyricsHeight = (lyricsBottom - lyricsTop).coerceAtLeast(1f)
                val lyricsMaxWidth = (cardWidth * 0.86f).toInt()

                // The selection's hook gets the emphasis, mirroring the
                // reference: for the default 5-line selection that is the second
                // line (the long hook wraps onto two rows exactly like the
                // reference composition); shorter selections keep the middle.
                val emphasizedIndex =
                    if (lyricLines.size >= 5) {
                        lyricLines.size / 2 - 1
                    } else {
                        lyricLines.size / 2
                    }

                fun buildRows(scale: Float): List<Pair<StaticLayout, Boolean>> =
                    lyricLines.mapIndexed { index, line ->
                        val emphasized = index == emphasizedIndex
                        val paint =
                            TextPaint().apply {
                                color = if (emphasized) emphasizedColor else normalColor
                                textSize = baseSize * 0.042f * scale * (if (emphasized) 1.28f else 1f)
                                typeface =
                                    if (emphasized) {
                                        Typeface.DEFAULT_BOLD
                                    } else {
                                        Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                    }
                                isAntiAlias = true
                                letterSpacing = -0.012f
                            }
                        val layout =
                            StaticLayout.Builder
                                .obtain(line, 0, line.length, paint, lyricsMaxWidth)
                                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                                .setLineSpacing(0f, 1.06f)
                                .setIncludePad(false)
                                .setMaxLines(4)
                                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                                .build()
                        layout to emphasized
                    }

                // Generous editorial pitch: 0.62em of rest between rows plus
                // extra breathing around the emphasized hook, like the
                // reference's vertical rhythm.
                fun rowGap(
                    previous: Pair<StaticLayout, Boolean>,
                    current: Pair<StaticLayout, Boolean>,
                ): Float {
                    var gap = previous.first.paint.textSize * 0.62f
                    if (previous.second || current.second) gap += current.first.paint.textSize * 0.35f
                    return gap
                }

                fun blockHeight(rows: List<Pair<StaticLayout, Boolean>>): Float {
                    var height = 0f
                    rows.forEachIndexed { index, row ->
                        if (index > 0) height += rowGap(rows[index - 1], row)
                        height += row.first.height
                    }
                    return height
                }

                var rows = buildRows(1f)
                var fitScale = 1f
                while (blockHeight(rows) > availableLyricsHeight && fitScale > 0.55f) {
                    fitScale *= 0.94f
                    rows = buildRows(fitScale)
                }

                val totalHeight = blockHeight(rows)
                var y = lyricsTop + (availableLyricsHeight - totalHeight) / 2f
                rows.forEachIndexed { index, row ->
                    if (index > 0) y += rowGap(rows[index - 1], row)
                    canvas.withTranslation(cardLeft + (cardWidth - row.first.width) / 2f, y) {
                        row.first.draw(this)
                    }
                    y += row.first.height
                }
            }

            return@withContext bitmap
        }

    /** Center-crops the artwork to the given canvas so blurred edges never run out. */
    private fun coverBitmap(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        val safe = ensureSoftwareBitmap(source)
        val targetW = targetWidth.coerceAtLeast(1)
        val targetH = targetHeight.coerceAtLeast(1)
        val srcRatio = safe.width.toFloat() / safe.height.toFloat()
        val dstRatio = targetW.toFloat() / targetH.toFloat()
        return if (kotlin.math.abs(srcRatio - dstRatio) < 0.01f) {
            ensureSoftwareBitmap(Bitmap.createScaledBitmap(safe, targetW, targetH, true))
        } else {
            val cropW: Int
            val cropH: Int
            if (srcRatio > dstRatio) {
                cropH = safe.height
                cropW = (safe.height * dstRatio).toInt().coerceAtMost(safe.width)
            } else {
                cropW = safe.width
                cropH = (safe.width / dstRatio).toInt().coerceAtMost(safe.height)
            }
            val left = (safe.width - cropW) / 2
            val top = (safe.height - cropH) / 2
            val cropped = Bitmap.createBitmap(safe, left, top, cropW, cropH)
            ensureSoftwareBitmap(Bitmap.createScaledBitmap(cropped, targetW, targetH, true))
        }
    }

    private fun drawVerticallyCenteredText(
        canvas: Canvas,
        text: String,
        x: Float,
        centerY: Float,
        paint: TextPaint,
    ) {
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun blurBitmap(
        source: Bitmap,
        radius: Float,
    ): Bitmap {
        val safe = ensureSoftwareBitmap(source)
        val safeRadius = radius.coerceIn(0f, 48f)
        if (safeRadius <= 0.5f) return safe
        val maxDimension = max(safe.width, safe.height)
        if (maxDimension <= 720 || safeRadius < 8f) {
            return stackBlur(safe, safeRadius.roundToInt().coerceAtLeast(1))
        }

        val scale = 720f / maxDimension.toFloat()
        val scaledWidth = (safe.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (safe.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = ensureSoftwareBitmap(Bitmap.createScaledBitmap(safe, scaledWidth, scaledHeight, true))
        val blurred = stackBlur(scaled, (safeRadius * scale).roundToInt().coerceAtLeast(1))
        return ensureSoftwareBitmap(Bitmap.createScaledBitmap(blurred, safe.width, safe.height, true))
    }

    private fun stackBlur(
        source: Bitmap,
        radius: Int,
    ): Bitmap {
        val bitmap = ensureSoftwareBitmap(source.copy(Bitmap.Config.ARGB_8888, true))
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val wm = width - 1
        val hm = height - 1
        val div = radius + radius + 1
        val red = IntArray(width * height)
        val green = IntArray(width * height)
        val blue = IntArray(width * height)
        val vMin = IntArray(max(width, height))
        val divSum = ((div + 1) shr 1).let { it * it }
        val divTable = IntArray(256 * divSum) { it / divSum }
        val stack = Array(div) { IntArray(3) }

        var yi = 0
        var yw = 0
        for (y in 0 until height) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0

            for (i in -radius..radius) {
                val p = pixels[yi + i.coerceIn(0, wm)]
                val sir = stack[i + radius]
                sir[0] = p shr 16 and 0xFF
                sir[1] = p shr 8 and 0xFF
                sir[2] = p and 0xFF
                val rbs = radius + 1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }

            var stackPointer = radius
            for (x in 0 until width) {
                red[yi] = divTable[rsum]
                green[yi] = divTable[gsum]
                blue[yi] = divTable[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackStart = (stackPointer - radius + div) % div
                val sir = stack[stackStart]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vMin[x] = (x + radius + 1).coerceAtMost(wm)
                }
                val p = pixels[yw + vMin[x]]
                sir[0] = p shr 16 and 0xFF
                sir[1] = p shr 8 and 0xFF
                sir[2] = p and 0xFF

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackPointer = (stackPointer + 1) % div
                val nextSir = stack[stackPointer]

                routsum += nextSir[0]
                goutsum += nextSir[1]
                boutsum += nextSir[2]

                rinsum -= nextSir[0]
                ginsum -= nextSir[1]
                binsum -= nextSir[2]
                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var yp = -radius * width

            for (i in -radius..radius) {
                val yiIndex = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = red[yiIndex]
                sir[1] = green[yiIndex]
                sir[2] = blue[yiIndex]
                val rbs = radius + 1 - kotlin.math.abs(i)
                rsum += red[yiIndex] * rbs
                gsum += green[yiIndex] * rbs
                bsum += blue[yiIndex] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) yp += width
            }

            var yiIndex = x
            var stackPointer = radius
            for (y in 0 until height) {
                pixels[yiIndex] =
                    pixels[yiIndex] and -0x1000000 or
                    (divTable[rsum] shl 16) or
                    (divTable[gsum] shl 8) or
                    divTable[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackStart = (stackPointer - radius + div) % div
                val sir = stack[stackStart]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vMin[y] = ((y + radius + 1).coerceAtMost(hm)) * width
                }
                val p = x + vMin[y]
                sir[0] = red[p]
                sir[1] = green[p]
                sir[2] = blue[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackPointer = (stackPointer + 1) % div
                val nextSir = stack[stackPointer]

                routsum += nextSir[0]
                goutsum += nextSir[1]
                boutsum += nextSir[2]

                rinsum -= nextSir[0]
                ginsum -= nextSir[1]
                binsum -= nextSir[2]

                yiIndex += width
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun createVinylImage(
        context: Context,
        coverArtUrl: String?,
        songTitle: String,
        artistName: String,
        width: Int,
        height: Int,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val canvasSize = maxOf(width, height).coerceAtLeast(1080)
            val bitmap = createBitmap(canvasSize, canvasSize)
            val canvas = Canvas(bitmap)

            val bgTop = 0xFF0A1F24.toInt()
            val bgBottom = 0xFF051418.toInt()
            val bgPaint = Paint().apply {
                isAntiAlias = true
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, canvasSize.toFloat(),
                    bgTop, bgBottom,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, canvasSize.toFloat(), canvasSize.toFloat(), bgPaint)

            var coverArtBitmap: Bitmap? = null
            if (coverArtUrl != null) {
                runCatching {
                    val imageLoader = ImageLoader(context)
                    val request = ImageRequest.Builder(context)
                        .data(coverArtUrl)
                        .size(canvasSize / 2)
                        .allowHardware(false)
                        .build()
                    coverArtBitmap = imageLoader.execute(request).image?.toBitmap()
                }
            }

            val coverSize = canvasSize * 0.46f
            val discSize = canvasSize * 0.46f
            val coverLeft = canvasSize * 0.22f
            val coverTop = canvasSize * 0.18f
            val discLeft = coverLeft + coverSize * 0.62f
            val discTop = coverTop
            val discCenterX = discLeft + discSize / 2f
            val discCenterY = discTop + discSize / 2f

            val discPaint = Paint().apply {
                color = 0xFF050505.toInt()
                isAntiAlias = true
            }
            canvas.drawCircle(discCenterX, discCenterY, discSize / 2f, discPaint)

            val groovePaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                color = 0x14FFFFFF
                isAntiAlias = true
            }
            val grooveCount = 28
            for (i in 1..grooveCount) {
                val radius = (discSize / 2f) * (i.toFloat() / (grooveCount + 1))
                canvas.drawCircle(discCenterX, discCenterY, radius, groovePaint)
            }

            val labelRadius = discSize * 0.19f
            val labelPaint = Paint().apply {
                isAntiAlias = true
                shader = android.graphics.RadialGradient(
                    discCenterX, discCenterY, labelRadius,
                    0xFF7DD3D8.toInt(), 0xFF4FB6BC.toInt(),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(discCenterX, discCenterY, labelRadius, labelPaint)

            val holePaint = Paint().apply {
                color = 0xFF000000.toInt()
                isAntiAlias = true
            }
            canvas.drawCircle(discCenterX, discCenterY, discSize * 0.012f, holePaint)

            val labelTextSize = labelRadius * 0.32f
            val titlePaint = TextPaint().apply {
                color = 0xFF0A1F24.toInt()
                textSize = labelTextSize
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                letterSpacing = -0.01f
            }
            val artistPaint = TextPaint().apply {
                color = 0xFF0A1F24.toInt()
                textSize = labelTextSize * 0.78f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
            }
            val maxLabelWidth = (labelRadius * 1.7f).toInt()
            val titleLayout = StaticLayout.Builder
                .obtain(songTitle, 0, songTitle.length, titlePaint, maxLabelWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            val artistLayout = StaticLayout.Builder
                .obtain(artistName, 0, artistName.length, artistPaint, maxLabelWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            val labelTextBlockHeight = titleLayout.height + artistLayout.height + (labelTextSize * 0.18f).toInt()
            val labelStartY = discCenterY - labelTextBlockHeight / 2f
            canvas.withTranslation(discCenterX - maxLabelWidth / 2f, labelStartY) {
                titleLayout.draw(canvas)
                canvas.translate(0f, titleLayout.height + (labelTextSize * 0.18f))
                artistLayout.draw(canvas)
            }

            val coverRect = RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize)
            val coverPath = Path().apply {
                addRect(coverRect, Path.Direction.CW)
            }
            canvas.withClip(coverPath) {
                if (coverArtBitmap != null) {
                    drawBitmap(coverArtBitmap!!, null, coverRect, Paint(Paint.FILTER_BITMAP_FLAG))
                } else {
                    drawRect(coverRect, Paint().apply { color = 0xFF1A2A2E.toInt() })
                }
            }

            val coverBorderPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = coverSize * 0.015f
                color = 0xFFFFFFFF.toInt()
                isAntiAlias = true
            }
            canvas.drawRect(coverRect, coverBorderPaint)

            val titleTextPaint = TextPaint().apply {
                color = 0xFFFFFFFF.toInt()
                textSize = canvasSize * 0.038f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                letterSpacing = -0.01f
            }
            val artistTextPaint = TextPaint().apply {
                color = 0xCCFFFFFF.toInt()
                textSize = canvasSize * 0.028f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
            }
            val textBlockX = canvasSize / 2f
            val textBlockTopY = coverTop + coverSize + canvasSize * 0.06f
            val maxTextWidth = (canvasSize * 0.8f).toInt()

            val titleTextLayout = StaticLayout.Builder
                .obtain(songTitle, 0, songTitle.length, titleTextPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            val artistTextLayout = StaticLayout.Builder
                .obtain(artistName, 0, artistName.length, artistTextPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()

            canvas.withTranslation(
                textBlockX - titleTextLayout.width / 2f,
                textBlockTopY,
            ) {
                titleTextLayout.draw(canvas)
                canvas.translate(0f, titleTextLayout.height + canvasSize * 0.012f)
                artistTextLayout.draw(canvas)
            }

            val wordmarkPaint = TextPaint().apply {
                color = 0x99FFFFFF.toInt()
                textSize = canvasSize * 0.018f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
                letterSpacing = 0.18f
            }
            val wordmark = "ARCHIVETUNE"
            val wordmarkWidth = wordmarkPaint.measureText(wordmark)
            val wordmarkX = (canvasSize - wordmarkWidth) / 2f
            val wordmarkY = canvasSize * 0.93f + wordmarkPaint.textSize
            canvas.drawText(wordmark, wordmarkX, wordmarkY, wordmarkPaint)

            bitmap
        }

    fun saveBitmapAsFile(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
    ): Uri {
        val safeBitmap = ensureSoftwareBitmap(bitmap)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArchiveTune")
                }
            val uri =
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues,
                ) ?: throw IllegalStateException("Failed to create new MediaStore record")

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                safeBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            uri
        } else {
            val cachePath = File(context.cacheDir, "images")
            cachePath.mkdirs()
            val imageFile = File(cachePath, "$fileName.png")
            FileOutputStream(imageFile).use { outputStream ->
                safeBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                imageFile,
            )
        }
    }
}

suspend fun saveCoverArtworkFromUrl(
    context: Context,
    thumbnailUrl: String?,
    fileName: String,
): Uri? {
    if (thumbnailUrl.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val loader = coil3.SingletonImageLoader.get(context)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(thumbnailUrl)
                    .allowHardware(false)
                    .build()
            val result = loader.execute(request)
            val bitmap = result.image?.toBitmap() ?: return@runCatching null
            ComposeToImage.saveBitmapAsFile(context, bitmap, fileName)
        }.getOrNull()
    }
}
