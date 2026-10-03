/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * In-place iterative radix-2 FFT. Size must be a power of two. Used by the
 * automix onset detector (spectral flux) - small sizes (<=2048) keep the
 * analysis fast enough to run per chunk on a background dispatcher.
 */
internal class Fft(private val size: Int) {
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val reverse = IntArray(size)

    init {
        require(size and (size - 1) == 0) { "FFT size must be a power of two" }
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        var bits = 0
        while (1 shl bits < size) bits++
        for (i in 0 until size) {
            var r = 0
            for (b in 0 until bits) {
                if (i shr b and 1 == 1) r = r or (1 shl (bits - 1 - b))
            }
            reverse[i] = r
        }
    }

    /**
     * Transforms [real]/[imag] in place; magnitudes are written into [mag]
     * (first size/2 bins) when it is non-null.
     */
    fun magnitudeSpectrum(
        real: FloatArray,
        imag: FloatArray,
        mag: FloatArray?,
    ) {
        for (i in 0 until size) {
            val j = reverse[i]
            if (j > i) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = half * 2
            var k = 0
            while (k < size) {
                for (i in 0 until half) {
                    val c = cosTable[i * (size / step)]
                    val s = sinTable[i * (size / step)]
                    val evenR = real[k + i]
                    val evenI = imag[k + i]
                    val oddR = real[k + i + half] * c - imag[k + i + half] * s
                    val oddI = real[k + i + half] * s + imag[k + i + half] * c
                    real[k + i] = evenR + oddR
                    imag[k + i] = evenI + oddI
                    real[k + i + half] = evenR - oddR
                    imag[k + i + half] = evenI - oddI
                }
                k += step
            }
            half = step
        }
        if (mag != null) {
            val bins = size / 2
            for (i in 0 until bins) {
                mag[i] = kotlin.math.sqrt(real[i] * real[i] + imag[i] * imag[i])
            }
        }
    }
}

/** Hann window coefficients for the given FFT size. */
internal fun hannWindow(size: Int): FloatArray =
    FloatArray(size) { i ->
        0.5f * (1f - cos(2.0 * PI * i / size).toFloat())
    }

/** Mean of the middle 50% of the sorted values - outlier-proof loudness level. */
internal fun robustMean(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val from = sorted.size / 4
    val to = sorted.size - sorted.size / 4
    if (to <= from) return sorted[sorted.size / 2]
    var sum = 0f
    for (i in from until to) sum += sorted[i]
    return sum / (to - from)
}

internal fun glideExp(
    from: Double,
    to: Double,
    amount: Double,
): Double {
    if (from <= 0.0 || to <= 0.0 || amount <= 0.0) return from
    return from * (to / from).pow(amount.coerceIn(0.0, 1.0))
}
