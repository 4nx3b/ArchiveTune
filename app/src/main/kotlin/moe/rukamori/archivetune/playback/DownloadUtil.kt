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
import androidx.media3.common.C
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.constants.DownloadSourceKey
import moe.rukamori.archivetune.constants.DownloadSourceOrderKey
import moe.rukamori.archivetune.constants.DeezerAudioQuality
import moe.rukamori.archivetune.constants.DeezerAudioQualityKey
import moe.rukamori.archivetune.constants.QobuzAudioQuality
import moe.rukamori.archivetune.constants.QobuzAudioQualityKey
import moe.rukamori.archivetune.constants.SaavnAudioQuality
import moe.rukamori.archivetune.constants.SaavnAudioQualityKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.TidalAudioQualityKey
import moe.rukamori.archivetune.constants.AppleMusicQuality
import moe.rukamori.archivetune.constants.AppleMusicQualityKey
import moe.rukamori.archivetune.constants.toFormatId
import moe.rukamori.archivetune.constants.toFormatName
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.applemusic.AppleMusicVirtualStream
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.TitleMatch
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.SongArtistMap
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.deezer.DeezerCrypto
import moe.rukamori.archivetune.deezer.DeezerDecryptingDataSource
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.audiosource.SongSourceOverride
import moe.rukamori.archivetune.audiosource.SongSourceQobuzBackupVideoId
import moe.rukamori.archivetune.audiosource.SongSourceQobuzTrackId
import moe.rukamori.archivetune.constants.SongSourceOverrideKey
import moe.rukamori.archivetune.constants.SongSourceQobuzBackupVideoIdKey
import moe.rukamori.archivetune.constants.SongSourceQobuzTrackIdKey
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.preference
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import kotlinx.coroutines.flow.first
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal class DownloadSnapshotState<T> {
    private val lock = Any()
    private val changedBeforeSnapshot = mutableSetOf<String>()
    private var initialized = false
    private val values = MutableStateFlow<Map<String, T>>(emptyMap())
    val flow = values.asStateFlow()

    fun put(id: String, value: T) = synchronized(lock) {
        if (!initialized) changedBeforeSnapshot += id
        values.value = values.value + (id to value)
    }

    fun remove(id: String) = synchronized(lock) {
        if (!initialized) changedBeforeSnapshot += id
        values.value = values.value - id
    }

    fun initialize(snapshot: Map<String, T>) = synchronized(lock) {
        if (initialized) return
        values.value = snapshot.filterKeys { it !in changedBeforeSnapshot } + values.value
        initialized = true
        changedBeforeSnapshot.clear()
    }
}

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

        private val appContext: Context = context

        private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        private val downloadSource by enumPreference(context, DownloadSourceKey, DownloadSource.AUTO)

        private val downloadSourceOrderCsv by preference(context, DownloadSourceOrderKey, "")
        private val downloadSourceOrder: List<DownloadSource>
            get() = DownloadSourceConfig.parseOrder(downloadSourceOrderCsv)
        private val qobuzAudioQuality by enumPreference(context, QobuzAudioQualityKey, QobuzAudioQuality.FLAC)
        private val tidalAudioQuality by enumPreference(context, TidalAudioQualityKey, TidalAudioQuality.FLAC)
        private val saavnAudioQuality by enumPreference(context, SaavnAudioQualityKey, SaavnAudioQuality.QUALITY_320)
        private val deezerAudioQuality by enumPreference(context, DeezerAudioQualityKey, DeezerAudioQuality.FLAC)
        private val appleMusicQuality by enumPreference(context, AppleMusicQualityKey, AppleMusicQuality.LOSSLESS)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()

        private val autoRetryCounts = ConcurrentHashMap<String, Int>()

        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)

        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(8, TimeUnit.SECONDS)

                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .dispatcher(
                    okhttp3.Dispatcher().apply {

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

                        val patched =
                            request
                                .newBuilder()
                                .header("Accept-Encoding", "identity")
                                .header("Connection", "keep-alive")

                        if (host.endsWith("kouzu.in") && request.header("x-request-source").isNullOrEmpty()) {
                            patched.header("x-request-source", "muzo")
                        }
                        return@addInterceptor chain.proceed(patched.build())
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

        fun prewarmDownloadConnections() {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                for (host in PREWARM_HOSTS) {
                    runCatching {
                        val request = Request.Builder()
                            .url("https://$host/")
                            .head()
                            .build()
                        mediaOkHttpClient.newCall(request).execute().use {  }
                    }
                }
            }
        }

        private val downloadState = DownloadSnapshotState<Download>()
        val downloads = downloadState.flow

        private val okHttpDataSourceFactory =
            PRDownloaderDataSource.Factory(context)

        private data class SongSourcePreferences(
            val overrideSource: AudioSourceType?,
            val directQobuzTrackId: String?,
            val directQobuzBackupVideoId: String?,
        )

        private fun readSongSourcePreferences(mediaId: String): SongSourcePreferences =
            runCatching {
                runBlocking(Dispatchers.IO) {
                    val prefs = appContext.dataStore.data.first()
                    SongSourcePreferences(
                        overrideSource = SongSourceOverride.get(prefs[SongSourceOverrideKey], mediaId),
                        directQobuzTrackId = SongSourceQobuzTrackId.get(prefs[SongSourceQobuzTrackIdKey], mediaId),
                        directQobuzBackupVideoId =
                            SongSourceQobuzBackupVideoId.get(prefs[SongSourceQobuzBackupVideoIdKey], mediaId),
                    )
                }
            }.getOrDefault(SongSourcePreferences(null, null, null))

        private fun downloadSourceForAudioSource(source: AudioSourceType): DownloadSource? =
            when (source) {
                AudioSourceType.YOUTUBE -> DownloadSource.YOUTUBE_MUSIC
                else ->
                    runCatching { DownloadSource.valueOf(source.name) }.getOrNull()
            }

        private fun downloadSourceChain(songPrefs: SongSourcePreferences): List<DownloadSource> {
            val chainSources =
                downloadSourceOrder
                    .takeWhile { it != DownloadSource.YOUTUBE_MUSIC }
                    .filter { it != DownloadSource.AUTO }
            val overridden = songPrefs.overrideSource
                ?.let(::downloadSourceForAudioSource)
                ?.takeIf { it != DownloadSource.YOUTUBE_MUSIC && it != DownloadSource.AUTO }
            return if (overridden != null && overridden !in chainSources) {
                listOf(overridden) + chainSources
            } else {
                chainSources
            }
        }

        data class DownloadTarget(
            val source: DownloadSource,
            val key: String,
            val legacyIds: List<String>,
        )

        fun currentSourceDownloadTarget(mediaId: String): DownloadTarget {
            val songPrefs = readSongSourcePreferences(mediaId)
            val overrideSource = songPrefs.overrideSource
                ?.let(::downloadSourceForAudioSource)
                ?.takeIf { it != null && it != DownloadSource.AUTO }
            val source =
                overrideSource
                    ?: downloadSourceChain(songPrefs).firstOrNull()
                    ?: DownloadSource.YOUTUBE_MUSIC
            val key = DownloadSourceConfig.downloadCacheKey(source, mediaId)
            val legacyIds =
                if (source == DownloadSource.YOUTUBE_MUSIC) {
                    listOf(mediaId)
                } else {
                    emptyList()
                }
            return DownloadTarget(source, key, legacyIds)
        }

        fun currentSourceDownloadIds(mediaId: String): List<String> {
            val target = currentSourceDownloadTarget(mediaId)
            return (listOf(target.key) + target.legacyIds).distinct()
        }

        fun clearCurrentTargetCacheSpans(mediaId: String) {
            val target = currentSourceDownloadTarget(mediaId)
            val keys = (listOf(target.key) + target.legacyIds).distinct()
            keys.forEach { key ->
                runCatching { downloadCache.removeResource(key) }
                runCatching { playerCache.removeResource(key) }
            }
        }

        fun removeSongCacheEntries(mediaId: String) {
            val keys = buildList {
                add(mediaId)
                addAll(DownloadSourceConfig.CACHE_KEY_PREFIXES.map { "$it$mediaId" })
            }
            keys.forEach { key ->
                runCatching { downloadCache.removeResource(key) }
                runCatching { playerCache.removeResource(key) }
            }
        }

        private fun removeDownloadCacheEntriesForRequest(requestId: String) {
            val keys = buildSet {
                add(requestId)
                if (!requestId.contains(':')) {
                    add(DownloadSourceConfig.YOUTUBE_MUSIC_CACHE_KEY_PREFIX + requestId)
                }
            }
            keys.forEach { key ->
                runCatching { downloadCache.removeResource(key) }
                runCatching { playerCache.removeResource(key) }
            }
        }

        private val playerCacheDownloadUpstreamFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setUpstreamDataSourceFactory(okHttpDataSourceFactory)
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

                        CacheDataSink
                            .Factory()
                            .setCache(downloadCache)
                            .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                            .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE),
                    ),
            ) { dataSpec ->
                val requestKey = dataSpec.key ?: error("No media id")
                val mediaId = DownloadSourceConfig.downloadIdToSongId(requestKey)

                val songSourcePrefs = readSongSourcePreferences(mediaId)

                val shortCircuitKeys = listOf(requestKey)
                for (key in shortCircuitKeys) {
                    val expectedForKey =
                        runCatching {
                            playerCache
                                .getContentMetadata(key)
                                .get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                        }.getOrDefault(-1L).takeIf { it > 0L }
                            ?: database.getSongByIdBlocking(mediaId)?.format?.contentLength?.takeIf { it > 0L }
                            ?: 0L
                    if (expectedForKey > 0L) {
                        val cachedBytes = runCatching {
                            playerCache.getCachedSpans(key).sumOf { it.length }
                        }.getOrDefault(0L)
                        if (cachedBytes >= expectedForKey) {
                            return@Factory dataSpec.buildUpon().setKey(key).build()
                        }
                    }
                }

                val targetSource = DownloadSourceConfig.downloadSourceForCacheKey(requestKey)

                if (targetSource != null && targetSource != DownloadSource.YOUTUBE_MUSIC) {
                    val song = database.getSongByIdBlocking(mediaId)
                    if (song != null) {
                        val title = song.song.title.takeIf { it.isNotBlank() } ?: mediaId
                        val resolved =
                            runCatching {
                                resolveSourceStream(
                                    targetSource,
                                    mediaId,
                                    title,
                                    song.artists.mapNotNull { it.name.takeIf(String::isNotBlank) },
                                    song.album?.title?.takeIf { it.isNotBlank() },
                                    song.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                                    directQobuzTrackId = songSourcePrefs.directQobuzTrackId,
                                    directQobuzBackupVideoId = songSourcePrefs.directQobuzBackupVideoId,
                                )
                            }.getOrNull()
                        if (resolved != null) {
                            persistSourceFormatEntity(
                                mediaId = mediaId,
                                mimeType = resolved.mimeType,
                                codecs = resolved.codecs,
                                contentLength = resolved.contentLength,
                            )
                            if (targetSource == DownloadSource.APPLE) {
                                val appleFile = runCatching { File(resolved.uri.toUri().path ?: "") }.getOrNull()
                                if (appleFile != null && appleFile.isFile &&
                                    copyLocalFileIntoPlayerCache(appleFile, requestKey)
                                ) {
                                    return@Factory dataSpec
                                }
                            } else {
                                return@Factory dataSpec
                                    .buildUpon()
                                    .setKey(requestKey)
                                    .setUri(resolved.uri.toUri())
                                    .setHttpRequestHeaders(
                                        dataSpec.httpRequestHeaders + requiredHeadersFor(resolved.uri),
                                    ).build()
                            }
                        }
                    }
                }

                if (requestKey == mediaId) {
                    val expectedLength = database.getSongByIdBlocking(mediaId)?.format?.contentLength ?: 0L
                    for (source in downloadSourceChain(songSourcePrefs)) {
                        if (source == DownloadSource.YOUTUBE_MUSIC) continue
                        val sourceKey = DownloadSourceConfig.downloadCacheKey(source, mediaId)
                        if (expectedLength > 0L) {
                            val cachedBytes = runCatching {
                                playerCache.getCachedSpans(sourceKey).sumOf { it.length }
                            }.getOrDefault(0L)
                            if (cachedBytes >= expectedLength) {
                                return@Factory dataSpec.buildUpon().setKey(sourceKey).build()
                            }
                        }
                    }

                    val lowDataModeActive = context.isLowDataModeActive()
                    if (!lowDataModeActive) {
                        resolvePreferredDownloadDataSpec(dataSpec, mediaId, songSourcePrefs)?.let { return@Factory it }
                    }
                }

                val lowDataMode = context.isLowDataModeActive()
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataMode)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
                val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                songUrlCache[streamCacheKey]
                    ?.takeIf {
                        it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    }?.let {
                        return@Factory dataSpec.buildUpon().setKey(requestKey).setUri(it.url.toUri()).build()
                    }
                val playbackData =
                    try {
                        runBlocking(Dispatchers.IO) {
                            kotlinx.coroutines.withTimeout(YT_DOWNLOAD_RESOLVE_TIMEOUT_MS) {
                                context.retryWithoutPlaybackLoginContext {
                                    YTPlayerUtils.playerResponseForDownload(
                                        mediaId,
                                        audioQuality = requestedAudioQuality,
                                        connectivityManager = connectivityManager,
                                        networkMetered = lowDataMode,
                                    )
                                }
                            }
                        }.getOrThrow()
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        throw IOException(
                            "YouTube download stream resolution timed out after " +
                                "${YT_DOWNLOAD_RESOLVE_TIMEOUT_MS / 1000}s for $mediaId",
                            timeout,
                        )
                    }
                persistPlaybackMetadata(mediaId, playbackData)

                val streamUrl = playbackData.streamUrl

                songUrlCache[streamCacheKey] =
                    AuthScopedCacheValue(
                        url = streamUrl,
                        expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                        authFingerprint = playbackData.authFingerprint,
                    )
                dataSpec.buildUpon().setKey(requestKey).setUri(streamUrl.toUri()).build()
            }

        private val telegramDataSourceFactory = moe.rukamori.archivetune.telegram.TelegramDataSource.Factory()

        private val deezerDownloadDataSourceFactory =
            CacheDataSource
                .Factory()
                .setCache(downloadCache)
                .setCacheKeyFactory(DownloadRequestCacheKeyFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setUpstreamDataSourceFactory(
                    DeezerDecryptingDataSource.Factory(okHttpDataSourceFactory),
                ).setCacheWriteDataSinkFactory(
                    CacheDataSink
                        .Factory()
                        .setCache(downloadCache)
                        .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                        .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE),
                )

        private val dataSourceFactory =
            DataSource.Factory {
                DownloadSchemeRoutingDataSource(
                    youtubeFactory = youtubeDataSourceFactory,
                    telegramFactory = telegramDataSourceFactory,
                    deezerFactory = deezerDownloadDataSourceFactory,
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
                                val failedMediaId = DownloadSourceConfig.downloadIdToSongId(download.request.id)
                                songUrlCache.keys.removeIf { it.startsWith("$failedMediaId:") }
                                removeDownloadCacheEntriesForRequest(download.request.id)

                                val retryCount =
                                    autoRetryCounts.merge(download.request.id, 1, Int::plus) ?: 1
                                if (retryCount <= 1) {
                                    downloadScope.launch {
                                        delay(DOWNLOAD_AUTO_RETRY_DELAY_MS)
                                        runCatching { downloadManager.addDownload(download.request) }
                                    }
                                }
                            }
                            if (download.state == Download.STATE_COMPLETED ||
                                download.state == Download.STATE_REMOVING
                            ) {
                                autoRetryCounts.remove(download.request.id)
                            }
                            downloadState.put(download.request.id, download)
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            val removedKey = download.request.id
                            runCatching { downloadCache.removeResource(removedKey) }
                            runCatching { playerCache.removeResource(removedKey) }
                            if (!removedKey.contains(':')) {
                                val ytmKey = DownloadSourceConfig.YOUTUBE_MUSIC_CACHE_KEY_PREFIX + removedKey
                                runCatching { downloadCache.removeResource(ytmKey) }
                                runCatching { playerCache.removeResource(ytmKey) }
                            }
                            autoRetryCounts.remove(removedKey)
                            downloadState.remove(removedKey)
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                try {
                    downloadManager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            val download = cursor.download
                            result[download.request.id] = download
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.e(error, "Could not load the download index")
                }
                downloadState.initialize(result)
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

        fun getDownload(songId: String): Flow<Download?> =
            downloads.map { map ->
                currentSourceDownloadIds(songId).firstNotNullOfOrNull { map[it] }
                    ?: DownloadSourceConfig
                        .songIdToDownloadIds(songId)
                        .firstNotNullOfOrNull { id ->
                            map[id]?.takeIf { it.state == Download.STATE_COMPLETED }
                        }
            }

        suspend fun prewarmSongForDownload(mediaId: String): String? {
            if (PoolAccountManager.isEnabled) {
                runCatching {
                    kotlinx.coroutines.withTimeout(POOL_REFRESH_TIMEOUT_MS) {
                        PoolAccountManager.refresh(appContext)
                    }
                }
            }

            val songSourcePrefs = readSongSourcePreferences(mediaId)
            val target = currentSourceDownloadTarget(mediaId)

            val priorityProbeKeys =
                (
                    listOf(target.key) +
                        downloadSourceChain(songSourcePrefs)
                            .map { DownloadSourceConfig.downloadCacheKey(it, mediaId) } +
                        (DownloadSourceConfig.YOUTUBE_MUSIC_CACHE_KEY_PREFIX + mediaId)
                    ).distinct() + mediaId
            for (key in priorityProbeKeys) {
                val spans = runCatching { downloadCache.getCachedSpans(key) }.getOrNull().orEmpty()
                if (spans.isNotEmpty()) {
                    val expected = expectedDownloadLengthFor(key, mediaId)
                    val cachedBytes = spans.sumOf { it.length }
                    if (expected > 0L && cachedBytes >= expected) {
                        return key
                    }
                }
                val partialPlayerSpans = runCatching { playerCache.getCachedSpans(key) }.getOrNull().orEmpty()
                if (partialPlayerSpans.isNotEmpty()) {
                    val expected = expectedDownloadLengthFor(key, mediaId)
                    val cachedBytes = partialPlayerSpans.sumOf { it.length }
                    if (expected <= 0L || cachedBytes < expected) {
                        runCatching {
                            partialPlayerSpans.forEach { playerCache.removeSpan(it) }
                        }
                    }
                }
            }

            val lowDataModeActive = appContext.isLowDataModeActive()
            val song = database.getSongByIdBlocking(mediaId)
            if (song != null && target.source != DownloadSource.YOUTUBE_MUSIC) {
                val title = song.song.title.takeIf { it.isNotBlank() }
                val artists = song.artists.mapNotNull { it.name.takeIf(String::isNotBlank) }
                val album = song.album?.title?.takeIf { it.isNotBlank() }
                val durationMs = song.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L)
                if (title != null) {

                    val sourceOrder: List<DownloadSource> = downloadSourceChain(songSourcePrefs)
                    for (source in sourceOrder) {
                        val resolved = runCatching {
                            resolveSourceStream(
                                source,
                                mediaId,
                                title,
                                artists,
                                album,
                                durationMs,
                                directQobuzTrackId = songSourcePrefs.directQobuzTrackId,
                                directQobuzBackupVideoId = songSourcePrefs.directQobuzBackupVideoId,
                            )
                        }.getOrNull() ?: continue
                        if (resolved == null) continue
                        persistSourceFormatEntity(
                            mediaId = mediaId,
                            mimeType = resolved.mimeType,
                            codecs = resolved.codecs,
                            contentLength = resolved.contentLength,
                        )
                        val cacheKey = DownloadSourceConfig.downloadCacheKey(source, mediaId)

                        if (source == DownloadSource.APPLE) {
                            val appleFile = runCatching { File(resolved.uri.toUri().path ?: "") }.getOrNull()
                            if (appleFile != null && appleFile.isFile &&
                                copyLocalFileIntoPlayerCache(appleFile, cacheKey)
                            ) {
                                return cacheKey
                            }
                            continue
                        }

                        val fetched = runCatching {
                            fetchStreamIntoPlayerCache(resolved.uri, cacheKey, resolved.contentLength)
                        }.isSuccess
                        if (fetched) return cacheKey
                    }
                }
            }

            if (target.source == DownloadSource.YOUTUBE_MUSIC) {
                return null
            }

            val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
            val playbackData = runCatching {
                kotlinx.coroutines.withTimeout(YT_DOWNLOAD_RESOLVE_TIMEOUT_MS) {
                    appContext.retryWithoutPlaybackLoginContext {
                        YTPlayerUtils.playerResponseForDownload(
                            mediaId,
                            audioQuality = requestedAudioQuality,
                            connectivityManager = connectivityManager,
                            networkMetered = lowDataModeActive,
                        )
                    }.getOrThrow()
                }
            }.getOrNull() ?: return null
            persistPlaybackMetadata(mediaId, playbackData)
            val ytDownloadKey = DownloadSourceConfig.YOUTUBE_MUSIC_CACHE_KEY_PREFIX + mediaId
            val fetched = runCatching {
                fetchStreamIntoPlayerCache(
                    playbackData.streamUrl,
                    ytDownloadKey,

                    playbackData.format.contentLength,
                )
            }.isSuccess
            return if (fetched) ytDownloadKey else null
        }

        private fun expectedDownloadLengthFor(
            key: String,
            mediaId: String,
        ): Long =
            runCatching {
                playerCache
                    .getContentMetadata(key)
                    .get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            }.getOrDefault(-1L).takeIf { it > 0L }
                ?: runCatching {
                    downloadCache
                        .getContentMetadata(key)
                        .get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                }.getOrDefault(-1L).takeIf { it > 0L }
                ?: database.getSongByIdBlocking(mediaId)?.format?.contentLength?.takeIf { it > 0L }
                ?: 0L

        private fun fetchStreamIntoPlayerCache(
            url: String,
            cacheKey: String,
            knownContentLength: Long?,
        ): Boolean {
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "keep-alive")
                .build()
            return runCatching {
                mediaOkHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} fetching $url")
                    }
                    val contentLength = knownContentLength
                        ?: response.header("Content-Length")?.toLongOrNull()
                        ?: -1L

                    val dataSpec = DataSpec.Builder()
                        .setUri(url.toUri())
                        .setKey(cacheKey)
                        .setPosition(0L)
                        .setLength(if (contentLength > 0) contentLength else C.LENGTH_UNSET.toLong())
                        .build()
                    val cacheSink = CacheDataSink.Factory()
                        .setCache(playerCache)
                        .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                        .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE)
                        .createDataSink()
                    val buffer = ByteArray(DOWNLOAD_WRITE_BUFFER_SIZE)
                    try {
                        cacheSink.open(dataSpec)
                        try {
                            response.body?.byteStream()?.use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    cacheSink.write(buffer, 0, read)
                                }
                            }

                        } finally {
                            runCatching { cacheSink.close() }
                        }

                        val spans = playerCache.getCachedSpans(cacheKey)
                        if (spans.isEmpty()) throw IOException("Cache empty after fetch for $cacheKey")
                        if (contentLength > 0) {
                            val cachedBytes = spans.sumOf { it.length }
                            if (cachedBytes < contentLength) {

                                runCatching { playerCache.removeResource(cacheKey) }
                                throw IOException("Partial cache: $cachedBytes / $contentLength bytes for $cacheKey")
                            }
                        }
                        true
                    } catch (e: Exception) {

                        runCatching { playerCache.removeResource(cacheKey) }
                        throw e
                    }
                }
            }.onFailure { e ->
                Timber.tag("DownloadUtil").w(e, "prewarm fetch failed for %s", cacheKey)
            }.getOrDefault(false)
        }

        private fun resolvePreferredDownloadDataSpec(
            dataSpec: DataSpec,
            mediaId: String,
            songSourcePrefs: SongSourcePreferences,
        ): DataSpec? {
            if (downloadSource == DownloadSource.YOUTUBE_MUSIC) return null
            val song = database.getSongByIdBlocking(mediaId) ?: return null
            val queryTitle = song.song.title.takeIf { it.isNotBlank() } ?: return null
            val artists = song.artists.mapNotNull { it.name.takeIf(String::isNotBlank) }
            val album = song.album?.title?.takeIf { it.isNotBlank() }
            val durationMs = song.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L)

            val sourceOrder: List<DownloadSource> = downloadSourceChain(songSourcePrefs)

            for (source in sourceOrder) {
                val resolved = runCatching {
                    resolveSourceStream(
                        source,
                        mediaId,
                        queryTitle,
                        artists,
                        album,
                        durationMs,
                        directQobuzTrackId = songSourcePrefs.directQobuzTrackId,
                        directQobuzBackupVideoId = songSourcePrefs.directQobuzBackupVideoId,
                    )
                }.getOrNull() ?: continue

                if (source == DownloadSource.DEEZER) {
                    enrichSongMetadataFromDeezer(mediaId, queryTitle, artists, album, durationMs)
                }
                if (resolved == null) continue

                persistSourceFormatEntity(
                    mediaId = mediaId,
                    mimeType = resolved.mimeType,
                    codecs = resolved.codecs,
                    contentLength = resolved.contentLength,
                )

                if (source == DownloadSource.APPLE) {
                    val appleKey = DownloadSourceConfig.downloadCacheKey(source, mediaId)
                    val appleFile = runCatching { File(resolved.uri.toUri().path ?: "") }.getOrNull()
                    if (appleFile != null && appleFile.isFile &&
                        copyLocalFileIntoPlayerCache(appleFile, appleKey)
                    ) {
                        return dataSpec.buildUpon()
                            .setKey(appleKey)
                            .build()
                    }

                    continue
                }

                return dataSpec.buildUpon()
                    .setUri(resolved.uri.toUri())
                    .setKey(DownloadSourceConfig.downloadCacheKey(source, mediaId))

                    .setHttpRequestHeaders(dataSpec.httpRequestHeaders + requiredHeadersFor(resolved.uri))
                    .build()
            }
            return null
        }

        private fun resolveSourceStream(
            source: DownloadSource,
            mediaId: String,
            title: String,
            artists: List<String>,
            album: String?,
            durationMs: Long?,
            directQobuzTrackId: String? = null,
            directQobuzBackupVideoId: String? = null,
        ): ResolvedStreamData? = when (source) {
            DownloadSource.QOBUZ -> {
                LosslessStreamResolver.resolveQobuz(
                    context = appContext,
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    formatId = qobuzAudioQuality.toFormatId(),
                    directTrackId = directQobuzTrackId,
                )?.let { ResolvedStreamData(it.uri, it.mimeType, it.codecs, it.contentLength) }
            }
            DownloadSource.TIDAL -> {
                LosslessStreamResolver.resolveTidal(
                    context = appContext,
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    audioQuality = tidalAudioQuality,
                    cacheDir = appContext.cacheDir,
                )?.let { ResolvedStreamData(it.uri, it.mimeType, it.codecs, it.contentLength) }
            }
            DownloadSource.QOBUZ_BACKUP -> {

                LosslessStreamResolver
                    .resolveQobuzBackup(directQobuzBackupVideoId ?: mediaId)
                    ?.let { ResolvedStreamData(it.uri, it.mimeType, it.codecs, it.contentLength) }
            }
            DownloadSource.DEEZER -> {

                LosslessStreamResolver.resolveDeezer(
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    format = deezerAudioQuality.toFormatName(),
                )?.let { ResolvedStreamData(it.uri, it.mimeType, it.codecs, it.contentLength) }
            }
            DownloadSource.JIOSAAVN -> {
                LosslessStreamResolver.resolveJioSaavn(
                    mediaId = mediaId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    qualityApiValue = saavnAudioQuality.toApiValue(),
                )?.let { ResolvedStreamData(it.uri, it.mimeType, it.codecs, it.contentLength) }
            }
            DownloadSource.APPLE -> {

                resolveAppleDownloadStream(mediaId, title, artists, album, durationMs)
            }

            // Amazon serves CENC-protected fragmented MP4 and this fork ships no decryption step
            // (see AmazonEnabledKey in PreferenceKeys.kt), so no download stream can be produced
            // here — the chain skips Amazon and falls through to the next source.
            DownloadSource.AMAZON -> null
            DownloadSource.AUTO, DownloadSource.YOUTUBE_MUSIC -> null
        }

        private fun resolveAppleDownloadStream(
            mediaId: String,
            title: String,
            artists: List<String>,
            album: String?,
            durationMs: Long?,
        ): ResolvedStreamData? {
            if (AppleMusicAudioProvider.mediaUserToken() == null ||
                AppleMusicAudioProvider.devToken() == null
            ) {
                Timber.tag("DownloadUtil").d("Apple Music source: missing tokens (sign in via Settings → Apple Music)")
                return null
            }
            val candidates =
                runBlocking(Dispatchers.IO) {
                    AppleMusicAudioProvider.resolveCandidates(
                        title = title,
                        artists = artists,
                        album = album,
                        durationMs = durationMs,
                        quality = appleMusicQuality,
                    )
                }
            if (candidates.isEmpty()) return null

            var winner: AppleMusicAudioProvider.AppleMusicStream? = null
            var bestScore = -1.0
            for (candidate in candidates) {
                val stream =
                    DirectStream(
                        uri = "apple-pending:${candidate.songId}",
                        mimeType = "audio/mp4",
                        codecs =
                            if (candidate.flavor.contains("ctrp", ignoreCase = true) &&
                                candidate.flavor.filter(Char::isDigit).toIntOrNull()?.let { it > 320 } == true
                            ) {
                                "alac"
                            } else {
                                "mp4a.40.2"
                            },
                        contentLength = candidate.contentLength,
                        label = "Apple Music ${candidate.flavor}",
                        source = AudioSourceType.APPLE,
                        matchedTitle = candidate.matchedTitle,
                        matchedArtist = candidate.matchedArtist,
                        matchedAlbum = candidate.matchedAlbum,
                        matchedDurationMs = candidate.matchedDurationMs,
                    )
                val match = TitleMatch.evaluate(
                    wantedTitle = title,
                    wantedArtists = artists,
                    wantedAlbum = album,
                    wantedDurationMs = durationMs,
                    stream = stream,
                )
                if (match.accepted && match.score > bestScore) {
                    winner = candidate
                    bestScore = match.score
                }
            }
            val candidate = winner ?: return null

            return try {
                val file = appleDownloadStreamFile(mediaId)
                if (!file.exists() || file.length() == 0L) {
                    val built =
                        AppleMusicVirtualStream.build(
                            mediaOkHttpClient,
                            candidate.playlistUrl,
                            candidate.keyIdHex,
                        )
                    file.writeBytes(built.bytes)
                }
                Timber
                    .tag("DownloadUtil")
                    .i("Apple Music resolved [%s] for \"%s\" (%d KB)", candidate.flavor, title, file.length() / 1024)
                ResolvedStreamData(
                    uri = Uri.fromFile(file).toString(),
                    mimeType = "audio/mp4",
                    codecs =
                        if (candidate.flavor.contains("ctrp", ignoreCase = true) &&
                            candidate.flavor.filter(Char::isDigit).toIntOrNull()?.let { it > 320 } == true
                        ) {
                            "alac"
                        } else {
                            "mp4a.40.2"
                        },
                    contentLength = file.length(),
                )
            } catch (err: Throwable) {
                Timber.tag("DownloadUtil").w(err, "Apple Music virtual stream failed for \"%s\"", title)
                null
            }
        }

        private fun appleDownloadStreamFile(mediaId: String): File {
            val dir = File(appContext.cacheDir, "applemusic").apply { mkdirs() }
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= 300L * 1024 * 1024) break
                total -= f.length()
                f.delete()
            }
            return File(dir, "$mediaId.m4a")
        }

        private fun copyLocalFileIntoPlayerCache(
            file: File,
            cacheKey: String,
        ): Boolean =
            runCatching {
                val length = file.length()
                if (length <= 0L) return@runCatching false
                val dataSpec =
                    DataSpec.Builder()
                        .setUri(Uri.fromFile(file))
                        .setKey(cacheKey)
                        .setPosition(0L)
                        .setLength(length)
                        .build()
                val cacheSink =
                    CacheDataSink
                        .Factory()
                        .setCache(playerCache)
                        .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE)
                        .setFragmentSize(DOWNLOAD_FRAGMENT_SIZE)
                        .createDataSink()
                val buffer = ByteArray(DOWNLOAD_WRITE_BUFFER_SIZE)
                cacheSink.open(dataSpec)
                try {
                    file.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            cacheSink.write(buffer, 0, read)
                        }
                    }
                } finally {

                    runCatching { cacheSink.close() }
                }
                val spans = playerCache.getCachedSpans(cacheKey)
                if (spans.isEmpty()) return@runCatching false
                val cachedBytes = spans.sumOf { it.length }
                if (cachedBytes < length) {
                    runCatching { playerCache.removeResource(cacheKey) }
                    return@runCatching false
                }
                true
            }.getOrDefault(false)

        private fun requiredHeadersFor(uri: String): Map<String, String> =
            if (runCatching { uri.toUri().host }.getOrNull()?.endsWith("kouzu.in") == true) {
                mapOf("x-request-source" to "muzo")
            } else {
                emptyMap()
            }

        private data class ResolvedStreamData(
            val uri: String,
            val mimeType: String,
            val codecs: String,
            val contentLength: Long?,
        )

        private fun enrichSongMetadataFromDeezer(
            mediaId: String,
            title: String,
            artists: List<String>,
            album: String?,
            durationMs: Long?,
        ) {
            downloadScope.launch {
                runCatching {
                    val resolved = moe.rukamori.archivetune.deezer.DeezerAudioProvider.lookup(
                        moe.rukamori.archivetune.deezer.DeezerAudioProvider.Query(
                            mediaId = mediaId,
                            title = title,
                            artists = artists,
                            album = album,
                            durationMs = durationMs,
                        ),
                    ) ?: return@launch
                    database.query {
                        val existing = getSongByIdBlocking(mediaId)?.song ?: return@query
                        val newThumb = existing.thumbnailUrl?.takeIf(String::isNotBlank)
                            ?: resolved.coverUrl
                        val newAlbum = existing.albumName?.takeIf(String::isNotBlank)
                            ?: resolved.album
                        if (newThumb == existing.thumbnailUrl && newAlbum == existing.albumName) {
                            return@query
                        }
                        upsert(
                            existing.copy(
                                thumbnailUrl = newThumb,
                                albumName = newAlbum,
                            ),
                        )
                    }
                }
            }
        }

        private fun persistSourceFormatEntity(
            mediaId: String,
            mimeType: String,
            codecs: String,
            contentLength: Long?,
        ) {
            val normalizedMime = mimeType.ifBlank { "audio/flac" }.substringBefore(";")

            runCatching {
                database.upsert(
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
                        val existingSongRow = getSongByIdBlocking(mediaId)
                        val existing = existingSongRow?.song
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

                        val videoDetails = playbackData.videoDetails
                        val hasArtistMap = existingSongRow?.artists?.isNotEmpty() == true
                        if (!hasArtistMap && videoDetails != null) {
                            val authorName = videoDetails.author?.takeIf { it.isNotBlank() }
                            val channelId = videoDetails.channelId?.takeIf { it.isNotBlank() }
                            if (authorName != null) {

                                val artistId = channelId ?: "UCYT:${mediaId}"

                                val cleanArtistName = authorName
                                    .removeSuffix(" - Topic")
                                    .removeSuffix("- Topic")
                                    .trim()
                                    .ifBlank { authorName }
                                upsert(
                                    ArtistEntity(
                                        id = artistId,
                                        name = cleanArtistName,
                                        channelId = channelId,
                                    ),
                                )
                                insert(
                                    SongArtistMap(
                                        songId = mediaId,
                                        artistId = artistId,
                                        position = 0,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        private class DownloadSchemeRoutingDataSource(
            private val youtubeFactory: DataSource.Factory,
            private val telegramFactory: DataSource.Factory,
            private val deezerFactory: DataSource.Factory,
        ) : DataSource {
            private val transferListeners = mutableListOf<TransferListener>()
            private var delegate: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                transferListeners += transferListener
                delegate?.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme?.lowercase(java.util.Locale.US)
                val selected =
                    when (scheme) {
                        "telegram" -> telegramFactory
                        DeezerCrypto.SCHEME -> deezerFactory
                        else -> youtubeFactory
                    }
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

            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 12

            internal const val YT_DOWNLOAD_RESOLVE_TIMEOUT_MS = 120_000L


            internal const val DOWNLOAD_AUTO_RETRY_DELAY_MS = 4_000L

            internal const val POOL_REFRESH_TIMEOUT_MS = 10_000L

            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 96
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 256
            private const val MAX_DOWNLOAD_HTTP_REQUESTS_PER_HOST = 96
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 10L

            internal const val DOWNLOAD_WRITE_BUFFER_SIZE = 16 * 1024 * 1024

            internal const val DOWNLOAD_FRAGMENT_SIZE = 128L * 1024 * 1024

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
