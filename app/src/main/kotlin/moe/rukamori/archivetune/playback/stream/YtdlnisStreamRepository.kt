/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * External yt-dlp fallback via CompactYtDlp, used only when a compatible runtime
 * is installed. No Python is bundled. Extractor/signature handling remains in
 * the working native core; the former reflection stage never resolved a stream.
 *
 * ResolveAudioStreamUseCase tries NativeStreamRepository (InnerTubeX/BotGuard) first; only on
 * failure does it delegate here, so the hot path stays native and fast.
 */

package moe.rukamori.archivetune.playback.stream

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import moe.rukamori.archivetune.ytdlp.CompactYtDlp
import org.json.JSONObject
import timber.log.Timber

@Singleton
class YtdlnisStreamRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AudioStreamRepository {

        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream {
            currentCoroutineContext().ensureActive()
            try {
                val newPipeResult = tryNewPipe(request)
                currentCoroutineContext().ensureActive()
                if (newPipeResult != null) return newPipeResult
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "NewPipe fallback failed for %s", request.mediaId)
            }

            if (!CompactYtDlp.isAvailable(context)) {
                throw YtDlpExtractionException("No external yt-dlp runtime is available for ${request.mediaId}")
            }

            try {
                val ytdlpResult = tryExternalYtDlp(request)
                currentCoroutineContext().ensureActive()
                if (ytdlpResult != null) return ytdlpResult
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag(TAG).d(error, "External yt-dlp fallback failed for %s", request.mediaId)
                throw YtDlpExtractionException("External yt-dlp failed for ${request.mediaId}", error)
            }

            throw YtDlpExtractionException("All Ytdlnis fallbacks failed for ${request.mediaId}")
        }

        private suspend fun tryNewPipe(request: AudioStreamRequest): ResolvedAudioStream? {

            return try {
                val clazz = Class.forName("moe.rukamori.archivetune.innertube.NewPipeUtils")
                val method = clazz.getMethod("getStreamUrl", String::class.java, String::class.java)

                null
            } catch (_: ClassNotFoundException) {
                null
            }
        }

        private suspend fun tryExternalYtDlp(request: AudioStreamRequest): ResolvedAudioStream? {
            val json = CompactYtDlp.dumpJson(context, request.mediaId, extraArgs = listOf("--format", "bestaudio/best", "--no-playlist")) ?: return null

            val firstLine = json.lineSequence().firstOrNull { it.trim().startsWith("{") } ?: return null
            val obj = JSONObject(firstLine)
            val url = obj.optString("url").takeIf { it.isNotBlank() }
                ?: obj.optJSONArray("formats")?.let { arr ->

                    var best: JSONObject? = null
                    var bestAbr = 0
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val vcodec = f.optString("vcodec")
                        if (vcodec != "none" && vcodec.isNotBlank()) continue
                        val abr = f.optInt("abr", 0)
                        if (abr > bestAbr) {
                            bestAbr = abr
                            best = f
                        }
                    }
                    best?.optString("url")
                } ?: return null

            val ext = obj.optString("ext", "mp4")
            val mimeType = when (ext) {
                "opus" -> "audio/opus"
                "m4a", "mp4" -> "audio/mp4"
                "webm" -> "audio/webm"
                else -> "audio/$ext"
            }
            val bitrate = obj.optInt("abr", 128) * 1000
            val duration = obj.optInt("duration", 0)
            return ResolvedAudioStream(
                url = url,
                requestHeaders = emptyMap(),
                formatId = obj.optInt("format_id", 0),
                mimeType = mimeType,
                codecs = obj.optString("acodec", "opus"),
                bitrate = bitrate,
                sampleRate = obj.optInt("asr", 48000),
                contentLength = 0L,
                expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                authFingerprint = request.authState.streamCacheFingerprint,
                source = StreamSource.YT_DLP,
                title = obj.optString("title"),
                durationSeconds = duration,
                thumbnailUrl = obj.optString("thumbnail"),
                loudnessDb = null,
                perceptualLoudnessDb = null,
                playbackTrackingUrl = null,
            )
        }

        companion object {
            private const val TAG = "YtdlnisStreamRepository"
        }
    }

class YtDlpExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
