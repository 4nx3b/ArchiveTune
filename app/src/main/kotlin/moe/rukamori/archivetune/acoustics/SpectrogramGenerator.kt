/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Acoustic quality audit (Spek-style spectrogram) — adapted from AURALIS
 * (GPL-3.0) https://github.com/Rueded/AURALIS
 * (app/src/main/java/com/auralis/app/SpectrogramGenerator.kt) — original
 * on-demand decode/FFT/cutoff implementation, reworked for ArchiveTune's
 * multi-source playback (local uri, download-cache spans, resolved streams).
 */

package moe.rukamori.archivetune.acoustics

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.simpstream.SimpMusicPlayer
import moe.rukamori.archivetune.utils.isLocalMediaId
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AcousticAuditSource {
    data class Local(
        val uri: Uri,
    ) : AcousticAuditSource

    data class Cached(
        val file: File,
    ) : AcousticAuditSource
}

fun AcousticAuditSource.release() {
    if (this is AcousticAuditSource.Cached) {
        runCatching { file.delete() }
    }
}

enum class AcousticVerdict {
    TRUE_LOSSLESS,
    UPSCALED_LOSSY,
    SUSPECTED_FAKE,
}

data class AcousticAuditResult(
    val spectrogram: Bitmap,
    val cutoffHz: Float,
    val nyquistHz: Float,
    val sampleRate: Int,
    val verdict: AcousticVerdict,
)

private val YouTubeId = Regex("^[A-Za-z0-9_-]{11}$")

suspend fun resolveAcousticAuditSource(
    context: Context,
    songId: String,
    downloadUtil: DownloadUtil,
    onProgress: (Float) -> Unit = {},
): AcousticAuditSource? =
    withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext null
        if (songId.isLocalMediaId()) {
            return@withContext AcousticAuditSource.Local(songId.toUri())
        }

        val candidateKeys =
            buildList {
                runCatching { downloadUtil.currentSourceDownloadIds(songId) }.getOrNull()
                    ?.let(::addAll)
                addAll(DownloadSourceConfig.songIdToDownloadIds(songId))
                add(DownloadSourceConfig.YOUTUBE_MUSIC_CACHE_KEY_PREFIX + songId)
                add(songId)
            }.distinct()

        val downloadCache = downloadUtil.downloadCache
        for (key in candidateKeys) {
            val spans =
                runCatching { downloadCache.getCachedSpans(key) }.getOrNull().orEmpty()
            if (spans.isEmpty()) continue
            val completed =
                runCatching { downloadUtil.getDownload(songId).first() }
                    .getOrNull()
                    ?.let { it.state == Download.STATE_COMPLETED } == true
            val spanEnd = spans.last().position + spans.last().length
            if (!completed && spanEnd < 512L * 1024L) continue
            val tempDir = File(context.cacheDir, "acoustic_audit_tmp").apply { mkdirs() }
            val tempFile = File(tempDir, "${songId.hashCode()}_${key.hashCode()}.audit")
            val materialized =
                runCatching {
                    FileOutputStream(tempFile).use { output ->
                        spans.forEach { span ->
                            FileInputStream(span.file).use { input -> input.copyTo(output) }
                        }
                    }
                }.isSuccess
            if (materialized && tempFile.length() > 0L) {
                return@withContext AcousticAuditSource.Cached(tempFile)
            }
            runCatching { tempFile.delete() }
        }

        if (YouTubeId.matches(songId)) {
            val tempFile =
                withTimeoutOrNull(ACOUSTIC_STREAM_RESOLVE_TIMEOUT_MS) {
                    downloadYouTubeAudioForAudit(context, songId, onProgress)
                }
            tempFile?.let { return@withContext AcousticAuditSource.Cached(it) }
        }

        null
    }

private suspend fun downloadYouTubeAudioForAudit(
    context: Context,
    videoId: String,
    onProgress: (Float) -> Unit,
): File? {
    val streamUrl = resolveYouTubeAudioStreamUrl(videoId) ?: return null
    val tempDir = File(context.cacheDir, "acoustic_audit_tmp").apply { mkdirs() }
    val tempFile = File(tempDir, "yt_stream_${videoId.hashCode()}.audit")
    if (tempFile.exists()) runCatching { tempFile.delete() }

    val client =
        OkHttpClient
            .Builder()
            .callTimeout(ACOUSTIC_STREAM_DOWNLOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    val response =
        runCatching {
            client
                .newCall(
                    Request
                        .Builder()
                        .url(streamUrl)
                        .build(),
                ).execute()
        }.getOrNull() ?: return null

    response.use { httpResponse ->
        if (!httpResponse.isSuccessful) {
            runCatching { tempFile.delete() }
            return null
        }
        val body = httpResponse.body ?: run {
            runCatching { tempFile.delete() }
            return null
        }
        val copied =
            runCatching {
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > ACOUSTIC_STREAM_DOWNLOAD_CAP_BYTES) break
                            output.write(buffer, 0, read)
                            onProgress(
                                (ACOUSTIC_DOWNLOAD_PROGRESS_SPAN * total / ACOUSTIC_STREAM_DOWNLOAD_CAP_BYTES)
                                    .coerceAtMost(ACOUSTIC_DOWNLOAD_PROGRESS_SPAN),
                            )
                        }
                    }
                }
            }
        if (copied.isFailure) {
            runCatching { tempFile.delete() }
            return null
        }
    }

    if (tempFile.length() < 256L * 1024L) {
        runCatching { tempFile.delete() }
        return null
    }
    return tempFile
}

private suspend fun resolveYouTubeAudioStreamUrl(videoId: String): String? {
    val response =
        runCatching { SimpMusicPlayer.player(videoId = videoId).getOrNull()?.second }
            .getOrNull() ?: return null
    val audioFormats =
        (
            response.streamingData?.formats.orEmpty() +
                response.streamingData?.adaptiveFormats.orEmpty()
        ).filter { format ->
            format.mimeType.startsWith("audio/") && !format.url.isNullOrBlank()
        }
    val preferred =
        audioFormats.firstOrNull { it.mimeType.startsWith("audio/mp4") }
            ?: audioFormats.maxByOrNull { it.bitrate }
    return preferred?.url
}

private const val ACOUSTIC_STREAM_RESOLVE_TIMEOUT_MS = 120_000L
private const val ACOUSTIC_STREAM_DOWNLOAD_TIMEOUT_MS = 90_000L
private const val ACOUSTIC_STREAM_DOWNLOAD_CAP_BYTES = 48L * 1024 * 1024
private const val ACOUSTIC_DOWNLOAD_PROGRESS_SPAN = 0.30f

object SpectrogramGenerator {
    private const val TARGET_SECONDS = 20
    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = 1024
    private const val MIN_DB = -100f
    private const val MAX_DB = 0f
    private const val ACTIVE_THRESHOLD_DB = -65f

    suspend fun generate(
        context: Context,
        source: AcousticAuditSource,
        onProgress: (Float) -> Unit = {},
    ): AcousticAuditResult? =
        withContext(Dispatchers.IO) {
            val decoded = decodeMonoSamples(context, source, onProgress)
            if (decoded == null || decoded.samples.size < FFT_SIZE * 2) return@withContext null

            val samples = decoded.samples
            val sampleRate = decoded.sampleRate
            val numFrames = (samples.size - FFT_SIZE) / HOP_SIZE
            if (numFrames <= 0) return@withContext null

            val hannWindow =
                FloatArray(FFT_SIZE) { i ->
                    0.5f * (1.0f - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat())
                }
            val numBins = FFT_SIZE / 2
            val magnitudeMatrix = Array(numFrames) { FloatArray(numBins) }
            val sumMagnitudes = FloatArray(numBins)
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)

            for (frame in 0 until numFrames) {
                ensureActive()
                val startIdx = frame * HOP_SIZE
                for (i in 0 until FFT_SIZE) {
                    re[i] = samples[startIdx + i] * hannWindow[i]
                    im[i] = 0f
                }
                fft(re, im)
                for (bin in 0 until numBins) {
                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                    magnitudeMatrix[frame][bin] = mag
                    sumMagnitudes[bin] += mag
                }
                onProgress(0.35f + 0.6f * frame / numFrames)
            }

            var highestActiveBin = -1
            for (bin in 0 until numBins) {
                val avgMag = sumMagnitudes[bin] / numFrames
                val avgDb = 20.0f * log10(avgMag + 1e-8f)
                if (avgDb > ACTIVE_THRESHOLD_DB) {
                    highestActiveBin = bin
                }
            }

            val nyquistHz = sampleRate / 2.0f
            val cutoffHz =
                if (highestActiveBin != -1) {
                    highestActiveBin * nyquistHz / numBins
                } else {
                    0f
                }
            val verdict =
                when {
                    cutoffHz >= 20_000f -> AcousticVerdict.TRUE_LOSSLESS
                    cutoffHz >= 18_000f -> AcousticVerdict.UPSCALED_LOSSY
                    else -> AcousticVerdict.SUSPECTED_FAKE
                }

            val bitmap = Bitmap.createBitmap(numFrames, numBins, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(numFrames * numBins)
            for (y in 0 until numBins) {
                val binIdx = numBins - 1 - y
                for (x in 0 until numFrames) {
                    val mag = magnitudeMatrix[x][binIdx]
                    val db = 20.0f * log10(mag + 1e-8f)
                    val ratio = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
                    pixels[y * numFrames + x] = colorForRatio(ratio)
                }
            }
            bitmap.setPixels(pixels, 0, numFrames, 0, 0, numFrames, numBins)
            onProgress(1f)

            AcousticAuditResult(
                spectrogram = bitmap,
                cutoffHz = cutoffHz,
                nyquistHz = nyquistHz,
                sampleRate = sampleRate,
                verdict = verdict,
            )
        }

    private class DecodedPcm(
        val samples: FloatArray,
        val sampleRate: Int,
    )

    private fun decodeMonoSamples(
        context: Context,
        source: AcousticAuditSource,
        onProgress: (Float) -> Unit,
    ): DecodedPcm? {
        val extractor = MediaExtractor()
        val opened =
            runCatching {
                when (source) {
                    is AcousticAuditSource.Local -> extractor.setDataSource(context, source.uri, null)
                    is AcousticAuditSource.Cached -> extractor.setDataSource(source.file.absolutePath)
                }
            }.isSuccess
        if (!opened) {
            runCatching { extractor.release() }
            return null
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }
        if (trackIndex == -1) {
            runCatching { extractor.release() }
            return null
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            runCatching { extractor.release() }
            return null
        }
        val sampleRate =
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
        val channelCount =
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
        val durationUs =
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

        val targetSamples = sampleRate * TARGET_SECONDS
        val sampleData = FloatArray(targetSamples)
        var totalSamplesDecoded = 0

        val codec =
            try {
                MediaCodec.createDecoderByType(mime)
            } catch (_: Exception) {
                runCatching { extractor.release() }
                return null
            }

        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEOS = false
        var isOutputEOS = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            if (durationUs > 0) {
                runCatching {
                    extractor.seekTo(durationUs / 3, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
            }
            while (!isOutputEOS && totalSamplesDecoded < targetSamples) {
                if (!isInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val chunkSamples = shortBuffer.remaining()
                        var i = 0
                        while (i < chunkSamples && totalSamplesDecoded < targetSamples) {
                            var sum = 0f
                            var count = 0
                            for (c in 0 until channelCount) {
                                if (shortBuffer.hasRemaining()) {
                                    sum += shortBuffer.get().toFloat() / 32768.0f
                                    count++
                                }
                            }
                            if (count > 0) {
                                sampleData[totalSamplesDecoded] = sum / count
                                totalSamplesDecoded++
                            }
                            i += channelCount
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                    onProgress(ACOUSTIC_DOWNLOAD_PROGRESS_SPAN + 0.05f * totalSamplesDecoded / targetSamples)
                }
            }
        } catch (_: Exception) {
            if (totalSamplesDecoded < FFT_SIZE * 2) {
                return null
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        if (totalSamplesDecoded < FFT_SIZE * 2) return null
        return DecodedPcm(sampleData.copyOf(totalSamplesDecoded), sampleRate)
    }

    private fun fft(
        re: FloatArray,
        im: FloatArray,
    ) {
        val n = re.size
        if (n == 0) return
        var i = 0
        var j = 0
        while (i < n) {
            if (i < j) {
                var temp = re[i]
                re[i] = re[j]
                re[j] = temp
                temp = im[i]
                im[i] = im[j]
                im[j] = temp
            }
            var m = n shr 1
            while (m >= 2 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
            i++
        }

        var len = 2
        while (len <= n) {
            val ang = 2 * Math.PI / len
            val wpr = cos(ang).toFloat()
            val wpi = -sin(ang).toFloat()
            var iVal = 0
            while (iVal < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (k in 0 until len / 2) {
                    val idx1 = iVal + k
                    val idx2 = iVal + k + len / 2
                    val tr = re[idx2] * wr - im[idx2] * wi
                    val ti = re[idx2] * wi + im[idx2] * wr
                    re[idx2] = re[idx1] - tr
                    im[idx2] = im[idx1] - ti
                    re[idx1] += tr
                    im[idx1] += ti
                    val nextWr = wr * wpr - wi * wpi
                    wi = wr * wpi + wi * wpr
                    wr = nextWr
                }
                iVal += len
            }
            len = len shl 1
        }
    }

    private fun colorForRatio(ratio: Float): Int {
        val r = ratio.coerceIn(0.0f, 1.0f)
        val colors =
            arrayOf(
                intArrayOf(10, 10, 30),
                intArrayOf(40, 0, 120),
                intArrayOf(0, 100, 200),
                intArrayOf(0, 200, 100),
                intArrayOf(240, 220, 0),
                intArrayOf(255, 30, 30),
            )
        val segment = r * (colors.size - 1)
        val index = segment.toInt().coerceIn(0, colors.size - 2)
        val localRatio = segment - index
        val c1 = colors[index]
        val c2 = colors[index + 1]
        val red = (c1[0] + (c2[0] - c1[0]) * localRatio).toInt()
        val green = (c1[1] + (c2[1] - c1[1]) * localRatio).toInt()
        val blue = (c1[2] + (c2[2] - c1[2]) * localRatio).toInt()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
