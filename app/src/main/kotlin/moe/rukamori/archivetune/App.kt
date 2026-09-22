/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import moe.rukamori.archivetune.utils.isLowRamDevice
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeLocale
import moe.rukamori.archivetune.kugou.KuGou
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackManager
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.morideobfuscator.ytdlp.YtDlpJavaScriptRuntime
import moe.rukamori.archivetune.scrobbling.LastFmServiceConfig
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyLibraryRepository
import moe.rukamori.archivetune.storage.StorageFolderKind
import moe.rukamori.archivetune.storage.StorageLocationRepository
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.repository.SearchDiscoveryRepository
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlaybackCache
import moe.rukamori.archivetune.ui.screens.settings.ThemePalettes
import moe.rukamori.archivetune.ui.theme.ThemeSeedPalette
import moe.rukamori.archivetune.ui.theme.ThemeSeedPaletteCodec
import moe.rukamori.archivetune.utils.CanvasResolverEndpoints
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.ProxyUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.clearPlaybackAuthSession
import moe.rukamori.archivetune.utils.clearPlaybackWebAuthSession
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.potoken.BotGuardTokenGenerator
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Proxy
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.system.exitProcess
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.extensions.*

@HiltAndroidApp
class App :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var spotifyLibraryRepository: SpotifyLibraryRepository

    @Inject
    lateinit var searchDiscoveryRepository: SearchDiscoveryRepository

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            Timber.e(error, "Application background initialization failed")
        },
    )

    @Volatile private var isInitialized = false

    @Volatile private var appleMusicDevTokenCache: String = ""
    @Volatile private var appleMusicMediaUserTokenCache: String = ""

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (currentProcessName()?.endsWith(":crash") == true) {
            Timber.plant(Timber.DebugTree())
            return
        }
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build(),
            )
        }
        YtDlpJavaScriptRuntime.initialize(this)
        BotGuardTokenGenerator.initialize(this)

        moe.rukamori.archivetune.echo.utils.cipher.CipherDeobfuscator.initialize(this)
        PreferenceStore.start(this)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        try {
            Timber.plant(
                moe.rukamori.archivetune.utils
                    .GlobalLogTree(),
            )
        } catch (_: Exception) {
        }

        initializeCriticalSync()
        initializeDeferredAsync()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

    }

    private fun initializeDiskBackedComponents() {
        runCatching {
            val config = com.downloader.PRDownloaderConfig.newBuilder()
                .setReadTimeout(90_000)
                .setConnectTimeout(15_000)
                .setUserAgent("ArchiveTune/${BuildConfig.VERSION_NAME}")
                .build()
            com.downloader.PRDownloader.initialize(this, config)
        }
        JapaneseLanguagePackManager.initialize(this)
        moe.rukamori.archivetune.lyrics.AiLyricsRomanization.attach(this)
        CanvasArtworkPlaybackCache.init(this)
    }

    private fun initializeCriticalSync() {
        initializeDiskBackedComponents()

        AppleMusicProvider.logger = { level, tag, message ->
            moe.rukamori.archivetune.utils.GlobalLog.append(level, tag, message)
        }

        applicationScope.launch(Dispatchers.IO) {
            var lastMedia = appleMusicMediaUserTokenCache
            dataStore.data.collect { prefs ->
                val newDev = prefs[AppleMusicDevTokenKey]?.trim().orEmpty()
                val newMedia = prefs[AppleMusicMediaUserTokenKey]?.trim().orEmpty()
                if (newMedia != lastMedia) {
                    lastMedia = newMedia
                    AppleMusicProvider.clearStorefrontCache()
                }
                appleMusicDevTokenCache = newDev
                appleMusicMediaUserTokenCache = newMedia
            }
        }
        AppleMusicProvider.devTokenProvider = {
            appleMusicDevTokenCache.ifBlank { null }
        }
        AppleMusicProvider.mediaUserTokenProvider = {
            appleMusicMediaUserTokenCache.ifBlank { null }

                ?: PoolAccountManager.appleMusicAccounts().firstOrNull()?.mediaUserToken
        }

        Spotify.logger = { level, message ->
            moe.rukamori.archivetune.utils.GlobalLog.append(
                when (level) {
                    "E" -> android.util.Log.ERROR
                    "W" -> android.util.Log.WARN
                    else -> android.util.Log.DEBUG
                },
                "Spotify",
                message,
            )
        }

        SpotifyCanvasProvider.logger = { message ->
            moe.rukamori.archivetune.utils.GlobalLog.append(
                android.util.Log.INFO,
                "SpotifyCanvas",
                message,
            )
        }

        SpotifyCanvasProvider.tokenProvider = { spotifyLibraryRepository.ensureAccessToken() }
        SpotifyCanvasProvider.trackUriResolver = { _, title, artist ->

            spotifyLibraryRepository.ensureAccessToken()
            resolveSpotifyTrackUri(title, artist)
        }
        SpotifyCanvasProvider.extraResolverEndpointsProvider = {
            CanvasResolverEndpoints.parse(dataStore.get(CanvasResolverEndpointsKey, ""))
        }

        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                if (appleMusicDevTokenCache.isBlank()) AppleMusicProvider.refreshToken()
                YouTube.currentPlaybackAuthState().sessionId?.takeIf { it.isNotBlank() }?.let {
                    BotGuardTokenGenerator.preWarm(it)
                }
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            runCatching { moe.rukamori.archivetune.telegram.TelegramClient.startIfSessionExists(this@App) }
                .onFailure { Timber.w(it, "Telegram session restore failed") }
        }

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "")
        YouTube.locale =
            YouTubeLocale(
                gl = locale.country.takeIf { it in CountryCodeToName } ?: "US",
                hl =
                    locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )
        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET,
        )
    }

    private fun initializeDeferredAsync() {

        moe.rukamori.archivetune.utils.SourceRefreshWorker.schedule(this)

        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                searchDiscoveryRepository.loadDiscovery(forceRefresh = false)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()

                prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                    YouTube.locale = YouTube.locale.copy(gl = country)
                }
                prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                    YouTube.locale = YouTube.locale.copy(hl = lang)
                }
                prefs[YouTubeMusicRegionKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { regionValue ->
                    YouTube.locale = YouTube.locale.copy(gl = regionValue)
                    YouTube.regionSpooferActive = true
                }

                LastFmServiceConfig.fromPreferences(prefs).apply(prefs[LastFMSessionKey])

                ProxyUtils.applyYouTubeProxy(
                    enabled = prefs[ProxyEnabledKey] == true,
                    type = prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                    host = prefs[ProxyHostKey],
                    port = prefs[ProxyPortKey],
                    username = prefs[ProxyUsernameKey],
                    password = prefs[ProxyPasswordKey],
                )
                YouTube.streamBypassProxy = YouTube.proxy != null && prefs[StreamBypassProxyKey] == true

                if (prefs[IpRotationEnabledKey] == true) {
                    runCatching { YouTube.enableIpRotation() }
                        .onFailure { Timber.w(it, "IP rotation restore failed") }
                }
                YouTube.useLoginForBrowse = prefs[UseLoginForBrowse] != false
                YouTube.authState = prefs.toPlaybackAuthState()
                applyDnsConfiguration(
                    enabled = prefs[EnableDnsOverHttpsKey] ?: false,
                    provider = prefs[DnsOverHttpsProviderKey] ?: "Cloudflare",
                    customUrl = prefs[stringPreferencesKey("customDnsUrl")] ?: "https://",
                )
                appleMusicDevTokenCache = prefs[AppleMusicDevTokenKey]?.trim().orEmpty()
                appleMusicMediaUserTokenCache = prefs[AppleMusicMediaUserTokenKey]?.trim().orEmpty()
                DeezerAudioProvider.setManualArl(prefs[DeezerArlKey].orEmpty(), prefs[DeezerAccountPremiumKey] ?: false)

                if (prefs[UseLoginForBrowse] != false) {
                    YouTube.useLoginForBrowse = true
                }

                if (prefs[RandomThemeOnStartupKey] == true) {
                    val randomPalette = ThemePalettes.generateRandomPalette()
                    val seedPalette =
                        ThemeSeedPalette(
                            primary = randomPalette.primary,
                            secondary = randomPalette.secondary,
                            tertiary = randomPalette.tertiary,
                            neutral = randomPalette.neutral,
                        )
                    val encodedPalette = ThemeSeedPaletteCodec.encodeForPreference(seedPalette, "Random")
                    dataStore.edit { settings ->
                        settings[CustomThemeColorKey] = encodedPalette
                    }
                }

                isInitialized = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error during deferred initialization")
                reportException(e)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                if (dataStore.get(TidalEnabledKey, true)) {
                    dataStore.get(TidalLastProbeTrackKey)?.takeIf { it.isNotBlank() }?.let {
                        if (TidalAudioProvider.lastResolvedTrackId.isNullOrBlank()) {
                            TidalAudioProvider.seedProbeTrack(it)
                        }
                    }

                    val autoDiscover = BuildConfig.SOURCE_PROVIDER_URL.isNotBlank()
                    TidalInstanceHealthManager.refresh(this@App, includeDiscovery = autoDiscover, staggered = true)
                }
            } catch (e: Exception) {
                Timber.w(e, "Tidal instance startup health scan failed")
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                if (PoolAccountManager.isEnabled) {
                    PoolAccountManager.loadCached(this@App)
                    PoolAccountManager.refresh(this@App)
                }
            } catch (e: Exception) {
                Timber.w(e, "Pool account startup refresh failed")
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                if (dataStore.get(QobuzEnabledKey, false)) {
                    dataStore.get(QobuzLastProbeTrackKey)?.takeIf { it.isNotBlank() }?.let {
                        QobuzAudioProvider.seedProbeTrack(it)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Qobuz probe-track restore failed")
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map {
                    Triple(
                        it[EnableDnsOverHttpsKey] ?: false,
                        it[DnsOverHttpsProviderKey] ?: "Cloudflare",
                        it[stringPreferencesKey("customDnsUrl")] ?: "https://",
                    )
                }.distinctUntilChanged()
                .collect { (enabled, provider, customUrl) ->
                    applyDnsConfiguration(enabled, provider, customUrl)
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { (it[DeezerArlKey] ?: "") to (it[DeezerAccountPremiumKey] ?: false) }
                .distinctUntilChanged()
                .collect { (arl, premium) ->
                    DeezerAudioProvider.setManualArl(arl, premium)
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState() }
                .distinctUntilChanged()
                .collect { authState ->
                    val previousFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                    YouTube.authState = authState
                    if (previousFingerprint != authState.fingerprint) {
                        YTPlayerUtils.clearPlaybackAuthCaches()
                        val sessionId = authState.sessionId
                        if (!sessionId.isNullOrBlank()) {
                            if (YouTube.currentPlaybackAuthState().sessionId == sessionId) {
                                BotGuardTokenGenerator.preWarm(sessionId)
                            }
                        }
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState().visitorData }
                .distinctUntilChanged()
                .collect { visitorData ->
                    if (!visitorData.isNullOrBlank()) return@collect
                    YouTube
                        .visitorData()
                        .onFailure {
                            reportException(it)
                        }.getOrNull()
                        ?.also { newVisitorData ->
                            dataStore.edit { settings ->
                                settings[VisitorDataKey] = newVisitorData
                            }
                        }
                }
        }

        if (BuildConfig.DEVICE != "automotive") {
            try {
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        val sw = StringWriter()
                        val pw = PrintWriter(sw)
                        throwable.printStackTrace(pw)
                        val stack = sw.toString()

                        val intent =
                            Intent(this@App, DebugActivity::class.java).apply {
                                putExtra(DebugActivity.EXTRA_STACK_TRACE, stack)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        startActivity(intent)
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    } finally {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(2)
                    }
                }
            } catch (e: Exception) {
                reportException(e)
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { prefs ->
                    LastFmServiceConfig.fromPreferences(prefs) to prefs[LastFMSessionKey]
                }.distinctUntilChanged()
                .collect { (serviceConfig, sessionKey) ->
                    serviceConfig.apply(sessionKey)
                }
        }
    }

    private fun applyDnsConfiguration(enabled: Boolean, provider: String, customUrl: String) {
        val url = when (provider) {
            "Google" -> "https://dns.google/dns-query"
            "Cloudflare" -> "https://cloudflare-dns.com/dns-query"
            "AdGuard" -> "https://dns.adguard.com/dns-query"
            "Quad9" -> "https://dns.quad9.net/dns-query"
            "Custom" -> customUrl
            else -> null
        }
        YouTube.dns = if (enabled && url?.startsWith("https://") == true) {
            runCatching { YouTube.createDnsOverHttps(url) }
                .onFailure { Timber.w(it, "Could not configure DNS over HTTPS") }
                .getOrDefault(Dns.SYSTEM)
        } else {
            Dns.SYSTEM
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {

        val imageCacheConfig = resolveImageDiskCacheConfig(dataStore[MaxImageCacheSizeKey])
        val lowRam = isLowRamDevice()

        val diskCache =
            DiskCache
                .Builder()
                .directory(StorageLocationRepository.cacheDirectory(this, StorageFolderKind.IMAGE_CACHE))
                .maxSizeBytes(imageCacheConfig.maxSizeBytes)
                .build()

        val imageHttpClient =
            OkHttpClient
                .Builder()
                .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        return ImageLoader
            .Builder(this)
            .components {
                add(moe.rukamori.archivetune.telegram.TelegramThumbnailFetcher.Factory())
                add(OkHttpNetworkFetcherFactory(imageHttpClient))
                // GIF support for the Listen Together chat's Giphy shares: the
                // API 28+ ImageDecoder path animates natively, BitmapFactory's
                // GifDecoder covers everything older.
                if (Build.VERSION.SDK_INT >= 28) {
                    add(coil3.gif.AnimatedImageDecoder.Factory())
                } else {
                    add(coil3.gif.GifDecoder.Factory())
                }
            }
            .crossfade(!lowRam)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(this@App, if (lowRam) 0.15 else 0.25)
                    .build()
            }.memoryCachePolicy(CachePolicy.ENABLED)
            .diskCache(diskCache)
            .diskCachePolicy(imageCacheConfig.policy)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set

        fun forgetAccount(
            context: Context,
            clearWebAuthSession: Boolean = true,
        ) {
            if (clearWebAuthSession) {
                clearPlaybackWebAuthSession(context)
            }
            CoroutineScope(Dispatchers.IO).launch {
                context.dataStore.edit { settings ->
                    settings.clearPlaybackAuthSession()
                }
            }
        }
    }
}

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

private suspend fun resolveSpotifyTrackUri(
    title: String?,
    artist: String?,
): String? {
    if (!Spotify.isAuthenticated()) return null
    val cleanTitle = title?.trim().orEmpty()
    val cleanArtist = artist?.trim().orEmpty()
    if (cleanTitle.isBlank()) return null

    val query = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
    val tracks =
        Spotify
            .search(query = query, types = listOf("track"), limit = 5)
            .getOrNull()
            ?.tracks
            ?.items
            .orEmpty()
    if (tracks.isEmpty()) return null

    fun matchesTitle(name: String): Boolean =
        name.equals(cleanTitle, ignoreCase = true) ||
            name.contains(cleanTitle, ignoreCase = true) ||
            cleanTitle.contains(name, ignoreCase = true)

    fun matchesArtist(names: List<String>): Boolean =
        cleanArtist.isBlank() ||
            names.any { candidate ->
                candidate.isNotBlank() &&
                    (
                        candidate.equals(cleanArtist, ignoreCase = true) ||
                            candidate.contains(cleanArtist, ignoreCase = true) ||
                            cleanArtist.contains(candidate, ignoreCase = true)
                    )
            }

    val match =
        tracks.firstOrNull { track ->
            matchesTitle(track.name) && matchesArtist(track.artists.map { it.name })
        } ?: return null

    return match.uri?.takeIf { it.startsWith("spotify:track:") }
        ?: match.id.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" }
}

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: 512
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 1L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val bytesPerMb = 1024L * 1024L
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * bytesPerMb)
}
