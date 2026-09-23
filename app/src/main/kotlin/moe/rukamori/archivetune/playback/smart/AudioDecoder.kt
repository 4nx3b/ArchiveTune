/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis pipeline ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord), which derives it from
 * Orchard (https://github.com/SFG5453/Orchard). Orchard's original source
 * is licensed AGPL-3.0-or-later; per AGPLv3 section 13 this file is
 * combined into ArchiveTune -- a GPL-3.0-or-later work -- and remains
 * itself governed by the AGPLv3 as part of that combination.
 */

package moe.rukamori.archivetune.playback.smart
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Decodes a region of a cached track to mono float PCM, for [TrackAnalyzer].
 *
 * Only a region: a transition only ever reads the tail of the outgoing track
 * and the head of the incoming one, not either track in full, and decoding a
 * whole album's worth of audio to analyse thirty seconds of it would cost
 * battery for nothing.
 *
 * Everything here is best-effort. A codec that will not configure, a
 * container Android cannot parse, a region past the end — all return null,
 * and the caller falls back to no analysis, which the transition policy
 * already handles as its bottom rung.
 *
 * Memory contract: the whole-track structural pass folds each decoded chunk
 * down to the analyzer's low target rate as it arrives
 * ([decodeRegion]'s [targetSampleRate]), so the container-rate signal never
 * exists in full — a heap that cannot hold 200 MB of float PCM must never be
 * asked to. [maxSeconds] bounds a decode whose timestamps lie (containers
 * that under-report their duration would otherwise walk the file to its end).
 */
object AudioDecoder {

    private const val TAG = "BitChordAudioDecoder"
    private const val TIMEOUT_US = 10_000L

    /** Hard ceiling for one region decode: whole-track reads of long files
     * legitimately take tens of seconds, but anything past this is a wedged
     * codec, not a slow decode. */
    private const val MAX_DECODE_WALL_MS = 120_000L

    /** Decoded mono PCM at the container's own sample rate; the caller resamples. */
    data class Pcm(val samples: FloatArray, val sampleRate: Double)

    /**
     * Decoded planar stereo PCM at the container's own sample rate.
     *
     * Planar rather than interleaved because the only consumer is the vocal
     * front end, which wants one array per channel; a mono source is widened
     * by sharing the same samples on both sides, which is what the model was
     * trained to see for centre-panned material anyway.
     */
    data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Double)

    /**
     * Reads the audio duration a fully-cached container advertises, without
     * decoding it. Queue metadata isn't always trustworthy, so this is the
     * fallback [TrackAnalyzer] reaches for when a track's own duration is
     * missing or non-finite.
     */
    fun containerDurationSeconds(source: MediaDataSource): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source)
            (0 until extractor.trackCount)
                .mapNotNull { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                    if (!mime.startsWith("audio/") || !format.containsKey(MediaFormat.KEY_DURATION)) {
                        return@mapNotNull null
                    }
                    format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1_000_000.0)
                }
                .maxOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
        } catch (error: Throwable) {
            // Throwable, not Exception: during heap pressure the huge PCM
            // allocations below can surface as OutOfMemoryError here first,
            // and an escaping Error used to kill the analysis worker's thread
            // with no Java unwind left behind.
            runCatching { Log.w(TAG, "Could not read duration from cached media", error) }
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes [startSeconds] to [endSeconds] of [source], downmixed to mono.
     *
     * With [targetSampleRate] the mono chunks are folded down to that rate as
     * the codec produces them ([StreamingResampler]) and the result comes
     * back at exactly that rate — the container-rate signal never exists in
     * full, which is what keeps a whole-track structural pass inside a normal
     * heap. Without it, the result stays at the container's own rate and the
     * caller resamples.
     *
     * [maxSeconds] (in seconds of decoded audio) stops the decode early once
     * exceeded — a guard against containers whose timestamps lie, which would
     * otherwise decode far past the requested end and balloon without bound.
     *
     * The extractor seeks to the closest sync sample at or before the
     * requested start, so a little more audio than asked for may come back at
     * the front; the caller is given the real start via the returned offset
     * so frame indices still map to true track times.
     */
    fun decodeRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        targetSampleRate: Double? = null,
        maxSeconds: Double? = null,
    ): Pair<Pcm, Double>? {
        if (targetSampleRate != null && targetSampleRate > 0) {
            val resampler = StreamingResampler(targetSampleRate)
            val budget =
                maxSeconds
                    ?.takeIf { it > 0 }
                    ?.let { seconds -> (seconds * targetSampleRate).toLong().coerceAtLeast(1L) }
                    ?: Long.MAX_VALUE
            val decoded =
                decodeRaw(source, startSeconds, endSeconds) { buffer, info, channels, rate ->
                    resampler.push(toMono(buffer, info, channels), rate, budget)
                } ?: return null
            val samples = resampler.result() ?: return null
            return Pcm(samples, targetSampleRate) to decoded.second
        }
        val chunks = ArrayList<FloatArray>()
        var framesDecoded = 0L
        var frameBudget = -1L
        val decoded =
            decodeRaw(source, startSeconds, endSeconds) { buffer, info, channels, rate ->
                if (frameBudget < 0 && maxSeconds != null && rate > 0) {
                    frameBudget = (maxSeconds * rate).toLong().coerceAtLeast(1L)
                }
                val chunk = toMono(buffer, info, channels)
                chunks += chunk
                framesDecoded += chunk.size
                frameBudget < 0 || framesDecoded < frameBudget
            } ?: return null
        return Pcm(flatten(chunks), decoded.first) to decoded.second
    }

    /**
     * As [decodeRegion], but keeping the two channels apart.
     *
     * Only the vocal front end needs this: open-unmix was trained on stereo,
     * and handing it a duplicated mono mix throws away the very stereo
     * information it uses to tell a centred vocal from the instruments
     * around it.
     */
    fun decodeRegionStereo(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        maxSeconds: Double? = null,
    ): Pair<StereoPcm, Double>? {
        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        var framesDecoded = 0L
        var frameBudget = -1L
        val decoded =
            decodeRaw(source, startSeconds, endSeconds) { buffer, info, channels, rate ->
                if (frameBudget < 0 && maxSeconds != null && rate > 0) {
                    frameBudget = (maxSeconds * rate).toLong().coerceAtLeast(1L)
                }
                val (leftChunk, rightChunk) = toStereo(buffer, info, channels)
                left += leftChunk
                right += rightChunk
                framesDecoded += leftChunk.size
                frameBudget < 0 || framesDecoded < frameBudget
            } ?: return null
        return StereoPcm(flatten(left), flatten(right), decoded.first) to decoded.second
    }

    /** Concatenates the decoded chunks into one contiguous buffer. */
    private fun flatten(chunks: List<FloatArray>): FloatArray {
        val samples = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        return samples
    }

    /**
     * Runs the decode loop, handing each output buffer to [onBuffer] — which
     * receives the output sample rate and returns false to stop the decode
     * (the budget signal) — and returns the output sample rate paired with
     * the region's real start.
     *
     * Shared by the mono and stereo entry points so there is one dequeue loop
     * to get right rather than two that can drift apart; all that differs
     * between them is how a buffer is reduced, which is what [onBuffer] owns.
     */
    private fun decodeRaw(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        onBuffer: (ByteBuffer, MediaCodec.BufferInfo, Int, Double) -> Boolean,
    ): Pair<Double, Double>? {
        if (endSeconds <= startSeconds) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val startUs = (startSeconds * 1_000_000).toLong()
            val endUs = (endSeconds * 1_000_000).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = runCatching { MediaCodec.createDecoderByType(mime) }
                .onFailure { Log.w(TAG, "No decoder for $mime", it) }
                .getOrNull() ?: return null
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var outputChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var outputRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var actualStartSeconds = -1.0
            var sawFirstSample = false
            var inputDone = false
            var outputDone = false
            // Wall-clock ceiling for the sync decode loop: a codec wedged on a
            // corrupt stream spins this loop forever without erroring, which
            // used to pin the single analysis thread (and the "analysing…"
            // status with it) indefinitely.
            val deadlineUptimeMs = SystemClock.uptimeMillis() + MAX_DECODE_WALL_MS

            while (!outputDone) {
                if (SystemClock.uptimeMillis() > deadlineUptimeMs) {
                    Log.w(TAG, "Region decode exceeded ${MAX_DECODE_WALL_MS}ms wall clock — aborting")
                    return null
                }
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            // Nothing to feed this cycle; try again next iteration.
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || (sampleTimeUs in 0..Long.MAX_VALUE && sampleTimeUs > endUs)) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        outputRate = newFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                        outputChannels = newFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (bufferInfo.size > 0) {
                            if (!sawFirstSample) {
                                actualStartSeconds = bufferInfo.presentationTimeUs / 1_000_000.0
                                sawFirstSample = true
                            }
                            var budgetExceeded = false
                            codec.getOutputBuffer(outputIndex)?.let { output ->
                                budgetExceeded = !onBuffer(output, bufferInfo, outputChannels, outputRate.toDouble())
                            }
                            if (budgetExceeded) {
                                // Enough decoded audio: stop feeding and drain no
                                // further — timestamps that lie would otherwise
                                // walk the decode to the file's true end.
                                inputDone = true
                                outputDone = true
                            } else if (bufferInfo.presentationTimeUs > endUs) {
                                outputDone = true
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (!sawFirstSample || outputRate <= 0) return null
            return outputRate.toDouble() to actualStartSeconds
        } catch (error: Throwable) {
            // Throwable, not Exception: MediaCodec configuration and the float
            // PCM allocations surface as OutOfMemoryError/StackOverflowError
            // under heap pressure, and an Error escaping here used to kill the
            // analysis thread outright — the exact silent force-close the
            // automix crash reports described.
            runCatching { Log.w(TAG, "Region decode failed", error) }
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downmixes one 16-bit PCM output buffer to mono float in [-1, 1]. */
    private fun toMono(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int): FloatArray {
        val safeChannels = max(1, channels)
        val shorts = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit(info.offset + info.size)
        }.asShortBuffer()
        val frames = shorts.remaining() / safeChannels
        val mono = FloatArray(frames)
        val frame = ShortArray(safeChannels)
        for (index in 0 until frames) {
            shorts.get(frame, 0, safeChannels)
            var sum = 0
            for (value in frame) sum += value
            mono[index] = (sum / safeChannels.toFloat()) / 32768f
        }
        return mono
    }

    /**
     * Splits one 16-bit PCM output buffer into planar left/right float in
     * [-1, 1], as one chunk per side.
     *
     * A mono source is widened by giving both sides the same samples, and
     * anything above two channels keeps only the first two: the model's input
     * is stereo, and a downmix of a 5.1 track would put the centre channel —
     * where the vocal usually is — into both sides at half level, which is
     * the opposite of helpful for telling a vocal apart from the bed.
     */
    private fun toStereo(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
    ): Pair<FloatArray, FloatArray> {
        val safeChannels = max(1, channels)
        val shorts = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit(info.offset + info.size)
        }.asShortBuffer()
        val frames = shorts.remaining() / safeChannels
        val leftChunk = FloatArray(frames)
        val rightChunk = FloatArray(frames)
        val frame = ShortArray(safeChannels)
        for (index in 0 until frames) {
            shorts.get(frame, 0, safeChannels)
            leftChunk[index] = frame[0] / 32768f
            rightChunk[index] = (if (safeChannels > 1) frame[1] else frame[0]) / 32768f
        }
        return leftChunk to rightChunk
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}

/**
 * Folds a stream of mono chunks — whose source rate is only known once the
 * codec starts producing — into one buffer at a fixed target rate, so a
 * whole-track decode never materialises at the container rate.
 *
 * Down-conversion box-averages each output sample's worth of input, which is
 * an adequate anti-alias for envelope, structure and key work (all of it
 * lives far below half the target rate — the beat model's mel front end still
 * gets the full-bandwidth sinc-resampled path); up-conversion
 * sample-and-holds. Both keep O(1) state and handle arbitrary, non-integer
 * ratios, and neither needs the previous chunk: every input sample lands in
 * exactly one output box.
 */
private class StreamingResampler(private val targetRate: Double) {
    /** Source samples per output sample; learned from the first chunk's rate. */
    private var step = 0.0

    /** Running sum/count of the currently open output box (down-conversion). */
    private var acc = 0.0
    private var accCount = 0

    /** 1-based source-sample count that closes the currently open box. */
    private var nextBoundary = 0.0

    /** Source samples pushed so far. */
    private var sourceSeen = 0L

    private var out = FloatArray(8192)
    private var outSize = 0

    /**
     * Appends [chunk] (decoded at [rate]) and returns false once [budget]
     * output samples exist — the caller's signal to stop the decode.
     */
    fun push(chunk: FloatArray, rate: Double, budget: Long): Boolean {
        if (chunk.isEmpty()) return outSize.toLong() < budget
        if (step <= 0.0) {
            if (rate <= 0.0) return outSize.toLong() < budget
            step = rate / targetRate
            nextBoundary = step
        }
        if (step >= 1.0) {
            for (value in chunk) {
                acc += value
                accCount++
                val oneBased = ++sourceSeen
                if (oneBased.toDouble() >= nextBoundary) {
                    append((acc / accCount).toFloat())
                    acc = 0.0
                    accCount = 0
                    nextBoundary += step
                }
            }
        } else {
            for (value in chunk) {
                val from = kotlin.math.ceil(sourceSeen / step).toLong()
                val until = kotlin.math.ceil((sourceSeen + 1) / step).toLong()
                for (index in from until until) append(value)
                sourceSeen++
            }
        }
        return outSize.toLong() < budget
    }

    /** Emits the trailing partial box, if any, and returns the folded samples. */
    fun result(): FloatArray? {
        if (accCount > 0) {
            append((acc / accCount).toFloat())
            acc = 0.0
            accCount = 0
        }
        return out.takeIf { outSize > 0 }?.copyOf(outSize)
    }

    private fun append(value: Float) {
        if (outSize == out.size) out = out.copyOf(out.size * 2)
        out[outSize++] = value
    }
}
