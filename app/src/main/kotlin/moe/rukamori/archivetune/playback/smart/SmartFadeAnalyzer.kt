/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis orchestrator, ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord) and adapted to ArchiveTune's
 * streaming: BitChord reads its analysis bytes out of the player's audio
 * cache (renditions, head prefetch); ArchiveTune has no rendition concept,
 * so the analyzer owns a small dedicated LRU store of analysis-only audio
 * fetched at low quality — analysis reads tempo, structure and vocals, none
 * of which need the bitrate the listener hears.
 */

package moe.rukamori.archivetune.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import moe.rukamori.archivetune.constants.AutomixPerformanceMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import okhttp3.OkHttpClient
import okhttp3.Request

@UnstableApi
class SmartFadeAnalyzer(
    context: Context,

    private val resolveAudioUrl: (mediaId: String) -> String?,
) {
    companion object {
        private const val TAG = "SmartFadeAnalyzer"

        private const val MIN_DECODED_FRACTION = 0.95

        private const val NEUTRAL_VOCAL = 0.5

        private const val STORE_DIR = "smartfade_audio"
        private const val MAX_STORE_FILES = 24
        private const val MAX_STORE_BYTES = 512L * 1024 * 1024

        private const val MIN_ANALYSIS_BYTES = 256L * 1024

        private const val MAX_SHORT_DECODE_STRIKES = 3

        private const val MAX_ANALYSIS_SECONDS = 600.0

        private const val STRUCT_DECODE_HEADROOM = 1.25

        private const val STRUCT_DECODE_SLACK_SECONDS = 15.0

        private const val REGION_DECODE_HEADROOM = 1.25
        private const val REGION_DECODE_SLACK_SECONDS = 10.0

        private const val MIN_FREE_HEAP_BYTES = 96L * 1024 * 1024

        private const val MAX_IN_MEMORY_RESULTS = 64

        private object Deferred
    }

    private val appContext = context.applicationContext
    private val store = AnalysisStore(appContext)
    private val tracker = BeatTracker(appContext)
    private val vocals = VocalTracker(appContext)

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    private val resultOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()

    @Volatile
    private var released = false

    private val fetchClient =
        OkHttpClient
            .Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))

            .callTimeout(java.time.Duration.ofSeconds(120))
            .build()

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "archivetune-smartfade-analysis").apply {
                isDaemon = true
            }
        }

    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    private fun recordResult(trackId: String, analysis: TrackAnalysis) {
        results[trackId] = analysis
        resultOrder.addLast(trackId)
        while (resultOrder.size > MAX_IN_MEMORY_RESULTS) {
            val oldestKey = resultOrder.pollFirst() ?: break
            val oldest = results[oldestKey] ?: continue
            results.remove(oldestKey, oldest)
        }
    }

    fun isAnalysing(trackId: String): Boolean = trackId in running

    fun request(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ) {
        if (trackId.isBlank()) return
        if (trackId in running) return
        if (results.containsKey(trackId)) return
        if (!running.add(trackId)) return

        runCatching {
            executor.execute {
                try {
                    if (released) return@execute

                    val stored = store.load(trackId)
                    if (stored != null && stored.isUsable) {
                        recordResult(trackId, stored)
                        Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
                        return@execute
                    }

                    Process.setThreadPriority(
                        if (SmartFadeSettings.performanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                            Process.THREAD_PRIORITY_BACKGROUND
                        } else {
                            Process.THREAD_PRIORITY_DEFAULT
                        },
                    )
                    val analysis = analyze(trackId, uri, durationSeconds)
                    if (analysis === Deferred) {

                        Log.d(TAG, "Deferring analysis of $trackId (retryable)")
                    } else if (analysis == null) {
                        val strikes = shortDecodes[trackId] ?: 0
                        if (strikes + 1 >= MAX_SHORT_DECODE_STRIKES) {
                            recordResult(trackId, empty(trackId, durationSeconds))
                            shortDecodes.remove(trackId)
                        } else {
                            shortDecodes[trackId] = strikes + 1
                        }
                    } else {

                        val result = analysis as TrackAnalysis
                        recordResult(trackId, result)
                        shortDecodes.remove(trackId)
                        if (result.isUsable) {
                            store.save(trackId, result)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Analysis of $trackId failed", t)
                    recordResult(trackId, empty(trackId, durationSeconds))
                } finally {
                    running.remove(trackId)
                }
            }
        }.onFailure {
            running.remove(trackId)
        }
    }

    fun release() {
        released = true
        executor.shutdownNow()

        Thread({
            val drained = runCatching { executor.awaitTermination(150, TimeUnit.SECONDS) }
                .isSuccess && executor.isTerminated
            if (drained) {
                tracker.release()
                vocals.release()
            } else {
                Log.w(TAG, "Analysis worker did not drain in time; leaving model sessions to process teardown")
            }
        }, "archivetune-smartfade-release").apply {
            isDaemon = true
        }.start()
    }

    private fun analyze(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): Any? {
        val local = LocalAudioSource.isLocal(uri)

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource(trackId, uri, local)?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: no readable duration")
            return empty(trackId, 0.0)
        }
        if (effectiveDuration > MAX_ANALYSIS_SECONDS) {
            Log.d(
                TAG,
                "Skipping $trackId: %.0fs exceeds the analysis cap".format(Locale.ROOT, effectiveDuration),
            )
            return empty(trackId, effectiveDuration)
        }

        if (!hasHeapHeadroom()) {
            Log.d(TAG, "Deferring analysis of $trackId: low heap headroom")
            return Deferred
        }

        Log.d(TAG, "stage=fetch/decode-struct track=$trackId duration=%.1fs".format(Locale.ROOT, effectiveDuration))

        val structural = structure(trackId, uri, local, effectiveDuration) ?: return null
        val features = structural.features ?: return empty(trackId, effectiveDuration)

        if (released) return null

        Log.d(TAG, "stage=dsp track=$trackId")

        runCatching {
            val early = TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = trackId,
                duration = effectiveDuration,
                contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
                bpm = features.bpm,
                beatInterval = features.beatInterval,
                beatConfidence = features.beatConfidence,
                downbeats = features.downbeats,
            )
            if (early.isUsable) {
                recordResult(trackId, early)
                store.save(trackId, early)
                Log.d(TAG, "Early analysis for $trackId: bpm=${features.bpm} (models still refining)")
            }
        }

        if (released) return null
        if (!hasHeapHeadroom()) {
            Log.d(TAG, "Deferring model passes of $trackId: low heap headroom")
            return Deferred
        }
        Log.d(TAG, "stage=decode-region track=$trackId")
        val openTrackSource: () -> MediaDataSource? = { openSource(trackId, uri, local) }
        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, effectiveDuration - window)
        val head = region(openTrackSource, 0.0, minOf(window, effectiveDuration), features)
        val tail = if (tailStart > window / 2) region(openTrackSource, tailStart, effectiveDuration, features) else null

        val headGrid = head?.grid
        val tailGrid = tail?.grid

        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "stage=models-done track=$trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                .ifEmpty { features.downbeats }
                .sorted(),
            firstBeat = headGrid?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = mergeMasks(features.energyCurve.size, head?.vocalMask, tail?.vocalMask)
                ?: features.vocalActivityMask,
            vocalProbability = features.vocalProbability,
        )
    }

    private class Structural(
        val features: TrackFeatures.Features?,
    )

    private fun structure(
        trackId: String,
        uri: Uri,
        local: Boolean,
        effectiveDuration: Double,
    ): Structural? {
        val structRate = TrackFeatures.sampleRate

        val decoded =
            openSource(trackId, uri, local)?.use {
                AudioDecoder.decodeRegion(
                    it,
                    0.0,
                    effectiveDuration,
                    targetSampleRate = structRate,
                    maxSeconds = effectiveDuration * STRUCT_DECODE_HEADROOM + STRUCT_DECODE_SLACK_SECONDS,
                    abort = { released },
                )
            } ?: return null
        val (pcm, _) = decoded

        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s container",
            )
            return null
        }

        return Structural(TrackFeatures.analyze(pcm.samples, effectiveDuration))
    }

    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
    )

    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features,
    ): Region? {
        val decoded =
            openSource()?.use {
                AudioDecoder.decodeRegionStereo(
                    it,
                    startSeconds,
                    endSeconds,
                    maxSeconds = (endSeconds - startSeconds) * REGION_DECODE_HEADROOM + REGION_DECODE_SLACK_SECONDS,
                    abort = { released },
                )
            } ?: return null
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) return null

        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel: FloatArray =
            if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
                MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate) ?: return null
            } else {
                mono
            }

        return Region(
            grid = tracker.track(forModel, offsetSeconds = actualStart),
            vocalMask = vocalMask(stereo, features, actualStart),
        )
    }

    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right

        val values = vocals.track(left, right, stereo.sampleRate) ?: return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    private fun mergeMasks(
        size: Int,
        head: DoubleArray?,
        tail: DoubleArray?,
    ): List<Double>? {
        if (size <= 0 || (head == null && tail == null)) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in listOfNotNull(head, tail)) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    private fun hasHeapHeadroom(): Boolean {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        return free >= MIN_FREE_HEAP_BYTES
    }

    private fun empty(
        trackId: String,
        durationSeconds: Double,
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    private fun openSource(
        trackId: String,
        uri: Uri,
        local: Boolean,
    ): MediaDataSource? {
        if (local) {
            return LocalAudioSource.open(appContext.contentResolver, uri)
        }
        val file = analysisFileFor(trackId)
        if (file.exists() && file.length() >= MIN_ANALYSIS_BYTES) {
            file.setLastModified(System.currentTimeMillis())

            return runCatching { FileMediaDataSource(file) }.getOrNull()
        }
        val fetched = fetchAnalysisAudio(trackId, file) ?: return null
        return runCatching { FileMediaDataSource(fetched) }.getOrNull()
    }

    private fun analysisFileFor(trackId: String): File {
        val dir = File(appContext.filesDir, STORE_DIR)
        if (!dir.isDirectory) dir.mkdirs()

        return File(dir, "${Integer.toHexString(trackId.hashCode())}_${trackId.length}")
    }

    private fun fetchAnalysisAudio(
        trackId: String,
        target: File,
    ): File? {
        val url = runCatching { resolveAudioUrl(trackId) }.getOrNull() ?: return null
        return try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            fetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Analysis fetch for $trackId failed: HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val temp = File(target.parentFile, target.name + ".part")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (temp.length() < MIN_ANALYSIS_BYTES) {
                    temp.delete()
                    return null
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
                pruneStore()
                target
            }
        } catch (e: IOException) {
            Log.d(TAG, "Analysis fetch for $trackId failed: ${e.message}")
            runCatching { File(target.parentFile, target.name + ".part").delete() }
            null
        }
    }

    private fun pruneStore() {
        val dir = File(appContext.filesDir, STORE_DIR)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var totalBytes = files.sumOf { it.length() }
        val byAge = files.sortedBy { it.lastModified() }
        var count = files.size
        for (file in byAge) {
            if (count <= MAX_STORE_FILES && totalBytes <= MAX_STORE_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                count -= 1
            }
        }
    }
}

private class FileMediaDataSource(
    private val file: File,
) : MediaDataSource() {
    private val handle = RandomAccessFile(file, "r")

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (size <= 0) return 0
        handle.seek(position)
        var read = 0
        while (read < size) {
            val n = handle.read(buffer, offset + read, size - read)
            if (n < 0) break
            read += n
        }
        return if (read == 0) -1 else read
    }

    @Synchronized
    override fun getSize(): Long = handle.length()

    @Synchronized
    override fun close() {
        handle.close()
    }
}
