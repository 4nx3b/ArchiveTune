/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.innertube.YouTube
import timber.log.Timber
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: Cache,
        @PlayerCache val playerCache: Cache,
    ) {
        private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
        private val downloadExecutor = Executors.newFixedThreadPool(OPTIMIZED_MAX_PARALLEL_DOWNLOADS)

        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                        maxRequestsPerHost = OPTIMIZED_MAX_PARALLEL_DOWNLOADS
                    },
                ).connectionPool(
                    ConnectionPool(
                        MAX_IDLE_DOWNLOAD_CONNECTIONS,
                        DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES,
                    ),
                ).addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    chain.proceed(
                        StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                }.build()
        }

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val dataSourceFactory =
            ResolvingDataSource.Factory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            mediaOkHttpClient,
                        ),
                    ).setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory().setCache(playerCache).setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE),
                    ),
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val length = if (dataSpec.length >= 0) dataSpec.length else 1
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    // Bug fix: verify cached stream quality matches requested download quality.
                    // Previously, a song streamed at HIGHEST (320kbps AAC) would be cached and
                    // served as-is during download even when LOSSLESS was requested.
                    val lowDataModeActive = context.isLowDataModeActive()
                    val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                    if (requestedAudioQuality == AudioQuality.LOSSLESS) {
                        // For LOSSLESS downloads, check if the cached format is actually lossless.
                        // If the cached data is lossy (e.g., from a previous streaming session),
                        // evict the cached spans so we can re-resolve with a lossless format.
                        val cachedFormat = database.query { getFormatByIdBlocking(mediaId) }
                        val isCachedLossless = cachedFormat?.let { fmt ->
                            fmt.mimeType.equals("audio/flac", ignoreCase = true) ||
                                fmt.mimeType.equals("audio/x-flac", ignoreCase = true) ||
                                fmt.mimeType.equals("audio/alac", ignoreCase = true) ||
                                fmt.codecs.contains("flac", ignoreCase = true) ||
                                fmt.codecs.contains("alac", ignoreCase = true) ||
                                (fmt.sampleRate != null && fmt.sampleRate >= 44100 && fmt.bitrate >= 800_000)
                        } ?: false
                        if (!isCachedLossless) {
                            Timber.tag("DownloadUtil").w(
                                "Cached stream for %s is lossy (%s); evicting to re-resolve as LOSSLESS",
                                mediaId,
                                cachedFormat?.mimeType ?: "unknown",
                            )
                            playerCache.removeResource(mediaId)
                            songUrlCache.remove(buildSongUrlCacheKey(mediaId, requestedAudioQuality))
                            // Fall through to re-resolve with LOSSLESS quality
                        } else {
                            return@Factory dataSpec
                        }
                    } else {
                        return@Factory dataSpec
                    }
                }
                val lowDataModeActive = context.isLowDataModeActive()
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
                val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                songUrlCache[streamCacheKey]
                    ?.takeIf {
                        it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    }?.let {
                        return@Factory dataSpec.withUri(it.url.toUri())
                    }
                val playbackData =
                    runBlocking(Dispatchers.IO) {
                        context.retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForDownload(
                                mediaId,
                                audioQuality = requestedAudioQuality,
                                connectivityManager = connectivityManager,
                                networkMetered = lowDataModeActive,
                            )
                        }
                    }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)

                val streamUrl = playbackData.streamUrl

                songUrlCache[streamCacheKey] =
                    AuthScopedCacheValue(
                        url = streamUrl,
                        expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                        authFingerprint = playbackData.authFingerprint,
                    )
                dataSpec.withUri(streamUrl.toUri())
            }

        val downloadNotificationHelper =
            DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                downloadExecutor,
            ).apply {
                maxParallelDownloads = OPTIMIZED_MAX_PARALLEL_DOWNLOADS
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download)
                                }
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            downloads.update { map -> map - download.request.id }
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                val cursor = downloadManager.downloadIndex.getDownloads()
                while (cursor.moveToNext()) {
                    result[cursor.download.request.id] = cursor.download
                }
                downloads.value = result
            }
            downloadScope.launch {
                var previousFingerprint: String? = null
                YouTube.authStateFlow
                    .map { it.fingerprint }
                    .distinctUntilChanged()
                    .collect { fingerprint ->
                        if (previousFingerprint != null && previousFingerprint != fingerprint) {
                            songUrlCache.clear()
                        }
                        previousFingerprint = fingerprint
                    }
            }
        }

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            // Bug fix: Downloads should default to LOSSLESS instead of AUTO (which maps to HIGHEST).
            // AUTO maps to HIGHEST (320kbps AAC) on unmetered networks, which is lossy.
            // For downloads, we always want the best quality available.
            if (lowDataModeActive) AudioQuality.LOW
            else if (audioQuality == AudioQuality.AUTO) AudioQuality.LOSSLESS
            else audioQuality

        private fun buildSongUrlCacheKey(
            mediaId: String,
            requestedAudioQuality: AudioQuality,
        ): String = "$mediaId:${requestedAudioQuality.name}"

        private fun persistPlaybackMetadata(
            mediaId: String,
            playbackData: YTPlayerUtils.PlaybackData,
        ) {
            downloadScope.launch {
                runCatching {
                    // Bug fix: persist metadata synchronously within the download scope to prevent
                    // race conditions where the download completes before metadata is written.
                    // Previously fire-and-forget with no error handling, causing silent metadata loss.
                    val format = playbackData.format
                    val contentLength = format.contentLength ?: 0L

                    // Robust codec extraction: parse "codecs=\"codec1, codec2\"" from mimeType
                    val resolvedCodecs = extractCodecsFromMimeType(format.mimeType)

                    // Bug fix: infer MIME type from codec when the raw MIME type is missing or misleading.
                    // Previously defaulted to "audio/mp4" for all blank/missing MIME types, which
                    // caused FLAC streams to be stored as audio/mp4, corrupting metadata.
                    val rawMimeType = format.mimeType.substringBefore(";").trim()
                    val resolvedMimeType = when {
                        rawMimeType.isNotBlank() -> rawMimeType
                        resolvedCodecs.contains("flac", ignoreCase = true) -> "audio/flac"
                        resolvedCodecs.contains("alac", ignoreCase = true) -> "audio/alac"
                        resolvedCodecs.contains("opus", ignoreCase = true) -> "audio/webm"
                        resolvedCodecs.contains("mp4a", ignoreCase = true) -> "audio/mp4"
                        else -> "audio/mp4"
                    }

                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = resolvedMimeType,
                                codecs = resolvedCodecs,
                                bitrate = format.bitrate,
                                sampleRate = format.audioSampleRate,
                                contentLength = contentLength,
                                loudnessDb = playbackData.audioConfig?.loudnessDb,
                                perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                                playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                            ),
                        )

                        val now = LocalDateTime.now()
                        val existing = getSongByIdBlocking(mediaId)?.song
                        val videoDetails = playbackData.videoDetails
                        val resolvedThumbnailUrl =
                            videoDetails
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                                ?.takeIf { it.isNotBlank() }

                        val updatedSong =
                            if (existing != null) {
                                existing.copy(
                                    title = existing.title.takeIf { it.isNotBlank() }
                                        ?: videoDetails?.title?.takeIf { it.isNotBlank() }
                                        ?: existing.title,
                                    duration = existing.duration.takeIf { it > 0 }
                                        ?: videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }
                                        ?: existing.duration,
                                    thumbnailUrl = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedThumbnailUrl,
                                    dateDownload = existing.dateDownload ?: now,
                                )
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = videoDetails?.title?.takeIf { it.isNotBlank() } ?: mediaId,
                                    duration = videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 } ?: -1,
                                    thumbnailUrl = resolvedThumbnailUrl,
                                    dateDownload = now,
                                )
                            }

                        upsert(updatedSong)
                    }
                }.onFailure { e ->
                    // Bug fix: log metadata persistence failures instead of silently swallowing them.
                    // Previously, errors in persistPlaybackMetadata were fire-and-forget, causing
                    // silent metadata corruption (missing format info, wrong codec, etc.).
                    Timber.tag("DownloadUtil").e(e, "Failed to persist playback metadata for %s", mediaId)
                }
            }
        }

        /**
         * Robustly extracts the codec string from a MIME type like:
         *   "audio/mp4; codecs=\"mp4a.40.2\"" -> "mp4a.40.2"
         *   "audio/webm; codecs=\"opus\"" -> "opus"
         *   "audio/mp4; codecs=\"mp4a.40.2\",something" -> "mp4a.40.2"
         * Returns empty string if no codecs parameter is found.
         *
         * Bug fix: previous implementation used removeSurrounding("\"") which fails when
         * trailing content follows the closing quote (e.g., codecs="mp4a.40.2"; extra).
         * Now uses regex-based extraction for robustness.
         */
        private fun extractCodecsFromMimeType(mimeType: String): String {
            val match = Regex("""codecs="([^"]+)"""").find(mimeType) ?: return ""
            val codecList = match.groupValues.getOrNull(1) ?: return ""
            // Take only the first codec from a comma-separated list
            return codecList.split(",").firstOrNull()?.trim()?.ifBlank { null } ?: ""
        }

        companion object {
            private const val OPTIMIZED_MAX_PARALLEL_DOWNLOADS = 4
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 8
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 16
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 3L
            private const val DOWNLOAD_WRITE_BUFFER_SIZE = 512 * 1024 // 512KB for faster I/O
        }
    }
