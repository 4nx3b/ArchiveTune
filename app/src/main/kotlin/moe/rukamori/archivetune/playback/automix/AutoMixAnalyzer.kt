/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Clean-room automix track analyzer (pure Kotlin, coroutine-native).
 *
 * Each queued track is decoded ONCE with MediaCodec straight into streaming
 * feature extractors - the PCM itself is never retained, so memory stays
 * bounded by a few envelope arrays regardless of track length:
 *
 *  - spectral flux (FFT, Hann window) -> onset envelope
 *  - onset autocorrelation            -> tempo, beat grid, confidence, downbeats
 *  - 250 ms RMS windows               -> energy curve, intro/outro structure, mix points
 *  - 300-3400 Hz band ratio           -> vocal-likelihood curve
 *
 * Results are persisted as JSON under filesDir/automix_analysis (LRU-pruned)
 * so repeat plays skip the decode entirely.
 */
class AutoMixAnalyzer(
    context: Context,
    private val resolveStreamUrl: suspend (mediaId: String) -> String?,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val analyses = ConcurrentHashMap<String, AutoMixAnalysis>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val persistenceMutex = Mutex()
    private val storeDir = File(appContext.filesDir, "automix_analysis")

    @Volatile
    var performanceMode: AutoMixPerformanceMode = AutoMixPerformanceMode.BALANCED

    /** Fire-and-forget analysis request; safe to call on any thread. */
    fun request(
        trackId: String,
        uri: Uri,
        durationMs: Long,
    ) {
        if (durationMs <= 0L || trackId.isBlank()) return
        if (analyses.containsKey(trackId) || failed.contains(trackId)) return
        if (!inFlight.add(trackId)) return
        scope.launch {
            try {
                val analysis = analyze(trackId, uri, durationMs)
                if (analysis.isUsable) {
                    analyses[trackId] = analysis
                    persist(trackId, analysis)
                    Log.i(TAG, "analysed $trackId bpm=%.1f conf=%.2f".format(analysis.bpm, analysis.beatConfidence))
                } else {
                    failed.add(trackId)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failed.add(trackId)
                Log.w(TAG, "analysis failed for $trackId: ${t.message}")
            } finally {
                inFlight.remove(trackId)
            }
        }
    }

    fun analysisFor(trackId: String): AutoMixAnalysis? = analyses[trackId]

    fun isAnalysed(trackId: String): Boolean = analyses.containsKey(trackId)

    fun isAnalysing(trackId: String): Boolean = inFlight.contains(trackId)

    /** Drops a track from the failure cache so a later request can retry it. */
    fun reset(trackId: String) {
        failed.remove(trackId)
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun analyze(
        trackId: String,
        uri: Uri,
        durationMs: Long,
    ): AutoMixAnalysis {
        restore(trackId)?.let { return it }
        return withContext(Dispatchers.Default) {
            val extractor = decodeAndExtract(trackId, uri)
                ?: return@withContext AutoMixAnalysis.unusable(durationMs)
            try {
                buildAnalysis(extractor, durationMs)
            } finally {
                extractor.release()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Streaming decode + feature extraction
    // ---------------------------------------------------------------------

    /**
     * Owns the MediaCodec/MediaExtractor pair while feeding the feature
     * pipeline; [release] must be called by the consumer.
     */
    private class DecodedFeatures(
        val sampleRate: Int,
        val fftHop: Int,
        val flux: ArrayList<Float>,
        val energyCurve: ArrayList<Float>,
        val vocalCurve: ArrayList<Float>,
    ) {
        fun release() {}
    }

    private fun decodeAndExtract(
        trackId: String,
        uri: Uri,
    ): DecodedFeatures? {
        val analysisRate = when (performanceMode) {
            AutoMixPerformanceMode.EFFICIENT -> 11_025
            else -> 22_050
        }
        val fftSize = when (performanceMode) {
            AutoMixPerformanceMode.EFFICIENT -> 512
            else -> 1024
        }
        val fftHop = fftSize / 2

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            if (!openDataSource(trackId, uri, extractor)) return null
            val trackIndex = selectAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // --- streaming feature state (all allocation-free per chunk) ---
            val fft = Fft(fftSize)
            val window = hannWindow(fftSize)
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            val mag = FloatArray(fftSize / 2)
            val prevMag = FloatArray(fftSize / 2)
            val overlap = FloatArray(fftHop)
            var windowFill = 0

            val flux = ArrayList<Float>(8192)
            val energyCurve = ArrayList<Float>(2048)
            val vocalCurve = ArrayList<Float>(2048)

            // Nearest/previous-sample resampler (mono, source -> analysis
            // rate): zero-order hold is ample for envelope analysis and keeps
            // the inner loop allocation- and branch-free.
            var resamplePos = 0.0
            var lastSample = 0f

            // 250 ms curve accumulators
            val curveWindowSamples =
                (analysisRate * AUTO_MIX_CURVE_STEP_MS / 1000L).toInt().coerceAtLeast(1)
            var curveSamples = 0
            var squareSum = 0.0
            var bandSum = 0.0
            var totalSum = 0.0
            val vocalLowBin = max(1, (300.0 * fftSize / analysisRate).toInt())
            val vocalHighBin = min(fftSize / 2 - 1, (3400.0 * fftSize / analysisRate).toInt())

            val bufferInfo = MediaCodec.BufferInfo()
            val chunk = ShortArray(4096)
            var inputDone = false
            var outputDone = false
            var idleSpins = 0

            fun pushSample(mono: Float) {
                // --- resample into the analysis rate ---
                while (resamplePos <= 0.0) {
                    val out = lastSample
                    // (first output per input uses the previous input pair)
                    if (resamplePos == 0.0 && windowFill == 0 && flux.isEmpty() && curveSamples == 0) {
                        resamplePos += sourceRate.toDouble() / analysisRate
                        break
                    }
                    // fill the analysis window
                    real[windowFill] = out * window[windowFill]
                    imag[windowFill] = 0f
                    windowFill++
                    if (windowFill == fftSize) {
                        // The in-place transform destroys the windowed input:
                        // save the overlap tail BEFORE transforming, then seed
                        // the next frame with it (imag is always zero input).
                        System.arraycopy(real, fftSize - fftHop, overlap, 0, fftHop)
                        fft.magnitudeSpectrum(real, imag, mag)
                        var fluxSum = 0f
                        var band = 0f
                        var total = 0f
                        for (bin in 0 until fftSize / 2) {
                            val m = mag[bin]
                            val d = m - prevMag[bin]
                            if (d > 0f) fluxSum += d
                            total += m
                            if (bin in vocalLowBin..vocalHighBin) band += m
                        }
                        System.arraycopy(mag, 0, prevMag, 0, mag.size)
                        flux.add(fluxSum)
                        bandSum += band
                        totalSum += total
                        System.arraycopy(overlap, 0, real, 0, fftHop)
                        java.util.Arrays.fill(real, fftHop, fftSize, 0f)
                        java.util.Arrays.fill(imag, 0, fftSize, 0f)
                        windowFill = fftHop
                    }
                    // 250 ms RMS accumulation
                    squareSum += out.toDouble() * out
                    curveSamples++
                    if (curveSamples >= curveWindowSamples) {
                        energyCurve.add(kotlin.math.sqrt(squareSum / curveSamples).toFloat())
                        val ratio = if (totalSum > 1e-6) (bandSum / totalSum).toFloat() else 0f
                        vocalCurve.add(ratio)
                        squareSum = 0.0
                        bandSum = 0.0
                        totalSum = 0.0
                        curveSamples = 0
                    }
                    resamplePos += sourceRate.toDouble() / analysisRate
                }
                resamplePos -= 1.0
                lastSample = mono
            }

            while (!outputDone && scope.isActive) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)!!
                        val shortOut = out.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        idleSpins = 0
                        while (shortOut.remaining() >= sourceChannels) {
                            val readable = min(
                                shortOut.remaining() / sourceChannels,
                                chunk.size / sourceChannels,
                            )
                            shortOut.get(chunk, 0, readable * sourceChannels)
                            var f = 0
                            while (f < readable) {
                                var mono = 0
                                for (c in 0 until sourceChannels) {
                                    mono += chunk[f * sourceChannels + c]
                                }
                                pushSample(mono.toFloat() / (32768f * sourceChannels))
                                f++
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++idleSpins > 50) outputDone = true
                    }
                }
            }

            if (flux.isEmpty() || energyCurve.isEmpty()) return null
            return DecodedFeatures(
                sampleRate = analysisRate,
                fftHop = fftHop,
                flux = flux,
                energyCurve = energyCurve,
                vocalCurve = vocalCurve,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    // ---------------------------------------------------------------------
    // Tempo / structure / mix points
    // ---------------------------------------------------------------------

    private fun buildAnalysis(
        features: DecodedFeatures,
        durationMs: Long,
    ): AutoMixAnalysis {
        val fluxRate = features.sampleRate.toDouble() / features.fftHop
        val fluxArray = features.flux.toFloatArray()
        val tempo = estimateTempo(fluxArray, fluxRate)
        val energy = features.energyCurve.toFloatArray()
        val vocal = normalizeVocalCurve(features.vocalCurve.toFloatArray())
        val level = robustMean(energy.toList()).coerceAtLeast(1e-6f)
        val floor = level * 0.10f
        val active = level * 0.35f

        var audibleStart = 0L
        var introEnd = 0L
        for (i in energy.indices) {
            if (energy[i] > floor) { audibleStart = i * AUTO_MIX_CURVE_STEP_MS; break }
        }
        for (i in energy.indices) {
            if (energy[i] > active) { introEnd = i * AUTO_MIX_CURVE_STEP_MS; break }
        }
        var contentEnd = durationMs
        for (i in energy.indices.reversed()) {
            if (energy[i] > floor) { contentEnd = (i + 1) * AUTO_MIX_CURVE_STEP_MS; break }
        }
        var outroStart = introEnd
        for (i in energy.indices.reversed()) {
            if (energy[i] > active) { outroStart = i * AUTO_MIX_CURVE_STEP_MS; break }
        }
        if (outroStart < introEnd) outroStart = introEnd

        val mixOut = ArrayList<Long>(6)
        val tailFrom = max(introEnd, contentEnd - 30_000L)
        var i = (tailFrom / AUTO_MIX_CURVE_STEP_MS).toInt()
        while (i < energy.size - 2 && mixOut.size < 5) {
            val drop = energy[i] - energy[i + 1]
            if (drop > active * 0.8f && energy[i + 1] < active) {
                mixOut.add((i + 1) * AUTO_MIX_CURVE_STEP_MS)
                i += 8
            } else {
                i += 1
            }
        }
        mixOut.add((contentEnd - 12_000L).coerceAtLeast(introEnd))
        mixOut.add(outroStart)

        val mixIn = ArrayList<Long>(6)
        var j = (audibleStart / AUTO_MIX_CURVE_STEP_MS).toInt()
        val headTo = min(introEnd + 24_000L, contentEnd)
        while (j < energy.size - 1 && j * AUTO_MIX_CURVE_STEP_MS < headTo && mixIn.size < 4) {
            val rise = energy[j + 1] - energy[j]
            if (rise > active * 0.6f && energy[j] < active) {
                mixIn.add((j + 1) * AUTO_MIX_CURVE_STEP_MS)
                j += 8
            } else {
                j += 1
            }
        }
        mixIn.add(introEnd)

        return AutoMixAnalysis(
            durationMs = durationMs,
            bpm = tempo?.bpm ?: 0.0,
            beatIntervalMs = tempo?.intervalMs ?: 0.0,
            beatConfidence = tempo?.confidence ?: 0.0,
            downbeatPhaseMs = tempo?.phaseMs ?: 0.0,
            audibleStartMs = audibleStart,
            introEndMs = introEnd,
            outroStartMs = outroStart,
            contentEndMs = contentEnd,
            mixInCandidatesMs = mixIn.distinct().sorted(),
            mixOutCandidatesMs = mixOut.distinct().sorted(),
            energyCurve = energy.toList(),
            vocalActivity = vocal.toList(),
        )
    }

    /**
     * The raw band-ratio track is unipolar around a per-track baseline;
     * subtract the median and stretch to 0..1 so the planner can treat it as
     * a clash probability.
     */
    private fun normalizeVocalCurve(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) return raw
        val sorted = raw.sorted()
        val median = sorted[sorted.size / 2]
        val spread = sorted[sorted.size * 19 / 20] - median
        if (spread <= 1e-6f) return FloatArray(raw.size) { 0.5f }
        return FloatArray(raw.size) { ((it - median) / spread).coerceIn(0f, 1f) }
    }

    private class TempoEstimate(
        val bpm: Double,
        val intervalMs: Double,
        val confidence: Double,
        val phaseMs: Double,
    )

    /**
     * Autocorrelation of the onset-flux envelope over the 60-180 BPM lag
     * range, with comb reinforcement for 4/4 phase picking.
     */
    private fun estimateTempo(
        flux: FloatArray,
        fluxRate: Double,
    ): TempoEstimate? {
        if (flux.size < 64) return null
        val factor = max(1, (fluxRate / 187.0).toInt())
        val compactSize = flux.size / factor
        if (compactSize < 32) return null
        val compact = FloatArray(compactSize) { i ->
            var m = 0f
            for (k in 0 until factor) m = max(m, flux[i * factor + k])
            m
        }
        val compactRate = fluxRate / factor

        val mean = compact.average().toFloat()
        val centered = FloatArray(compact.size) { compact[it] - mean }
        var power = 0.0
        for (v in centered) power += v.toDouble() * v
        if (power <= 1e-9) return null

        val minLag = (compactRate * 60.0 / 180.0).toInt().coerceAtLeast(2)
        val maxLag = (compactRate * 60.0 / 60.0).toInt().coerceAtMost(centered.size / 2)
        if (maxLag <= minLag) return null

        var bestLag = -1
        var bestScore = 0.0
        var scoreSum = 0.0
        for (lag in minLag..maxLag) {
            var dot = 0.0
            for (t in 0 until centered.size - lag) {
                dot += centered[t].toDouble() * centered[t + lag]
            }
            val score = dot / power
            scoreSum += score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null

        val avg = scoreSum / (maxLag - minLag + 1)
        val prominence = (bestScore - avg) / (abs(bestScore) + abs(avg) + 1e-9)
        val confidence = (prominence * 2.0).coerceIn(0.0, 1.0)

        val rawIntervalMs = 1000.0 * bestLag / compactRate
        var bpm = 60_000.0 / rawIntervalMs
        while (bpm < 70.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0

        val bar = bestLag * 4
        var bestPhase = 0
        var bestComb = -1.0
        for (phase in 0 until bar) {
            var comb = 0.0
            var t = phase
            while (t < centered.size) {
                comb += centered[t].toDouble()
                t += bar
            }
            if (comb > bestComb) {
                bestComb = comb
                bestPhase = phase
            }
        }
        val phaseMs = 1000.0 * bestPhase / compactRate
        return TempoEstimate(
            bpm = bpm,
            intervalMs = 60_000.0 / bpm,
            confidence = confidence,
            phaseMs = phaseMs,
        )
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private fun cacheKey(trackId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trackId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun persist(
        trackId: String,
        analysis: AutoMixAnalysis,
    ) {
        persistenceMutex.withLock {
            runCatching {
                storeDir.mkdirs()
                File(storeDir, cacheKey(trackId)).writeText(json.encodeToString(analysis))
                pruneLocked()
            }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
        }
    }

    private suspend fun restore(trackId: String): AutoMixAnalysis? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = File(storeDir, cacheKey(trackId))
                if (f.isFile) json.decodeFromString<AutoMixAnalysis>(f.readText()) else null
            }.getOrNull()?.also { analyses[trackId] = it }
        }

    private fun pruneLocked() {
        val files = storeDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in files.dropLast(MAX_PERSISTED_ANALYSES)) {
            runCatching { f.delete() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun openDataSource(
        trackId: String,
        uri: Uri,
        extractor: MediaExtractor,
    ): Boolean {
        val scheme = uri.scheme ?: ""
        if (scheme == "file" || scheme == "content" || scheme.isEmpty()) {
            if (runCatching { extractor.setDataSource(appContext, uri, null) }.isSuccess) {
                return true
            }
        }
        val remote = runBlocking { runCatching { resolveStreamUrl(trackId) }.getOrNull() }
        if (remote.isNullOrBlank()) return false
        return runCatching { extractor.setDataSource(remote) }.isSuccess
    }

    private companion object {
        private const val TAG = "AutoMix"
        private const val MAX_PERSISTED_ANALYSES = 600
    }
}
