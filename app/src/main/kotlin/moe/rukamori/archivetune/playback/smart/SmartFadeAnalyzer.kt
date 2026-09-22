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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request

@UnstableApi
class SmartFadeAnalyzer(
    context: Context,
    /** Blocking: resolves a remote mediaId to a playable stream URL, or null. */
    private val resolveAudioUrl: (mediaId: String) -> String?,
) {
    companion object {
        private const val TAG = "SmartFadeAnalyzer"

        /**
         * The decode must cover at least this fraction of the container's
         * duration or the analysis is refused outright: a confidently wrong
         * mix-out anchor (faded out minutes early) is worse than a missing one
         * (plain crossfade fallback).
         */
        private const val MIN_DECODED_FRACTION = 0.95

        /** Neutral vocal-presence value; sits below every policy threshold. */
        private const val NEUTRAL_VOCAL = 0.5

        private const val STORE_DIR = "smartfade_audio"
        private const val MAX_STORE_FILES = 24
        private const val MAX_STORE_BYTES = 512L * 1024 * 1024

        /** A remote fetch must land at least this much audio to be worth decoding. */
        private const val MIN_ANALYSIS_BYTES = 256L * 1024

        /** A refused decode is retried at most this often before being written off. */
        private const val MAX_SHORT_DECODE_STRIKES = 3
    }

    private val appContext = context.applicationContext
    private val store = AnalysisStore(appContext)
    private val tracker = BeatTracker(appContext)
    private val vocals = VocalTracker(appContext)

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    private val fetchClient =
        OkHttpClient
            .Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))
            // A hard ceiling for the whole call: a stalled transfer used to
            // hold the single analysis thread hostage forever, leaving BOTH
            // the current and the next track stuck on "analysing…".
            .callTimeout(java.time.Duration.ofSeconds(120))
            .build()

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "archivetune-smartfade-analysis").apply {
                isDaemon = true
            }
        }

    // ------------------------------------------------------------------
    // Public surface (called from the playback thread, must never block)
    // ------------------------------------------------------------------

    /** What is known about [trackId] right now; empty analysis = no evidence. */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /** True once [trackId] has a result, including a failure. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /** True while a decode and inference for [trackId] is in flight. */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * Queues [trackId] for analysis if not already done or in flight. Cheap to
     * call repeatedly — the driver re-requests every poll tick. Local tracks
     * (content://, file://) are decoded straight off the device; remote tracks
     * are fetched once into the analysis store at low quality.
     */
    fun request(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ) {
        if (trackId.isBlank()) return
        if (trackId in running) return
        if (results.containsKey(trackId)) return
        if (!running.add(trackId)) return

        executor.execute {
            try {
                // A usable stored analysis short-circuits the whole pipeline:
                // without this head-check the first request of a session would
                // re-earn from audio a result that was already on disk.
                val stored = store.load(trackId)
                if (stored != null && stored.isUsable) {
                    results[trackId] = stored
                    Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
                    return@execute
                }
                // Efficient mode yields to decoding and playback rather than
                // competing for a core.
                Process.setThreadPriority(
                    if (SmartFadeSettings.performanceMode.value == AutomixPerformanceMode.EFFICIENT) {
                        Process.THREAD_PRIORITY_BACKGROUND
                    } else {
                        Process.THREAD_PRIORITY_DEFAULT
                    },
                )
                val analysis = analyze(trackId, uri, durationSeconds)
                val strikes = shortDecodes[trackId] ?: 0
                if (analysis == null) {
                    if (strikes + 1 >= MAX_SHORT_DECODE_STRIKES) {
                        // Write it off rather than re-reading it every tick.
                        results[trackId] = empty(trackId, durationSeconds)
                        shortDecodes.remove(trackId)
                    } else {
                        shortDecodes[trackId] = strikes + 1
                    }
                } else {
                    results[trackId] = analysis
                    shortDecodes.remove(trackId)
                    if (analysis.isUsable) {
                        store.save(trackId, analysis)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Analysis of $trackId failed", t)
                results[trackId] = empty(trackId, durationSeconds)
            } finally {
                running.remove(trackId)
            }
        }
    }

    fun release() {
        executor.shutdownNow()
        tracker.release()
        vocals.release()
    }

    // ------------------------------------------------------------------
    // Analysis pipeline
    // ------------------------------------------------------------------

    /** A null result means "not now, try again" (short decode, missing bytes). */
    private fun analyze(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): TrackAnalysis? {
        val local = LocalAudioSource.isLocal(uri)

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource(trackId, uri, local)?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: no readable duration")
            return empty(trackId, 0.0)
        }

        // Pass 1 (DSP-only): whole track at the analyzer's low sample rate, in
        // its own frame so the decoded buffer is collectible before Pass 2.
        // A null result means the source would not open or the decode refused
        // (retry later); empty features means the decode succeeded but the DSP
        // yielded nothing usable (recorded, degrades to a plain fade).
        val structural = structure(trackId, uri, local, effectiveDuration) ?: return null
        val features = structural.features ?: return empty(trackId, effectiveDuration)

        // Early publish: the whole-track DSP alone already carries a tempo
        // estimate — surface it the moment Pass 1 lands so the player's status
        // line resolves within seconds. The Beat This! / vocal model passes
        // below refine the result afterwards (the next poll picks the richer
        // numbers up). Without this, "analysing…" sat on screen for the whole
        // multi-minute pipeline even when the fast answer was already known.
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
                results[trackId] = early
                store.save(trackId, early)
                Log.d(TAG, "Early analysis for $trackId: bpm=${features.bpm} (models still refining)")
            }
        }

        // Pass 2 (models): the Beat This! grid and the open-unmix vocal mask,
        // over the head and tail only — a transition only ever reads the tail
        // of the outgoing track and the head of the incoming one.
        val openTrackSource: () -> MediaDataSource? = { openSource(trackId, uri, local) }
        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, effectiveDuration - window)
        val head = region(openTrackSource, 0.0, minOf(window, effectiveDuration), features)
        val tail = if (tailStart > window / 2) region(openTrackSource, tailStart, effectiveDuration, features) else null

        val headGrid = head?.grid
        val tailGrid = tail?.grid
        // The tail governs where the outgoing track is mixed out, so it takes
        // precedence; the head is what a track uses as the incoming side.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
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

    /** The whole-track DSP pass outcome: features, or why they are absent. */
    private class Structural(
        val features: TrackFeatures.Features?,
    )

    /**
     * Decodes the whole track and reduces it to DSP features. In a frame of
     * its own, and returning only the features, because of what it allocates:
     * the whole track decoded to mono plus the resampled copy the DSP reads.
     * Returning is what releases them before the model pass runs.
     *
     * Null = the source would not open or the decode refused (too short);
     * non-null with null features = decoded fine, DSP found no structure.
     */
    private fun structure(
        trackId: String,
        uri: Uri,
        local: Boolean,
        effectiveDuration: Double,
    ): Structural? {
        val structRate = TrackFeatures.sampleRate
        val decoded =
            openSource(trackId, uri, local)?.use {
                AudioDecoder.decodeRegion(it, 0.0, effectiveDuration)
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

        val samples =
            if (abs(pcm.sampleRate - structRate) > 1.0) {
                TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate)
            } else {
                pcm.samples
            }
        return Structural(TrackFeatures.analyze(samples ?: return null, effectiveDuration))
    }

    /** Everything a decoded region contributes, once its audio is let go of. */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only
     * their results. Null means "no model evidence for this window" — a codec
     * that will not configure, a region too short, a missing model.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features,
    ): Region? {
        val decoded =
            openSource()?.use { AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds) }
                ?: return null
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) return null

        // In a frame of its own so the full-rate mono downmix is released
        // before either model runs.
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

    /**
     * A vocal-presence value for every point on the energy curve, filled only
     * where the model actually ran; everywhere else stays [NEUTRAL_VOCAL],
     * which sits below the policy's active threshold so unmeasured material
     * can never trip vocal logic in either direction.
     */
    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        // The beat model's window is longer than the vocal model's fixed input,
        // so the region is trimmed rather than handed over whole.
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

    /** Overlays the head and tail masks onto one full-length curve. */
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

    /** Recorded ready-but-empty so an undecodable track is not retried forever. */
    private fun empty(
        trackId: String,
        durationSeconds: Double,
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    // ------------------------------------------------------------------
    // Analysis audio store
    // ------------------------------------------------------------------

    /**
     * Opens a [MediaDataSource] for the track: local URIs straight off the
     * device, remote ids from the LRU analysis store — fetching on first use
     * so the *incoming* track's audio exists well before its transition.
     */
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
            return FileMediaDataSource(file)
        }
        val fetched = fetchAnalysisAudio(trackId, file) ?: return null
        return FileMediaDataSource(fetched)
    }

    private fun analysisFileFor(trackId: String): File {
        val dir = File(appContext.filesDir, STORE_DIR)
        if (!dir.isDirectory) dir.mkdirs()
        // Hashed names: stable across sessions and safe for any id shape.
        return File(dir, Integer.toHexString(trackId.hashCode()))
    }

    /** Downloads the low-quality analysis rendition for [trackId]. */
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

    /** LRU cap: oldest files go first, both by count and by bytes. */
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

/** Random-access [MediaDataSource] over a fully-downloaded analysis file. */
private class FileMediaDataSource(
    private val file: File,
) : MediaDataSource() {
    private val handle = RandomAccessFile(file, "r")

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

    override fun getSize(): Long = handle.length()

    override fun close() {
        handle.close()
    }
}
