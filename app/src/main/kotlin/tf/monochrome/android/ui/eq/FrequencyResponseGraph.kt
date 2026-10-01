package tf.monochrome.android.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import tf.monochrome.android.audio.eq.AutoEqEngine
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

private const val MIN_FREQ = 20f
private const val MAX_FREQ = 20000f
private const val DB_RANGE = 40f
private const val GRAPH_PADDING_LEFT = 0f
private const val GRAPH_PADDING_RIGHT = 40f
private const val GRAPH_PADDING_TOP = 12f
private const val GRAPH_PADDING_BOTTOM = 20f

@Composable
fun FrequencyResponseGraph(
    originalCurve: List<FrequencyPoint>,
    targetCurve: List<FrequencyPoint>,
    eqBands: List<EqBand>,
    modifier: Modifier = Modifier,
    preamp: Float = 0f,
    sampleRate: Float = 48000f,
    onBandDragged: ((bandId: Int, newFreq: Float, newGain: Float) -> Unit)? = null,
    spectrumBins: FloatArray = FloatArray(0),
    spectrumColor: Color? = null,
    centerOnZero: Boolean = false,
    showLegend: Boolean = true,

    maxAbsDragGain: Float = EqLimits.AUTOEQ_MAX_BAND_DB,

    secondaryMeasurement: List<FrequencyPoint> = emptyList(),
    secondaryBands: List<EqBand>? = null,

    primaryIsRight: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary

    val zeroOffset = remember(originalCurve, targetCurve, centerOnZero) {
        when {
            centerOnZero -> 0f
            originalCurve.isNotEmpty() -> getNormalizationOffset(originalCurve)
            targetCurve.isNotEmpty() -> getNormalizationOffset(targetCurve)
            else -> 75f
        }
    }

    val minGain = zeroOffset - (DB_RANGE / 2f)
    val maxGain = zeroOffset + (DB_RANGE / 2f)

    val targetNormOffset = remember(originalCurve, targetCurve) {
        if (originalCurve.isNotEmpty() && targetCurve.isNotEmpty()) {
            val measNorm = getNormalizationOffset(originalCurve)
            val targetNorm = getNormalizationOffset(targetCurve)
            measNorm - targetNorm
        } else 0f
    }

    val normalizedTarget = remember(targetCurve, targetNormOffset) {
        if (targetNormOffset != 0f) {
            targetCurve.map { FrequencyPoint(it.freq, it.gain + targetNormOffset) }
        } else targetCurve
    }

    val gridFreqs = remember(originalCurve) { buildGrid(originalCurve) }
    val grid = remember(gridFreqs, sampleRate) { AutoEqEngine.ResponseGrid(gridFreqs, sampleRate) }

    val bandResponses = remember(grid, eqBands) {
        eqBands.filter { it.enabled }.map { it to grid.response(it) }
    }
    val eqSum = remember(bandResponses, grid) {
        FloatArray(grid.size).also { out ->
            for ((_, response) in bandResponses) {
                for (i in out.indices) out[i] += response[i]
            }
        }
    }

    val correctedCurve = remember(gridFreqs, originalCurve, eqSum, preamp, zeroOffset) {
        val hasMeasurement = originalCurve.isNotEmpty()
        List(gridFreqs.size) { i ->
            val base = if (hasMeasurement) originalCurve[i].gain else zeroOffset
            FrequencyPoint(gridFreqs[i], base + preamp + eqSum[i])
        }.filter { it.gain.isFinite() }
    }

    val secondaryFreqs = remember(secondaryMeasurement, gridFreqs) {
        if (secondaryMeasurement.isNotEmpty()) {
            FloatArray(secondaryMeasurement.size) { secondaryMeasurement[it].freq }
        } else {
            gridFreqs
        }
    }
    val secondaryGrid = remember(secondaryFreqs, sampleRate, grid) {
        if (secondaryFreqs === gridFreqs) grid else AutoEqEngine.ResponseGrid(secondaryFreqs, sampleRate)
    }
    val secondaryCorrected = remember(
        secondaryGrid, secondaryFreqs, secondaryBands, secondaryMeasurement, originalCurve,
        preamp, zeroOffset,
    ) {
        val bands = secondaryBands
        val hasBase = secondaryMeasurement.isNotEmpty() || originalCurve.isNotEmpty()
        if (bands == null || (!hasBase && bands.isEmpty())) {
            emptyList()
        } else {
            val baseCurve = secondaryMeasurement.ifEmpty { originalCurve }
            val sum = secondaryGrid.sum(bands)
            List(secondaryFreqs.size) { i ->
                val base = if (baseCurve.isNotEmpty()) baseCurve[i].gain else zeroOffset
                FrequencyPoint(secondaryFreqs[i], base + preamp + sum[i])
            }.filter { it.gain.isFinite() }
        }
    }

    var selectedBandId by remember { mutableIntStateOf(-1) }
    var isDragging by remember { mutableStateOf(false) }

    var hiddenCurves by remember { mutableStateOf(setOf<String>()) }
    val eqDotsHidden by rememberUpdatedState("eqL" in hiddenCurves)

    val latestEqBands by rememberUpdatedState(eqBands)
    val latestCorrected by rememberUpdatedState(correctedCurve)
    val latestPreamp by rememberUpdatedState(preamp)
    val latestSampleRate by rememberUpdatedState(sampleRate)
    val latestMinGain by rememberUpdatedState(minGain)
    val latestMaxGain by rememberUpdatedState(maxGain)
    val latestZeroOffset by rememberUpdatedState(zeroOffset)
    val latestMaxAbsDragGain by rememberUpdatedState(maxAbsDragGain)
    val latestOnBandDragged by rememberUpdatedState(onBandDragged)

    val bandContributions = remember(bandResponses, gridFreqs, zeroOffset) {
        bandResponses.filter { (band, _) -> band.gain != 0f }.map { (band, response) ->
            band to List(response.size) { i -> FrequencyPoint(gridFreqs[i], zeroOffset + response[i]) }
        }
    }

    val graphBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
    val legendBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val legendLabelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val curveNeutral = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(graphBackground)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .pointerInput(Unit) {
                    if (onBandDragged == null) return@pointerInput
                    detectTapGestures { offset ->
                        if (eqDotsHidden) return@detectTapGestures
                        val tapped = findNearestBand(
                            offset, latestEqBands, latestCorrected, latestPreamp, latestSampleRate,
                            size.width.toFloat(), size.height.toFloat(),
                            latestMinGain, latestMaxGain, latestZeroOffset
                        )
                        selectedBandId = if (tapped == selectedBandId) -1 else tapped
                    }
                }
                .pointerInput(Unit) {
                    if (onBandDragged == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        if (eqDotsHidden) return@awaitEachGesture

                        val bandId = findNearestBand(
                            down.position, latestEqBands, latestCorrected,
                            latestPreamp, latestSampleRate,
                            size.width.toFloat(), size.height.toFloat(),
                            latestMinGain, latestMaxGain, latestZeroOffset
                        )
                        if (bandId < 0) return@awaitEachGesture

                        val touchSlop = viewConfiguration.touchSlop
                        var started = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val pos = change.position
                            if (!started) {

                                if ((pos - down.position).getDistance() < touchSlop) continue
                                started = true
                                selectedBandId = bandId
                                isDragging = true
                            }
                            change.consume()
                            val freq = xToFreq(pos.x, size.width.toFloat())
                            val gain = yToGain(
                                pos.y, size.height.toFloat(),
                                latestMinGain, latestMaxGain, latestZeroOffset, latestMaxAbsDragGain
                            )
                            latestOnBandDragged?.invoke(bandId, freq, gain)
                        }
                        isDragging = false
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            drawGrid(w, h, minGain, maxGain, zeroOffset)

            drawDbLabels(w, h, minGain, maxGain, zeroOffset)

            drawFreqLabels(w, h)

            if (spectrumBins.isNotEmpty() && spectrumColor != null) {
                drawSpectrum(
                    bins = spectrumBins,
                    color = spectrumColor,
                    width = w,
                    height = h,
                    minGain = minGain,
                    maxGain = maxGain,
                    zeroOffset = zeroOffset
                )
            }

            if (originalCurve.size > 1 && normalizedTarget.size > 1 &&
                "measL" !in hiddenCurves && "target" !in hiddenCurves
            ) {
                drawCurveGap(originalCurve, normalizedTarget, primary.copy(alpha = 0.08f), w, h, minGain, maxGain)
            }

            if (originalCurve.size > 1 && "measL" !in hiddenCurves) {
                drawCurve(originalCurve, Color(0xFF4A9EFF).copy(alpha = 0.8f), w, h, minGain, maxGain, 3f)
            }

            if (normalizedTarget.size > 1 && "target" !in hiddenCurves) {
                drawDashedCurve(normalizedTarget, primary.copy(alpha = 0.85f), w, h, minGain, maxGain, 3f)
            }

            if (secondaryMeasurement.size > 1 && "measR" !in hiddenCurves) {
                drawCurve(secondaryMeasurement, Color(0xFF4A9EFF).copy(alpha = 0.35f), w, h, minGain, maxGain, 2.5f)
            }
            if (secondaryCorrected.size > 1 && "eqR" !in hiddenCurves) {
                drawCurve(secondaryCorrected, Color(0xFFFFB300), w, h, minGain, maxGain, 3.5f)
            }

            if ("eqL" !in hiddenCurves) {
                for ((band, pts) in bandContributions) {
                    val t = (ln(band.freq.coerceAtLeast(20f) / 20f) / ln(1000f)).coerceIn(0f, 1f)
                    val hue = 60f + 240f * t
                    val selected = selectedBandId == band.id
                    drawCurve(
                        pts,
                        Color.hsv(hue, 0.85f, 1f).copy(alpha = if (selected) 0.95f else 0.5f),
                        w, h, minGain, maxGain,
                        if (selected) 2.5f else 1.5f
                    )
                }
            }

            if (correctedCurve.size > 1 && "eqL" !in hiddenCurves) {
                drawFilledCurve(correctedCurve, Color(0xFFFF4444), w, h, minGain, maxGain, zeroOffset, 4f)
            }

            val compactDots = eqBands.count { it.enabled } > 10
            if ("eqL" !in hiddenCurves) eqBands.forEach { band ->
                if (!band.enabled) return@forEach

                val dotX = freqToX(band.freq, w)
                val bandGain = bandDotGain(band, correctedCurve, eqBands, preamp, zeroOffset, sampleRate)
                val dotY = gainToY(bandGain, h, minGain, maxGain)

                val isSelected = selectedBandId == band.id

                if (isSelected) {
                    val infoText =
                        "${band.freq.toInt()} Hz  ${"%.1f".format(band.gain)} dB  Q ${"%.2f".format(band.q)}"
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE

                        textSize = 12.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    val textWidth = paint.measureText(infoText)
                    val tooltipPadding = 16f
                    val rectTop = (dotY - 70f).coerceAtLeast(GRAPH_PADDING_TOP)

                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        dotX - textWidth/2 - tooltipPadding, rectTop - 35f,
                        dotX + textWidth/2 + tooltipPadding, rectTop + 10f,
                        12f, 12f,
                        android.graphics.Paint().apply { color = android.graphics.Color.argb(180, 20, 20, 20) }
                    )
                    drawContext.canvas.nativeCanvas.drawText(infoText, dotX, rectTop, paint)
                }

                val bandColor = when {
                    band.gain > 0.25f -> Color(0xFF4CAF50)
                    band.gain < -0.25f -> Color(0xFFE53935)
                    else -> Color(0xFF9E9E9E)
                }

                if (isSelected) {

                    drawLine(
                        color = curveNeutral.copy(alpha = 0.25f),
                        start = Offset(dotX, GRAPH_PADDING_TOP),
                        end = Offset(dotX, h - GRAPH_PADDING_BOTTOM),
                        strokeWidth = 1.5f,
                    )
                    drawCircle(
                        color = bandColor.copy(alpha = 0.4f),
                        radius = 34f,
                        center = Offset(dotX, dotY)
                    )
                }

                val dotRadius = when {
                    isSelected -> 17f
                    compactDots -> 6.5f
                    else -> 15f
                }

                drawCircle(
                    color = Color.Black,
                    radius = dotRadius + 2f,
                    center = Offset(dotX, dotY)
                )

                drawCircle(
                    color = bandColor,
                    radius = dotRadius,
                    center = Offset(dotX, dotY)
                )

                drawCircle(
                    color = curveNeutral,
                    radius = dotRadius,
                    center = Offset(dotX, dotY),
                    style = Stroke(
                        width = when {
                            isSelected -> 3f
                            compactDots -> 1.5f
                            else -> 2.5f
                        }
                    )
                )

                if (!compactDots || isSelected) {
                    val numPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "${band.id + 1}",
                        dotX,
                        dotY + numPaint.textSize * 0.35f,
                        numPaint,
                    )
                }
            }
        }

        if (showLegend) {
            val stereo = secondaryBands != null

            val p1 = if (primaryIsRight) "R" else "L"
            val p2 = if (primaryIsRight) "L" else "R"
            val entries = buildList {
                add(Triple("measL", if (stereo) "$p1 meas" else "Original", Color(0xFF4A9EFF)))
                add(Triple("target", "Target", primary))
                add(Triple("eqL", if (stereo) "$p1 EQ" else "Corrected", Color(0xFFFF4444)))
                if (stereo) {
                    add(Triple("measR", "$p2 meas", Color(0xFF4A9EFF).copy(alpha = 0.5f)))
                    add(Triple("eqR", "$p2 EQ", Color(0xFFFFB300)))
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(legendBackground)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
            ) {
                entries.forEach { (key, label, color) ->
                    LegendDot(
                        label = label,
                        color = color,
                        labelColor = legendLabelColor,
                        active = key !in hiddenCurves,
                        onClick = {
                            hiddenCurves =
                                if (key in hiddenCurves) hiddenCurves - key
                                else hiddenCurves + key
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(
    label: String,
    color: Color,
    labelColor: Color,
    active: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .let { m -> if (onClick != null) m.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick) else m }
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .graphicsLayer { alpha = if (active) 1f else 0.35f }
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(5.dp))
        )
        Text(
            label,
            fontSize = 10.sp,
            color = labelColor
        )
    }
}

@Composable
fun EqProfileMiniGraph(
    bands: List<EqBand>,
    modifier: Modifier = Modifier,
    preamp: Float = 0f,
    sampleRate: Float = 48000f,

    gainRange: Float = EqLimits.AUTOEQ_MAX_BAND_DB,
) {
    if (bands.isEmpty()) return

    val miniBackground = MaterialTheme.colorScheme.surface
    val zeroLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(miniBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h / 2f
            val yScale = midY / gainRange

            drawLine(
                color = zeroLineColor,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            val freqPoints = buildFreqSamples(MIN_FREQ, MAX_FREQ, 256)

            bands.forEach { band ->
                if (!band.enabled) return@forEach

                val hue = ((log10(band.freq) - log10(MIN_FREQ)) /
                        (log10(MAX_FREQ) - log10(MIN_FREQ)) * 300f).coerceIn(0f, 300f)
                val bandColor = Color.hsl(hue, 0.85f, 0.55f)

                val gainValues = freqPoints.map { freq ->
                    AutoEqEngine.calculateBiquadResponse(freq, band, sampleRate)
                }

                val fillPath = Path()
                val linePath = Path()
                var started = false
                freqPoints.forEachIndexed { i, freq ->
                    val x = freqToX(freq, w)
                    val g = gainValues[i].coerceIn(-gainRange, gainRange)
                    val y = midY - g * yScale
                    if (!started) {
                        fillPath.moveTo(x, midY)
                        fillPath.lineTo(x, y)
                        linePath.moveTo(x, y)
                        started = true
                    } else {
                        fillPath.lineTo(x, y)
                        linePath.lineTo(x, y)
                    }
                }

                val lastX = freqToX(freqPoints.last(), w)
                val firstX = freqToX(freqPoints.first(), w)
                fillPath.lineTo(lastX, midY)
                fillPath.lineTo(firstX, midY)
                fillPath.close()

                drawPath(fillPath, bandColor.copy(alpha = 0.35f))
                drawPath(linePath, bandColor, style = Stroke(width = 1.5f))
            }
        }
    }
}

private fun buildGrid(measurement: List<FrequencyPoint>): FloatArray {
    if (measurement.isNotEmpty()) {
        return FloatArray(measurement.size) { measurement[it].freq }
    }
    val samples = 256
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    return FloatArray(samples) { i ->
        10f.pow(logMin + i.toFloat() / (samples - 1) * (logMax - logMin))
    }
}

private fun buildFreqSamples(minF: Float, maxF: Float, count: Int): List<Float> {
    val logMin = log10(minF)
    val logMax = log10(maxF)
    return (0 until count).map { i ->
        10f.pow(logMin + i.toFloat() / (count - 1) * (logMax - logMin))
    }
}

private fun getNormalizationOffset(data: List<FrequencyPoint>): Float {
    var sum = 0f
    var count = 0
    for (p in data) {
        if (p.freq in 250f..2500f) {
            sum += p.gain
            count++
        }
    }
    return if (count > 0) sum / count else interpolateGain(1000f, data)
}

private fun freqToX(freq: Float, width: Float): Float {
    val logFreq = log10(freq.coerceIn(MIN_FREQ, MAX_FREQ))
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val ratio = (logFreq - logMin) / (logMax - logMin)
    return GRAPH_PADDING_LEFT + ratio * (width - GRAPH_PADDING_LEFT - GRAPH_PADDING_RIGHT)
}

private fun gainToY(gain: Float, height: Float, minGain: Float, maxGain: Float): Float {
    val ratio = (gain - minGain) / (maxGain - minGain)
    return (height - GRAPH_PADDING_BOTTOM) - ratio * (height - GRAPH_PADDING_TOP - GRAPH_PADDING_BOTTOM)
}

private fun xToFreq(x: Float, width: Float): Float {
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val ratio = (x - GRAPH_PADDING_LEFT) / (width - GRAPH_PADDING_LEFT - GRAPH_PADDING_RIGHT)
    val logFreq = logMin + ratio.coerceIn(0f, 1f) * (logMax - logMin)
    return 10f.pow(logFreq).coerceIn(MIN_FREQ, MAX_FREQ)
}

private fun yToGain(
    y: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float,
    maxAbsDragGain: Float = EqLimits.AUTOEQ_MAX_BAND_DB,
): Float {
    val ratio = ((height - GRAPH_PADDING_BOTTOM) - y) / (height - GRAPH_PADDING_TOP - GRAPH_PADDING_BOTTOM)

    return (minGain + ratio.coerceIn(0f, 1f) * (maxGain - minGain) - zeroOffset)
        .coerceIn(-maxAbsDragGain, maxAbsDragGain)
}

private fun bandDotGain(
    band: EqBand,
    correctedCurve: List<FrequencyPoint>,
    bands: List<EqBand>,
    preamp: Float,
    zeroOffset: Float,
    sampleRate: Float
): Float = if (correctedCurve.isNotEmpty()) {
    interpolateGain(band.freq, correctedCurve)
} else {
    var gainAtFreq = preamp + zeroOffset
    bands.forEach { b -> if (b.enabled) gainAtFreq += AutoEqEngine.calculateBiquadResponse(band.freq, b, sampleRate) }
    gainAtFreq
}

private fun findNearestBand(
    position: Offset,
    bands: List<EqBand>,
    correctedCurve: List<FrequencyPoint>,
    preamp: Float,
    sampleRate: Float,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float
): Int {
    val threshold = 50f
    var nearest = -1
    var nearestDist = Float.MAX_VALUE
    bands.forEach { band ->
        if (!band.enabled) return@forEach
        val dotX = freqToX(band.freq, width)
        val dotY = gainToY(
            bandDotGain(band, correctedCurve, bands, preamp, zeroOffset, sampleRate),
            height, minGain, maxGain
        )
        val dist = sqrt((position.x - dotX).pow(2) + (position.y - dotY).pow(2))
        if (dist < threshold && dist < nearestDist) {
            nearest = band.id
            nearestDist = dist
        }
    }
    return nearest
}

private fun interpolateGain(freq: Float, curve: List<FrequencyPoint>): Float {
    if (curve.isEmpty()) return 0f
    if (freq <= curve.first().freq) return curve.first().gain
    if (freq >= curve.last().freq) return curve.last().gain

    var lo = 0
    var hi = curve.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (curve[mid].freq <= freq) lo = mid else hi = mid
    }
    val span = curve[hi].freq - curve[lo].freq
    if (span <= 0f) return curve[lo].gain
    val t = (freq - curve[lo].freq) / span
    return curve[lo].gain + t * (curve[hi].gain - curve[lo].gain)
}

private fun DrawScope.drawGrid(
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float
) {
    val gridColor = Color(0x18FFFFFF)
    val zeroLineColor = Color(0x30FFFFFF)

    val freqs = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
    freqs.forEach { freq ->
        val x = freqToX(freq, width)
        drawLine(gridColor, Offset(x, GRAPH_PADDING_TOP), Offset(x, height - GRAPH_PADDING_BOTTOM), 1f)
    }

    val range = maxGain - minGain
    val step = when {
        range > 60 -> 10f
        range > 30 -> 5f
        else -> 5f
    }

    var rel = kotlin.math.ceil((minGain - zeroOffset) / step) * step
    while (rel + zeroOffset <= maxGain) {
        val y = gainToY(rel + zeroOffset, height, minGain, maxGain)
        if (y in GRAPH_PADDING_TOP..(height - GRAPH_PADDING_BOTTOM)) {
            val isZero = abs(rel) < 0.5f
            drawLine(
                if (isZero) zeroLineColor else gridColor,
                Offset(GRAPH_PADDING_LEFT, y),
                Offset(width - GRAPH_PADDING_RIGHT, y),
                if (isZero) 2f else 1f,
            )
        }
        rel += step
    }
}

private fun DrawScope.drawDbLabels(
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float,
) {
    val range = maxGain - minGain
    val step = when {
        range > 60 -> 10f
        range > 30 -> 5f
        else -> 5f
    }
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(120, 180, 180, 180)
        textSize = 10.sp.toPx()
        textAlign = android.graphics.Paint.Align.LEFT
        isAntiAlias = true
    }

    var rel = kotlin.math.ceil((minGain - zeroOffset) / step) * step
    while (rel + zeroOffset <= maxGain) {
        val g = rel + zeroOffset
        val y = gainToY(g, height, minGain, maxGain)
        if (y in GRAPH_PADDING_TOP..(height - GRAPH_PADDING_BOTTOM)) {
            drawContext.canvas.nativeCanvas.drawText(
                if (rel > 0f) "+${rel.toInt()}" else "${rel.toInt()}",
                width - GRAPH_PADDING_RIGHT + 4f,
                y + 7f,
                paint
            )
        }
        rel += step
    }
}

private fun DrawScope.drawFreqLabels(width: Float, height: Float) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(120, 180, 180, 180)
        textSize = 9.sp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val labels = mapOf(
        20f to "20", 50f to "50", 100f to "100", 200f to "200",
        500f to "500", 1000f to "1k", 2000f to "2k", 5000f to "5k",
        10000f to "10k", 20000f to "20k"
    )
    labels.forEach { (freq, label) ->
        val x = freqToX(freq, width)
        drawContext.canvas.nativeCanvas.drawText(
            label, x, height - 2f, paint
        )
    }
}

private fun DrawScope.drawCurveGap(
    a: List<FrequencyPoint>,
    b: List<FrequencyPoint>,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
) {
    if (a.size < 2 || b.size < 2) return
    val lo = maxOf(a.first().freq, b.first().freq).coerceAtLeast(MIN_FREQ)
    val hi = minOf(a.last().freq, b.last().freq).coerceAtMost(MAX_FREQ)
    if (hi <= lo) return
    val samples = 128
    val logLo = log10(lo)
    val logHi = log10(hi)
    val path = Path()
    for (i in 0..samples) {
        val freq = 10f.pow(logLo + i.toFloat() / samples * (logHi - logLo))
        val x = freqToX(freq, width)
        val y = gainToY(interpolateGain(freq, a), height, minGain, maxGain)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (i in samples downTo 0) {
        val freq = 10f.pow(logLo + i.toFloat() / samples * (logHi - logLo))
        val x = freqToX(freq, width)
        val y = gainToY(interpolateGain(freq, b), height, minGain, maxGain)
        path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawCurve(
    curve: List<FrequencyPoint>,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    strokeWidth: Float
) {
    if (curve.size < 2) return
    val path = Path().apply {
        moveTo(freqToX(curve[0].freq, width), gainToY(curve[0].gain, height, minGain, maxGain))
        for (i in 1 until curve.size) {
            lineTo(freqToX(curve[i].freq, width), gainToY(curve[i].gain, height, minGain, maxGain))
        }
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawDashedCurve(
    curve: List<FrequencyPoint>,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    strokeWidth: Float
) {
    if (curve.size < 2) return
    val path = Path().apply {
        moveTo(freqToX(curve[0].freq, width), gainToY(curve[0].gain, height, minGain, maxGain))
        for (i in 1 until curve.size) {
            lineTo(freqToX(curve[i].freq, width), gainToY(curve[i].gain, height, minGain, maxGain))
        }
    }
    drawPath(
        path, color,
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        )
    )
}

private fun DrawScope.drawSpectrum(
    bins: FloatArray,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float
) {
    if (bins.isEmpty()) return
    val n = bins.size
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val topY = GRAPH_PADDING_TOP
    val bottomY = height - GRAPH_PADDING_BOTTOM

    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        val t = i.toFloat() / (n - 1).toFloat()
        val freq = 10f.pow(logMin + t * (logMax - logMin))
        xs[i] = freqToX(freq, width)
        val binGain = (zeroOffset + bins[i]).coerceIn(minGain, maxGain)
        ys[i] = gainToY(binGain, height, minGain, maxGain).coerceIn(topY, bottomY)
    }

    val envelope = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val p0x = xs[(i - 1).coerceAtLeast(0)]; val p0y = ys[(i - 1).coerceAtLeast(0)]
            val p1x = xs[i]; val p1y = ys[i]
            val p2x = xs[i + 1]; val p2y = ys[i + 1]
            val p3x = xs[(i + 2).coerceAtMost(n - 1)]; val p3y = ys[(i + 2).coerceAtMost(n - 1)]
            val c1x = p1x + (p2x - p0x) / 6f
            val c1y = p1y + (p2y - p0y) / 6f
            val c2x = p2x - (p3x - p1x) / 6f
            val c2y = p2y - (p3y - p1y) / 6f
            cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
        }
    }

    val fill = Path().apply {
        addPath(envelope)
        lineTo(xs[n - 1], bottomY)
        lineTo(xs[0], bottomY)
        close()
    }
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = 0.55f),
                color.copy(alpha = 0.18f),
                color.copy(alpha = 0.04f)
            ),
            startY = topY,
            endY = bottomY
        )
    )

    drawPath(envelope, color.copy(alpha = 0.85f), style = Stroke(width = 1.5f))
}

private fun DrawScope.drawFilledCurve(
    curve: List<FrequencyPoint>,
    lineColor: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float,
    strokeWidth: Float
) {
    if (curve.size < 2) return
    val path = Path().apply {
        moveTo(freqToX(curve[0].freq, width), gainToY(curve[0].gain, height, minGain, maxGain))
        for (i in 1 until curve.size) {
            lineTo(freqToX(curve[i].freq, width), gainToY(curve[i].gain, height, minGain, maxGain))
        }
    }

    val zeroY = gainToY(zeroOffset, height, minGain, maxGain).coerceIn(GRAPH_PADDING_TOP, height - GRAPH_PADDING_BOTTOM)
    val fillPath = Path().apply {
        addPath(path)
        lineTo(freqToX(curve.last().freq, width), zeroY)
        lineTo(freqToX(curve[0].freq, width), zeroY)
        close()
    }

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0.0f)),
            startY = GRAPH_PADDING_TOP,
            endY = zeroY
        )
    )

    drawPath(path, lineColor, style = Stroke(width = strokeWidth))
}
