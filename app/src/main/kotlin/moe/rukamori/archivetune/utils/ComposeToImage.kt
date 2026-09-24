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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
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
import moe.rukamori.archivetune.ui.component.LyricsShareStyle
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

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
            // The classic preset family (restored from the pre-liquid-glass
            // share popup) renders through its own pipeline; the liquid-glass
            // engine below is just one style among the rest now.
            if (shareOptions.style != LyricsShareStyle.LIQUID_GLASS) {
                return@withContext createClassicLyricsImage(
                    context = context,
                    coverArtUrl = coverArtUrl,
                    songTitle = songTitle,
                    artistName = artistName,
                    lyrics = lyrics,
                    width = width,
                    height = height,
                    textColor = textColor,
                    shareOptions = shareOptions,
                )
            }
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

            // ---- card geometry first: the liquid-glass layers need it ----
            val cardWidth = canvasWidth * 0.92f
            val cardHeight = canvasHeight * 0.93f
            val cardLeft = (canvasWidth - cardWidth) / 2f
            val cardTop = (canvasHeight - cardHeight) / 2f
            val cardRight = cardLeft + cardWidth
            val cardBottom = cardTop + cardHeight
            val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)
            // Reference-exact roundness: ~6% of card width, clearly softer
            // than the old 2.8% pill.
            val cardRadius = baseSize * 0.055f
            val cardPath =
                Path().apply {
                    addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW)
                }

            // ---- reference liquid glass ----
            // The whole glass look is derived from the artwork itself: a frosted,
            // liquid-warped base for the ambient background plus a separately
            // refracted and tinted layer behind the card. All displacement runs
            // on a downscaled working copy — the source is already low-frequency
            // — so the 3072px export stays fast while every crisp element (type,
            // artwork, borders) renders at native canvas resolution. The blur
            // radius is expressed at a 2048px reference scale, so the preview
            // and the export always show the same relative frost.
            val referenceScale = maxOf(canvasWidth, canvasHeight) / 2048f
            val glassLayers =
                coverArtBitmap?.let { art ->
                    renderLiquidGlassLayers(art, canvasWidth, canvasHeight, cardRect, shareOptions, referenceScale)
                }

            // ---- ambient background: liquid-glass blurred artwork, dimmed and
            // vignetted by the dim slider; adapts to every album's palette ----
            if (glassLayers != null) {
                val bleed = baseSize * 0.03f
                val bgRect = RectF(-bleed, -bleed, canvasWidth + bleed, canvasHeight + bleed)
                canvas.drawBitmap(glassLayers.ambient, null, bgRect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawColor(0xFF151014.toInt())
            }

            val fullRect = RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
            val dimAmount = shareOptions.sanitizedDimAmount
            if (dimAmount > 0f) {
                val dimAlpha = (dimAmount * 0.58f * 255f).toInt().coerceIn(0, 255)
                canvas.drawRect(
                    fullRect,
                    Paint().apply { color = (dimAlpha shl 24) or 0x0A0608 },
                )
                canvas.drawRect(
                    fullRect,
                    Paint().apply {
                        shader =
                            RadialGradient(
                                canvasWidth / 2f,
                                canvasHeight / 2f,
                                maxOf(canvasWidth, canvasHeight) * 0.75f,
                                0x00000000,
                                ((dimAmount * 0.55f * 255f).toInt().coerceIn(0, 255)) shl 24,
                                Shader.TileMode.CLAMP,
                            )
                    },
                )
            }

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

            // ---- the frosted-glass fill: the refracted glass layer at the
            // opacity the slider picks ----
            if (glassLayers != null) {
                val bleed = baseSize * 0.03f
                val bgRect = RectF(-bleed, -bleed, canvasWidth + bleed, canvasHeight + bleed)
                val glassAlpha =
                    (shareOptions.sanitizedGlassOpacity * 255f).toInt().coerceIn(0, 255)
                canvas.withClip(cardPath) {
                    drawBitmap(
                        glassLayers.glass,
                        null,
                        bgRect,
                        Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = glassAlpha },
                    )
                }
            } else {
                canvas.withClip(cardPath) { drawColor(0xFF2B2024.toInt()) }
            }
            // Tonal balance + the reference's soft white glow falling from the
            // top edge of the glass.
            canvas.withClip(cardPath) {
                drawRect(fullRect, Paint().apply { color = 0x2A140D10.toInt() })
                drawRect(
                    fullRect,
                    Paint().apply {
                        shader =
                            LinearGradient(
                                0f,
                                cardTop,
                                0f,
                                cardTop + cardHeight * 0.42f,
                                0x2EFFFFFF,
                                0x00FFFFFF,
                                Shader.TileMode.CLAMP,
                            )
                    },
                )
            }
            // Outer hairline.
            canvas.drawRoundRect(
                cardRect,
                cardRadius,
                cardRadius,
                Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (baseSize * 0.0013f).coerceAtLeast(1.5f)
                    color = 0x59FFFFFF
                    isAntiAlias = true
                },
            )
            // The rounder internal border: an inset ring with its own (smaller)
            // corner radius, echoing the reference's layered glass rim.
            val innerInset = baseSize * 0.0075f
            val innerRect =
                RectF(
                    cardLeft + innerInset,
                    cardTop + innerInset,
                    cardRight - innerInset,
                    cardBottom - innerInset,
                )
            val innerRadius = (cardRadius - innerInset).coerceAtLeast(1f)
            canvas.drawRoundRect(
                innerRect,
                innerRadius,
                innerRadius,
                Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (baseSize * 0.0009f).coerceAtLeast(1f)
                    color = 0x2EFFFFFF
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

            // ---- reference-exact typography: Figtree, the closest open
            // match to the reference's Circular — loaded straight from font
            // resources, entirely outside the app's Compose font system ----
            val figtreeBold = shareCardTypeface(context, ShareCardFontWeight.BOLD)
            val figtreeMedium = shareCardTypeface(context, ShareCardFontWeight.MEDIUM)
            val figtreeRegular = shareCardTypeface(context, ShareCardFontWeight.REGULAR)

            val titlePaint =
                TextPaint().apply {
                    color = emphasizedColor
                    textSize = baseSize * 0.040f
                    typeface = figtreeBold
                    isAntiAlias = true
                    letterSpacing = -0.01f
                }
            val artistPaint =
                TextPaint().apply {
                    color = secondaryColor
                    textSize = baseSize * 0.0195f
                    typeface = figtreeMedium
                    isAntiAlias = true
                    letterSpacing = 0.025f
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
                    .obtain(artistName.uppercase(), 0, artistName.uppercase().length, artistPaint, textMaxWidth)
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

            // ---- footer: the ArchiveTune wordmark only — no monogram, no
            // separator, no tagline (reference typography, Figtree bold) ----
            val footerCenterY = cardBottom - cardHeight * 0.085f
            val footerTop = footerCenterY - baseSize * 0.032f

            val brandPaint =
                TextPaint().apply {
                    color = 0xFFF2EDE8.toInt()
                    textSize = baseSize * 0.024f
                    typeface = figtreeBold
                    isAntiAlias = true
                    letterSpacing = 0.01f
                }
            val appName = context.getString(R.string.app_name)
            drawVerticallyCenteredText(canvas, appName, cardLeft + contentInset, footerCenterY, brandPaint)

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
                                // Reference pitch: the hook at ~8-9% of card width
                                // in bold, surrounding lines regular at ~6%.
                                textSize = baseSize * 0.050f * scale * (if (emphasized) 1.36f else 1f)
                                typeface = if (emphasized) figtreeBold else figtreeRegular
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

    /**
     * One classic share-card preset, restored from the pre-liquid-glass share
     * popup: surface tint + fill alpha, inks, overlay wash and the background
     * dim each style carried. Values are the ones those styles always had.
     */
    private class ClassicStyle(
        val surfaceTint: Int,
        val surfaceAlpha: Float,
        val textColor: Int,
        val secondaryTextColor: Int,
        val overlayColor: Int,
        val overlayAlpha: Float,
        val isDark: Boolean,
        val backgroundDimAlpha: Float,
    )

    private fun classicStyleFor(style: LyricsShareStyle): ClassicStyle? =
        when (style) {
            LyricsShareStyle.FROSTED_DARK ->
                ClassicStyle(
                    surfaceTint = 0xFF000000.toInt(), surfaceAlpha = 0.35f,
                    textColor = 0xFFFFFFFF.toInt(), secondaryTextColor = 0xB3FFFFFF.toInt(),
                    overlayColor = 0xFF000000.toInt(), overlayAlpha = 0.25f,
                    isDark = true, backgroundDimAlpha = 0.35f,
                )
            LyricsShareStyle.FROSTED_LIGHT ->
                ClassicStyle(
                    surfaceTint = 0xFFFFFFFF.toInt(), surfaceAlpha = 0.45f,
                    textColor = 0xFF1A1A1A.toInt(), secondaryTextColor = 0xA61A1A1A.toInt(),
                    overlayColor = 0xFFFFFFFF.toInt(), overlayAlpha = 0.35f,
                    isDark = false, backgroundDimAlpha = 0.15f,
                )
            LyricsShareStyle.CLEAR_GLASS ->
                ClassicStyle(
                    surfaceTint = 0xFFFFFFFF.toInt(), surfaceAlpha = 0.15f,
                    textColor = 0xFFFFFFFF.toInt(), secondaryTextColor = 0xBFFFFFFF.toInt(),
                    overlayColor = 0xFFFFFFFF.toInt(), overlayAlpha = 0.08f,
                    isDark = true, backgroundDimAlpha = 0.2f,
                )
            LyricsShareStyle.DEEP_BLUR ->
                ClassicStyle(
                    surfaceTint = 0xFF0A0A14.toInt(), surfaceAlpha = 0.55f,
                    textColor = 0xFFFFFFFF.toInt(), secondaryTextColor = 0x99FFFFFF.toInt(),
                    overlayColor = 0xFF0A0A14.toInt(), overlayAlpha = 0.4f,
                    isDark = true, backgroundDimAlpha = 0.5f,
                )
            LyricsShareStyle.VIVID_GLOW ->
                ClassicStyle(
                    surfaceTint = 0xFFFF6B9D.toInt(), surfaceAlpha = 0.2f,
                    textColor = 0xFFFFFFFF.toInt(), secondaryTextColor = 0xCCFFFFFF.toInt(),
                    overlayColor = 0xFFFF6B9D.toInt(), overlayAlpha = 0.12f,
                    isDark = true, backgroundDimAlpha = 0.25f,
                )
            LyricsShareStyle.LIQUID_GLASS -> null
        }

    /**
     * The classic preset card renderer, restored: blurred artwork background,
     * a dim wash, one rounded frosted-glass panel with the preset's tint and
     * overlay, artwork-or-centred header, auto-fitted centered lyrics, and the
     * app-signature footer.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun createClassicLyricsImage(
        context: Context,
        coverArtUrl: String?,
        songTitle: String,
        artistName: String,
        lyrics: String,
        width: Int,
        height: Int,
        textColor: Int?,
        shareOptions: LyricsShareImageOptions,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val style = classicStyleFor(shareOptions.style) ?: return@withContext createLyricsImage(
                context = context,
                coverArtUrl = coverArtUrl,
                songTitle = songTitle,
                artistName = artistName,
                lyrics = lyrics,
                width = width,
                height = height,
                textColor = textColor,
                shareOptions = shareOptions,
            )
            val canvasWidth = width.coerceAtLeast(1)
            val canvasHeight = height.coerceAtLeast(1)
            val baseSize = minOf(canvasWidth, canvasHeight).toFloat()
            val bitmap = createBitmap(canvasWidth, canvasHeight)
            val canvas = Canvas(bitmap)

            val mainTextColor = textColor ?: style.textColor
            val secondaryTxtColor = textColor?.let { (it and 0x00FFFFFF) or (0xBF shl 24) } ?: style.secondaryTextColor
            val bgColor = 0xFF121212.toInt()

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

            val fittedArt =
                coverArtBitmap?.let {
                    coverBitmap(
                        source = it,
                        targetWidth = canvasWidth,
                        targetHeight = canvasHeight,
                    )
                }

            if (fittedArt != null) {
                val blurPx = shareOptions.sanitizedBlurRadius.roundToInt().coerceIn(1, 48)
                val blurredBackground = stackBlur(fittedArt, blurPx)
                canvas.drawBitmap(blurredBackground, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawColor(bgColor)
            }

            // The liquid-glass slider runs 0..1 with 0.45 as its neutral
            // point; the classic presets were authored against a 1.0 neutral,
            // so the slider scales around that.
            val dimScale = if (shareOptions.sanitizedDimAmount <= 0f) 0f else shareOptions.sanitizedDimAmount / 0.45f
            val dimPaint =
                Paint().apply {
                    color =
                        ((style.backgroundDimAlpha * dimScale).coerceIn(0f, 0.95f) * 255f).toInt().let { alpha ->
                            (alpha shl 24) or (if (style.isDark) 0x000000 else 0xFFFFFF)
                        }
                    isAntiAlias = true
                }
            canvas.drawRect(RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), dimPaint)

            val glassMargin = baseSize * 0.045f
            val glassLeft = glassMargin
            val glassTop = glassMargin
            val glassRight = canvasWidth - glassMargin
            val glassBottom = canvasHeight - glassMargin
            val glassWidth = glassRight - glassLeft
            val glassHeight = glassBottom - glassTop
            val glassCornerRadius = baseSize * 0.05f

            val glassRect = RectF(glassLeft, glassTop, glassRight, glassBottom)
            val glassPath =
                Path().apply {
                    addRoundRect(glassRect, glassCornerRadius, glassCornerRadius, Path.Direction.CW)
                }

            if (fittedArt != null) {
                val frostedPx = (shareOptions.sanitizedBlurRadius + 10f).coerceIn(8f, 48f).roundToInt()
                val frostedCrop = stackBlur(fittedArt, frostedPx)
                canvas.withClip(glassPath) {
                    drawBitmap(frostedCrop, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }

            fun argb(alpha: Float, color: Int): Int =
                ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

            val glassBgPaint =
                Paint().apply {
                    color = argb(style.surfaceAlpha, style.surfaceTint)
                    isAntiAlias = true
                }
            canvas.drawRoundRect(glassRect, glassCornerRadius, glassCornerRadius, glassBgPaint)

            val overlayPaint =
                Paint().apply {
                    color = argb(style.overlayAlpha, style.overlayColor)
                    isAntiAlias = true
                }
            canvas.drawRoundRect(glassRect, glassCornerRadius, glassCornerRadius, overlayPaint)

            val borderPaint =
                Paint().apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                    color = android.graphics.Color.argb(25, 255, 255, 255)
                    isAntiAlias = true
                }
            canvas.drawRoundRect(glassRect, glassCornerRadius, glassCornerRadius, borderPaint)

            val contentPadding = minOf(glassWidth, glassHeight) * 0.08f
            val contentLeft = glassLeft + contentPadding
            val contentTop = glassTop + contentPadding
            val contentRight = glassRight - contentPadding

            val imageCornerRadius = baseSize * 0.035f
            val coverSize = minOf(glassWidth * 0.18f, glassHeight * 0.15f)
            val topRowGap = baseSize * 0.035f

            val titlePaint =
                TextPaint().apply {
                    color = mainTextColor
                    textSize = baseSize * 0.038f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                    letterSpacing = -0.02f
                }
            val artistPaint =
                TextPaint().apply {
                    color = secondaryTxtColor
                    textSize = baseSize * 0.028f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    isAntiAlias = true
                }

            val showingArtwork = shareOptions.showArtwork && coverArtBitmap != null
            if (showingArtwork) {
                val rect = RectF(contentLeft, contentTop, contentLeft + coverSize, contentTop + coverSize)
                val path =
                    Path().apply {
                        addRoundRect(rect, imageCornerRadius, imageCornerRadius, Path.Direction.CW)
                    }
                canvas.withClip(path) {
                    drawBitmap(coverArtBitmap ?: return@withClip, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val artBorderPaint =
                    Paint().apply {
                        this.style = Paint.Style.STROKE
                        strokeWidth = 1f
                        color = android.graphics.Color.argb(38, 255, 255, 255)
                        isAntiAlias = true
                    }
                canvas.drawRoundRect(rect, imageCornerRadius, imageCornerRadius, artBorderPaint)
            }

            val textMaxWidth =
                if (showingArtwork) {
                    (contentRight - contentLeft - coverSize - topRowGap).toInt()
                } else {
                    (contentRight - contentLeft).toInt()
                }
            val textStartX = if (showingArtwork) contentLeft + coverSize + topRowGap else contentLeft
            val headerAlignment = if (showingArtwork) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER

            val titleLayout =
                StaticLayout.Builder
                    .obtain(songTitle, 0, songTitle.length, titlePaint, textMaxWidth)
                    .setAlignment(headerAlignment)
                    .setMaxLines(1)
                    .build()
            val artistLayout =
                StaticLayout.Builder
                    .obtain(artistName, 0, artistName.length, artistPaint, textMaxWidth)
                    .setAlignment(headerAlignment)
                    .setMaxLines(1)
                    .build()

            val topBlockHeight = if (showingArtwork) coverSize else (titleLayout.height + artistLayout.height + 6f)
            val imageCenter = contentTop + topBlockHeight / 2f
            val textBlockHeight = titleLayout.height + artistLayout.height + 6f
            val textBlockY = imageCenter - textBlockHeight / 2f

            canvas.withTranslation(textStartX, textBlockY) {
                titleLayout.draw(this)
                translate(0f, titleLayout.height.toFloat() + 6f)
                artistLayout.draw(this)
            }

            val lyricsPaint =
                TextPaint().apply {
                    color = mainTextColor
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                    letterSpacing = -0.01f
                }

            val lyricsMaxWidth = (glassWidth * 0.85f).toInt()
            val logoBlockHeight = (baseSize * 0.08f).toInt()
            val headerBottom = if (showingArtwork) contentTop + coverSize else (textBlockY + textBlockHeight)
            val lyricsTop = headerBottom + baseSize * 0.045f
            val lyricsBottom = glassBottom - (logoBlockHeight + contentPadding)
            val availableLyricsHeight = lyricsBottom - lyricsTop

            var lyricsTextSize = baseSize * 0.055f
            var lyricsLayout: StaticLayout
            do {
                lyricsPaint.textSize = lyricsTextSize
                lyricsLayout =
                    StaticLayout.Builder
                        .obtain(
                            lyrics,
                            0,
                            lyrics.length,
                            lyricsPaint,
                            lyricsMaxWidth,
                        ).setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .setLineSpacing(8f, 1.35f)
                        .setMaxLines(10)
                        .build()
                if (lyricsLayout.height > availableLyricsHeight) {
                    lyricsTextSize -= 2f
                } else {
                    break
                }
            } while (lyricsTextSize > 22f)

            val lyricsYOffset = lyricsTop + (availableLyricsHeight - lyricsLayout.height) / 2f
            canvas.withTranslation(glassLeft + (glassWidth - lyricsMaxWidth) / 2f, lyricsYOffset) {
                lyricsLayout.draw(this)
            }

            // Footer signature: a small tinted disc with the note glyph and the
            // app name, centred on the card's bottom band.
            val footerPaint =
                TextPaint().apply {
                    color = secondaryTxtColor
                    textSize = baseSize * 0.024f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                    letterSpacing = 0.04f
                }
            val footerText = "ARCHIVETUNE"
            val footerTextWidth = footerPaint.measureText(footerText)
            val discRadius = baseSize * 0.028f
            val footerGap = baseSize * 0.018f
            val footerCenterY = glassBottom - contentPadding - logoBlockHeight / 2f
            val footerTotalWidth = discRadius * 2f + footerGap + footerTextWidth
            val footerStartX = glassLeft + (glassWidth - footerTotalWidth) / 2f
            val discPaint =
                Paint().apply {
                    color = argb(0.55f, style.overlayColor)
                    isAntiAlias = true
                }
            canvas.drawCircle(footerStartX + discRadius, footerCenterY, discRadius, discPaint)
            val notePaint =
                Paint().apply {
                    color = if (style.isDark) 0xDD000000.toInt() else 0xE6FFFFFF.toInt()
                    isAntiAlias = true
                    textSize = discRadius * 1.1f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
            canvas.drawText("\u266A", footerStartX + discRadius, footerCenterY + discRadius * 0.4f, notePaint)
            val fm = footerPaint.fontMetrics
            canvas.drawText(
                footerText,
                footerStartX + discRadius * 2f + footerGap,
                footerCenterY - (fm.ascent + fm.descent) / 2f,
                footerPaint,
            )

            return@withContext bitmap
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

    // ------------------------------------------------------------------
    // Liquid-glass engine for the lyrics share card
    // ------------------------------------------------------------------

    /** The share card's exclusive typeface set: Figtree, the closest open
     * match to the reference image's Circular — deliberately outside the
     * app's Compose font system so the card typography can never drift. */
    private enum class ShareCardFontWeight {
        REGULAR,
        MEDIUM,
        BOLD,
    }

    private fun shareCardTypeface(context: Context, weight: ShareCardFontWeight): Typeface =
        runCatching {
            ResourcesCompat.getFont(
                context,
                when (weight) {
                    ShareCardFontWeight.REGULAR -> R.font.sharecard_figtree_regular
                    ShareCardFontWeight.MEDIUM -> R.font.sharecard_figtree_medium
                    ShareCardFontWeight.BOLD -> R.font.sharecard_figtree_bold
                },
            )
        }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    /** Working resolution for the displacement passes: the source is heavily
     * blurred, so sub-1024 computation is visually identical and ~10x faster. */
    private const val LIQUID_WORKING_DIM = 1024

    private class LiquidGlassLayers(
        val ambient: Bitmap,
        val glass: Bitmap,
    )

    /**
     * Builds both glass layers from the artwork:
     *  - [LiquidGlassLayers.ambient]: frosted + liquid-warped base for the
     *    full-bleed background;
     *  - [LiquidGlassLayers.glass]: the card's own slab — extra frost, a
     *    stronger liquid warp, rim refraction that bends the surrounding
     *    artwork inward, plus the brightness/saturation lift and frost grain
     *    that sell the "liquid glass" material.
     */
    private fun renderLiquidGlassLayers(
        cover: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int,
        cardRect: RectF,
        options: LyricsShareImageOptions,
        referenceScale: Float,
    ): LiquidGlassLayers {
        val safeCover = ensureSoftwareBitmap(cover)
        val covered = coverBitmap(safeCover, canvasWidth, canvasHeight)
        val workScale = min(1f, LIQUID_WORKING_DIM.toFloat() / max(covered.width, covered.height))
        val working =
            if (workScale < 1f) {
                ensureSoftwareBitmap(
                    Bitmap.createScaledBitmap(
                        covered,
                        (covered.width * workScale).roundToInt().coerceAtLeast(1),
                        (covered.height * workScale).roundToInt().coerceAtLeast(1),
                        true,
                    ),
                )
            } else {
                ensureSoftwareBitmap(covered.copy(Bitmap.Config.ARGB_8888, true))
            }

        val blurPx =
            (options.sanitizedBlurRadius.coerceAtLeast(14f) * referenceScale * workScale)
                .roundToInt().coerceAtLeast(1)
        val frosted = stackBlur(working, blurPx)

        // Ambient background: liquid warp of the frosted base, upscaled back.
        val ambientWork = applyLiquidWarp(frosted, options.sanitizedLiquidyAmount)
        val ambient =
            ensureSoftwareBitmap(
                Bitmap.createScaledBitmap(ambientWork, canvasWidth, canvasHeight, true),
            )

        // Glass slab: warp the SHARPER base first so the liquid survives the
        // frost, then frost it; the rim samples the sharper warped layer so
        // the refraction lens stays legible.
        val sharpish =
            stackBlur(
                ensureSoftwareBitmap(working.copy(Bitmap.Config.ARGB_8888, true)),
                (blurPx * 0.45f).roundToInt().coerceAtLeast(1),
            )
        val warped = applyLiquidWarp(sharpish, options.sanitizedLiquidyAmount)
        val glassFrost =
            stackBlur(
                ensureSoftwareBitmap(warped.copy(Bitmap.Config.ARGB_8888, true)),
                (blurPx * 1.25f).roundToInt().coerceAtLeast(1),
            )
        val cardRectInLayer =
            RectF(
                cardRect.left * workScale,
                cardRect.top * workScale,
                cardRect.right * workScale,
                cardRect.bottom * workScale,
            )
        var glassWork = blendRefractionRim(glassFrost, warped, cardRectInLayer, options.sanitizedRefractionAmount)
        glassWork = applyGlassFinish(glassWork)
        val glass =
            ensureSoftwareBitmap(
                Bitmap.createScaledBitmap(glassWork, canvasWidth, canvasHeight, true),
            )

        return LiquidGlassLayers(ambient = ambient, glass = glass)
    }

    /** Sinusoidal liquid displacement: two overlapping waves whose amplitude
     * scales with [amount] (0..1) — the "liquidy" slider. The amplitude is
     * deliberately large relative to the frost radius so the flow survives
     * the blur and stays visible in the final render. */
    private fun applyLiquidWarp(
        source: Bitmap,
        amount: Float,
    ): Bitmap {
        if (amount <= 0.02f) return source
        val safe = ensureSoftwareBitmap(source)
        val w = safe.width
        val h = safe.height
        if (w < 8 || h < 8) return safe
        val src = IntArray(w * h)
        safe.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        val amp = amount * 0.16f * minOf(w, h)
        val fy = (2.0 * Math.PI / h).toFloat()
        val fx = (2.6 * Math.PI / w).toFloat()
        for (y in 0 until h) {
            val dy = (sin(y * fy) * amp).toInt()
            val row = (y + dy).coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                val dx = (sin(x * fx + y * 0.011f) * amp * 0.72f).toInt()
                dst[y * w + x] = src[row + (x + dx).coerceIn(0, w - 1)]
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Rim refraction — the lens at the glass edge. Within a band along the
     * card's border, samples displaced radially outward are taken from the
     * SHARPER warped layer (content just beyond the glass, bent inward the
     * way a thick glass slab refracts the scene behind it) and blended over
     * the frost with a quadratic falloff. [cardRect] is expressed in the
     * working layer's coordinates.
     */
    private fun blendRefractionRim(
        frost: Bitmap,
        warped: Bitmap,
        cardRect: RectF,
        amount: Float,
    ): Bitmap {
        if (amount <= 0.02f) return frost
        val w = frost.width
        val h = frost.height
        if (w < 8 || h < 8 || warped.width != w || warped.height != h) return frost
        val frostPixels = IntArray(w * h)
        frost.getPixels(frostPixels, 0, w, 0, 0, w, h)
        val warpedPixels = IntArray(w * h)
        warped.getPixels(warpedPixels, 0, w, 0, 0, w, h)
        val dst = frostPixels.copyOf()

        val cx = cardRect.centerX()
        val cy = cardRect.centerY()
        val halfW = cardRect.width() / 2f
        val halfH = cardRect.height() / 2f
        val bandX = halfW * 0.20f
        val bandY = halfH * 0.20f
        val strengthX = bandX * 1.1f * amount
        val strengthY = bandY * 1.1f * amount

        val yStart = cardRect.top.toInt().coerceIn(0, h - 1)
        val yEnd = cardRect.bottom.toInt().coerceIn(0, h - 1)
        val xStart = cardRect.left.toInt().coerceIn(0, w - 1)
        val xEnd = cardRect.right.toInt().coerceIn(0, w - 1)

        for (y in yStart..yEnd) {
            val offY = y - cy
            val ey = halfH - abs(offY)
            val weightY =
                if (ey < bandY) {
                    val t = 1f - (ey / bandY).coerceIn(0f, 1f)
                    t * t
                } else {
                    0f
                }
            val lensY = weightY * strengthY * sign(offY.toFloat())
            val sy = (y + lensY).roundToInt().coerceIn(0, h - 1)
            for (x in xStart..xEnd) {
                val offX = x - cx
                val ex = halfW - abs(offX)
                val weightX =
                    if (ex < bandX) {
                        val t = 1f - (ex / bandX).coerceIn(0f, 1f)
                        t * t
                    } else {
                        0f
                    }
                val weight = (weightX + weightY).coerceAtMost(1f)
                if (weight <= 0f) continue
                val lensX = weightX * strengthX * sign(offX.toFloat())
                val sx = (x + lensX).roundToInt().coerceIn(0, w - 1)
                val frostP = frostPixels[y * w + x]
                val warpedP = warpedPixels[sy * w + sx]
                val keep = 1f - weight
                val a = ((frostP ushr 24) * keep + (warpedP ushr 24) * weight).toInt().coerceIn(0, 255)
                val r = ((frostP shr 16 and 0xFF) * keep + (warpedP shr 16 and 0xFF) * weight).toInt().coerceIn(0, 255)
                val g = ((frostP shr 8 and 0xFF) * keep + (warpedP shr 8 and 0xFF) * weight).toInt().coerceIn(0, 255)
                val b = ((frostP and 0xFF) * keep + (warpedP and 0xFF) * weight).toInt().coerceIn(0, 255)
                dst[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    /** The glass material's finish: gentle brightness + saturation lift and
     * a deterministic per-pixel frost grain. */
    private fun applyGlassFinish(source: Bitmap): Bitmap {
        val safe = ensureSoftwareBitmap(source)
        val out = Bitmap.createBitmap(safe.width, safe.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bright =
            ColorMatrix(
                floatArrayOf(
                    1.14f, 0f, 0f, 0f, -18f,
                    0f, 1.14f, 0f, 0f, -18f,
                    0f, 0f, 1.16f, 0f, -21f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        val saturation = ColorMatrix().apply { setSaturation(1.32f) }
        bright.postConcat(saturation)
        canvas.drawBitmap(safe, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(bright) })

        // Frost grain: hash-based deterministic noise, +-3 luminance levels.
        val w = out.width
        val h = out.height
        if (w >= 8 && h >= 8) {
            val pixels = IntArray(w * h)
            out.getPixels(pixels, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    if (p ushr 24 == 0) continue
                    val noise = (((x * 73856093) xor (y * 19349663)) and 7) - 3
                    val r = ((p shr 16 and 0xFF) + noise).coerceIn(0, 255)
                    val g = ((p shr 8 and 0xFF) + noise).coerceIn(0, 255)
                    val b = ((p and 0xFF) + noise).coerceIn(0, 255)
                    pixels[y * w + x] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
        }
        return out
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
