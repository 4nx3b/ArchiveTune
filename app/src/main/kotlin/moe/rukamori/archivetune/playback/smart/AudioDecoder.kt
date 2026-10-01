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

object AudioDecoder {
    private const val TAG = "BitChordAudioDecoder"
    private const val TIMEOUT_US = 10_000L

    private const val MAX_DECODE_WALL_MS = 120_000L

    data class Pcm(val samples: FloatArray, val sampleRate: Double)

    data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Double)

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

            runCatching { Log.w(TAG, "Could not read duration from cached media", error) }
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun decodeRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        targetSampleRate: Double? = null,
        maxSeconds: Double? = null,
        abort: () -> Boolean = { false },
    ): Pair<Pcm, Double>? {
        if (targetSampleRate != null && targetSampleRate > 0) {
            val resampler = StreamingResampler(targetSampleRate)
            val budget =
                maxSeconds
                    ?.takeIf { it > 0 }
                    ?.let { seconds -> (seconds * targetSampleRate).toLong().coerceAtLeast(1L) }
                    ?: Long.MAX_VALUE
            val decoded =
                decodeRaw(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate ->
                    resampler.push(toMono(buffer, info, channels), rate, budget)
                } ?: return null
            val samples = resampler.result() ?: return null
            return Pcm(samples, targetSampleRate) to decoded.second
        }
        val chunks = ArrayList<FloatArray>()
        var framesDecoded = 0L
        var frameBudget = -1L
        val decoded =
            decodeRaw(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate ->
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

    fun decodeRegionStereo(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        maxSeconds: Double? = null,
        abort: () -> Boolean = { false },
    ): Pair<StereoPcm, Double>? {
        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        var framesDecoded = 0L
        var frameBudget = -1L
        val decoded =
            decodeRaw(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate ->
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

    private fun flatten(chunks: List<FloatArray>): FloatArray {
        val samples = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        return samples
    }

    private fun decodeRaw(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
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

            val deadlineUptimeMs = SystemClock.uptimeMillis() + MAX_DECODE_WALL_MS

            while (!outputDone) {

                if (abort()) return null
                if (SystemClock.uptimeMillis() > deadlineUptimeMs) {
                    Log.w(TAG, "Region decode exceeded ${MAX_DECODE_WALL_MS}ms wall clock — aborting")
                    return null
                }
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
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

            runCatching { Log.w(TAG, "Region decode failed", error) }
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

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

private class StreamingResampler(private val targetRate: Double) {
    private var step = 0.0

    private var acc = 0.0
    private var accCount = 0

    private var nextBoundary = 0.0

    private var sourceSeen = 0L

    private var out = FloatArray(8192)
    private var outSize = 0

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
