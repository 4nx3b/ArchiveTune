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
import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
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
import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.constants.DownloadSourceKey
import moe.rukamori.archivetune.constants.QobuzAudioQuality
import moe.rukamori.archivetune.constants.QobuzAudioQualityKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.TidalAudioQualityKey
import moe.rukamori.archivetune.constants.toFormatId
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private val downloadSource by enumPreference(context, DownloadSourceKey, DownloadSource.YOUTUBE_MUSIC)
        private val qobuzAudioQuality by enumPreference(context, QobuzAudioQualityKey, QobuzAudioQuality.FLAC)
        private val tidalAudioQuality by enumPreference(context, TidalAudioQualityKey, TidalAudioQuality.FLAC)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)

        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS) // no overall cap — let large FLAC files download to completion
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        // Aggressive parallelism: allow up to MAX_DOWNLOAD_HTTP_REQUESTS
                        // concurrent HTTP requests in flight, with up to MAX_DOWNLOAD_HTTP_REQUESTS
                        // of them hitting the same host (googlevideo.com / qobuz / tidal). HTTP/2
                        // multiplexing means each host connection can carry many requests, so the
                        // per-host cap effectively becomes the per-connection concurrency cap.
                        maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                        maxRequestsPerHost = MAX_DOWNLOAD_HTTP_REQUESTS_PER_HOST
                    },
                ).connectionPool(
                    ConnectionPool(
                        MAX_IDLE_DOWNLOAD_CONNECTIONS,
                        DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES,
                    ),
                ).protocols(
                    // Force HTTP/2 over HTTP/1.1 when available — HTTP/2 multiplexes
                    // many requests over a single TCP connection, eliminating the
                    // per-request TCP+TLS handshake cost (~100-300ms each).
                    listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1),
                ).addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) {
                        // For non-YouTube hosts (Qobuz / Tidal / iTunes / Deezer), hint
                        // that we want a binary stream and disable any transparent
                        // gzip/br compression — compressed audio is already efficiently
                        // encoded and re-compressing it just wastes CPU.
                        return@addInterceptor chain.proceed(
                            request
                                .newBuilder()
                                .header("Accept-Encoding", "identity")
                                .header("Connection", "keep-alive")
                                .build(),
                        )
                    }

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

        /**
         * Pre-warms DNS + TLS connections to the most common download hosts so
         * the first download of a session doesn't pay the ~300-800ms
         * DNS+TCP+TLS handshake cost. Safe to call on a background coroutine
         * at app start; failures are silently swallowed (it's only an
         * optimization, not a hard requirement).
         */
        fun prewarmDownloadConnections() {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                for (host in PREWARM_HOSTS) {
                    runCatching {
                        val request = Request.Builder()
                            .url("https://$host/")
                            .head()
                            .build()
                        mediaOkHttpClient.newCall(request).execute().use { /* discard */ }
                    }
                }
            }
        }

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val cachedPlaybackDataSourceFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(mediaOkHttpClient))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        /**
         * Read-only view of [playerCache] used as an inner upstream of the download chain.
         *
         * The download [CacheDataSource] is bound to [downloadCache]; without this inner layer,
         * any byte range present in [playerCache] (e.g. a song that was just streamed from Qobuz
         * or YouTube) but absent from [downloadCache] would fall through to [OkHttpDataSource]
         * holding the *bare media id* the [DownloadRequest] carried (e.g. "dJth8oW7CAQ"), which
         * is not a valid URL — producing `HttpDataSourceException: Malformed URL` and failing
         * the download at 0%.
         *
         * Chaining [playerCache] as the next upstream means: downloadCache miss → playerCache hit
         * → serve bytes (and write them through to downloadCache so subsequent chunks persist).
         * Only when *both* caches miss do we reach [OkHttpDataSource], by which point the
         * [ResolvingDataSource] resolver below has already swapped the URI for a real YouTube
         * stream URL.
         */
        private val playerCacheDownloadUpstreamFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(mediaOkHttpClient))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        private val youtubeDataSourceFactory =
            ResolvingDataSource.Factory(
                CacheDataSource
                    .Factory()
                    .setCache(downloadCache)
                    .setCacheKeyFactory(DownloadRequestCacheKeyFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .setUpstreamDataSourceFactory(playerCacheDownloadUpstreamFactory)
                    .setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory().setCache(downloadCache).setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE),
                    ),
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val length = if (dataSpec.length >= 0) dataSpec.length else 1
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    return@Factory dataSpec
                }
                // Check source-specific player-cache keys (Qobuz, Tidal) so that
                // downloading a song that was streamed from an external lossless
                // source saves the actual lossless data instead of re-downloading
                // from YouTube Music. The keys match MusicService.sourceCacheKey().
                // The chained [playerCacheDownloadUpstreamFactory] reads these source-specific
                // keys directly from playerCache — no YouTube URL resolution is needed when the
                // lossless bytes are already cached.
                for (sourcePrefix in listOf("qobuz:", "tidal:")) {
                    val sourceKey = "$sourcePrefix$mediaId"
                    if (playerCache.isCached(sourceKey, dataSpec.position, length)) {
                        return@Factory dataSpec.buildUpon().setKey(sourceKey).build()
                    }
                }
                val lowDataModeActive = context.isLowDataModeActive()
                if (!lowDataModeActive) {
                    resolvePreferredDownloadDataSpec(dataSpec, mediaId)?.let { return@Factory it }
                }
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

        // Route downloads by scheme: telegram:// tracks stream through TDLib (same as playback),
        // everything else through the YouTube-resolving factory above.
        private val telegramDataSourceFactory = moe.rukamori.archivetune.telegram.TelegramDataSource.Factory()

        private val dataSourceFactory =
            DataSource.Factory {
                DownloadSchemeRoutingDataSource(
                    youtubeFactory = youtubeDataSourceFactory,
                    telegramFactory = telegramDataSourceFactory,
                )
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
                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            if (finalException != null || download.state == Download.STATE_FAILED) {
                                songUrlCache.keys.removeIf { it.startsWith("${download.request.id}:") }
                                runCatching { downloadCache.removeResource(download.request.id) }
                            }
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


        private fun resolvePreferredDownloadDataSpec(
            dataSpec: DataSpec,
            mediaId: String,
        ): DataSpec? {
            if (downloadSource == DownloadSource.YOUTUBE_MUSIC) return null
            val song = database.getSongByIdBlocking(mediaId) ?: return null
            val queryTitle = song.song.title.takeIf { it.isNotBlank() } ?: return null
            val artists = song.artists.mapNotNull { it.name.takeIf(String::isNotBlank) }
            val album = song.album?.title?.takeIf { it.isNotBlank() }
            val durationMs = song.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L)
            // Resolve the source-specific direct stream and pull out the
            // metadata we need to (a) build the DataSpec and (b) persist a
            // FormatEntity so future exports read the correct MIME/codec
            // (FLAC for Qobuz/Tidal lossless) instead of defaulting to MP3.
            data class ResolvedStream(
                val uri: String,
                val mimeType: String,
                val codecs: String,
                val contentLength: Long?,
            )
            val resolvedStream =
                runCatching {
                    when (downloadSource) {
                        DownloadSource.QOBUZ ->
                            QobuzAudioProvider.resolve(
                                QobuzAudioProvider.Query(mediaId, queryTitle, artists, album, durationMs),
                                qobuzAudioQuality.toFormatId(),
                            )?.let { ResolvedStream(it.uri, it.mimeType, it.codecs, it.contentLength) }
                        DownloadSource.TIDAL ->
                            TidalAudioProvider.resolve(
                                TidalAudioProvider.Query(mediaId, queryTitle, artists, album, null, durationMs),
                                audioQuality = tidalAudioQuality,
                            ).let { ResolvedStream(it.mediaUri, it.mimeType, it.codecs, it.contentLength) }
                        DownloadSource.YOUTUBE_MUSIC -> null
                    }
                }.getOrNull() ?: return null
            persistSourceFormatEntity(
                mediaId = mediaId,
                mimeType = resolvedStream.mimeType,
                codecs = resolvedStream.codecs,
                contentLength = resolvedStream.contentLength,
            )
            return dataSpec.buildUpon()
                .setUri(resolvedStream.uri.toUri())
                .setKey("${downloadSource.name.lowercase(java.util.Locale.US)}:$mediaId")
                .build()
        }

        /**
         * Writes a [FormatEntity] reflecting the resolved lossless/lossy stream
         * (Qobuz/Tidal) so that subsequent exports read the correct codec + MIME
         * type instead of defaulting to MP3.
         */
        private fun persistSourceFormatEntity(
            mediaId: String,
            mimeType: String,
            codecs: String,
            contentLength: Long?,
        ) {
            val normalizedMime = mimeType.ifBlank { "audio/flac" }.substringBefore(";")
            downloadScope.launch {
                runCatching {
                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = 0,
                                mimeType = normalizedMime,
                                codecs = codecs,
                                bitrate = 0,
                                sampleRate = null,
                                contentLength = contentLength ?: 0L,
                                loudnessDb = null,
                                perceptualLoudnessDb = null,
                                playbackUrl = null,
                            ),
                        )
                    }
                }
            }
        }

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            if (lowDataModeActive) AudioQuality.LOW else audioQuality

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
                    val format = playbackData.format
                    val contentLength = format.contentLength ?: 0L
                    val resolvedCodecs =
                        format.mimeType
                            .substringAfter("codecs=", "")
                            .removeSurrounding("\"")
                            .substringBefore("\"")

                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = format.mimeType.split(";")[0],
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
                        val resolvedThumbnailUrl =
                            playbackData.videoDetails
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                                ?.takeIf { it.isNotBlank() }

                        val updatedSong =
                            if (existing != null) {
                                existing.copy(
                                    thumbnailUrl = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedThumbnailUrl,
                                    dateDownload = existing.dateDownload ?: now,
                                )
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = playbackData.videoDetails?.title ?: "Unknown",
                                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                                    thumbnailUrl = resolvedThumbnailUrl,
                                    dateDownload = now,
                                )
                            }

                        upsert(updatedSong)
                    }
                }
            }
        }

        /**
         * Picks the download upstream by URI scheme: `telegram://` tracks go through TDLib (mirroring
         * playback's SchemeRoutingDataSource), everything else through the YouTube-resolving factory.
         */
        private class DownloadSchemeRoutingDataSource(
            private val youtubeFactory: DataSource.Factory,
            private val telegramFactory: DataSource.Factory,
        ) : DataSource {
            private val transferListeners = mutableListOf<TransferListener>()
            private var delegate: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                transferListeners += transferListener
                delegate?.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme?.lowercase(java.util.Locale.US)
                val selected = if (scheme == "telegram") telegramFactory else youtubeFactory
                val source = selected.createDataSource()
                transferListeners.forEach(source::addTransferListener)
                delegate = source
                return source.open(dataSpec)
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = checkNotNull(delegate).read(buffer, offset, length)

            override fun getUri(): Uri? = delegate?.uri

            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

            override fun close() {
                delegate?.close()
                delegate = null
            }
        }

        private object DownloadRequestCacheKeyFactory : CacheKeyFactory {
            override fun buildCacheKey(dataSpec: DataSpec): String = dataSpec.key ?: dataSpec.uri.toString()
        }

        companion object {
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 32
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 64
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 256
            private const val MAX_DOWNLOAD_HTTP_REQUESTS_PER_HOST = 64
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 10L
            // 4MB write buffer — large enough to amortize fsync syscall cost on
            // most filesystems, but small enough that 32 parallel downloads
            // only need ~128MB of heap (vs the previous 512MB at 16MB × 32).
            private const val DOWNLOAD_WRITE_BUFFER_SIZE = 4 * 1024 * 1024

            // Hosts that downloads frequently hit. Pre-warming these at app
            // start means the first download of a session skips the
            // DNS+TCP+TLS handshake (~300-800ms each).
            private val PREWARM_HOSTS = listOf(
                "www.youtube.com",
                "music.youtube.com",
                "r1---sn.googlevideo.com",
                "api.qobuz.com",
                "api.tidal.com",
                "amp-api.tidal.com",
            )
        }
    }
