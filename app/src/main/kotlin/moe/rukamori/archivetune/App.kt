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
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.extensions.*
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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Proxy
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.system.exitProcess

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

        moe.rukamori.archivetune.utils.traceStartup("ArchiveTune.applicationSetup") {
            initializeCriticalSync()
            initializeDeferredAsync()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

    }

    private fun initializeDiskBackedComponents() {
        runCatching {
            val config = com.downloader.PRDownloaderConfig.newBuilder()

                .setReadTimeout(300_000)
                .setConnectTimeout(15_000)
                .setUserAgent("ArchiveTune/${BuildConfig.VERSION_NAME}")
                .build()
            com.downloader.PRDownloader.initialize(this, config)
        }
        JapaneseLanguagePackManager.initialize(this)
        CanvasArtworkPlaybackCache.init(this)
    }

    private fun initializeCriticalSync() {
        PaxsenixLyrics.setUserAgent("ArchiveTune", BuildConfig.VERSION_NAME)
        // Route PaxsenixLyrics diagnostic logs through GlobalLog so they show up
        // in the in-app logcat viewer with the proper tag, instead of going to
        // System.err (which Android redirects to logcat one line at a time as
        // `W/System.err`, with synchronized I/O that causes contention during
        // parallel lyrics prefetch).
        PaxsenixLyrics.logger = { message ->
            moe.rukamori.archivetune.utils.GlobalLog.append(
                android.util.Log.INFO,
                "PaxsenixLyrics",
                message,
            )
        }

        AppleMusicProvider.logger = { level, tag, message ->
            moe.rukamori.archivetune.utils.GlobalLog.append(level, tag, message)
        }

        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.awaitReady()
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

        // Every log() call inside the Spotify client — the whole GraphQL and REST layer — went
        // nowhere: Spotify.logger was declared and never assigned, so the token refreshes, the
        // home-feed parse counts and the "unhandled __typename" diagnostics all evaluated their
        // message strings and dropped them. Routed into GlobalLog like every other provider, so
        // the Spotify layer can actually be debugged from a log dump.
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

        // Spotify Canvas. The canvas module deliberately has no dependency on the
        // app's Spotify code, so it takes the access token and the song → Spotify
        // track mapping as injected callbacks. Both yield null when the user has
        // no Spotify session, in which case the provider falls back to the
        // kouzu.in resolver on its own.
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
            startupReadiness.runOptional {
                runCatching {
                    if (appleMusicDevTokenCache.isBlank()) AppleMusicProvider.refreshToken()
                    PaxsenixLyrics.refreshAmpToken()
                    YouTube.currentPlaybackAuthState().sessionId?.takeIf { it.isNotBlank() }?.let {
                        BotGuardTokenGenerator.preWarm(it)
                    }
                }
            }
        }

        // Only resumes an existing session — see TelegramClient.startIfSessionExists. Starting the
        // client unconditionally mapped TDLib's 21.7 MB native library and started its threads for
        // every user, signed in to Telegram or not.
        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.awaitReady()
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

        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                searchDiscoveryRepository.loadDiscovery(forceRefresh = false)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                startupReadiness.initialize {
                    val prefs = moe.rukamori.archivetune.utils.traceStartupAsync("ArchiveTune.preferences") {
                        PreferenceStore.awaitSnapshot()
                    }
                    initializeDiskBackedComponents()

                    prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                        YouTube.locale = YouTube.locale.copy(gl = country)
                    }
                    prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                        YouTube.locale = YouTube.locale.copy(hl = lang)
                    }
                    // Restore the YouTube Music region override. BOTH halves have to come back: the
                    // `gl` locale override *and* `regionSpooferActive`, which is what forces the
                    // region-sensitive endpoints (home, search, charts, explore, moods, new releases)
                    // to go out anonymously so `gl` is authoritative. Restoring only `gl` — as this
                    // used to — meant spoofing silently stopped working after the very first restart,
                    // including the automatic one that picking a region triggers: the account context
                    // came back and YouTube went on serving the account's home country.
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
                    PaxsenixLyrics.setApiKey(prefs[PaxsenixApiKeyKey].orEmpty())
                    PaxsenixLyrics.setEndpoint(normalizePaxsenixEndpoint(prefs[PaxsenixEndpointKey].orEmpty()))
                    if (PoolAccountManager.isEnabled) PoolAccountManager.loadCached(this@App)

                    if (prefs[UseLoginForBrowse] != false) {
                        YouTube.useLoginForBrowse = true
                    }

                    // Apply random theme on startup if enabled
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
                }
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
                    startupReadiness.runOptional {
                        TidalInstanceHealthManager.refresh(this@App, includeDiscovery = autoDiscover, staggered = true)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Tidal instance startup health scan failed")
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                if (PoolAccountManager.isEnabled) {
                    startupReadiness.runOptional { PoolAccountManager.refresh(this@App) }
                }
            } catch (e: Exception) {
                Timber.w(e, "Pool account startup refresh failed")
            }
        }

        // Re-install the rotating proxy pool when the user left IP rotation on. Without
        // this the toggle in Internet Settings read as ON after every restart while no
        // proxy was actually installed, so rotation appeared to do nothing. Fetching and
        // validating the pool is network-bound, so it runs after the first frame.
        // A pool that validates to nothing leaves rotation off, exactly as before.
        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.runOptional {
                if (dataStore.data.first()[IpRotationEnabledKey] == true) {
                    runCatching { YouTube.enableIpRotation() }
                        .onFailure { Timber.w(it, "IP rotation restore failed") }
                }
            }
        }

        // Restore the Qobuz health-probe track so the settings "Test" action can distinguish a
        // fully-working instance from a preview-only (unsubscribed) one on the first probe.
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
            startupReadiness.awaitReady()
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
            startupReadiness.awaitReady()
            dataStore.data
                .map { (it[DeezerArlKey] ?: "") to (it[DeezerAccountPremiumKey] ?: false) }
                .distinctUntilChanged()
                .collect { (arl, premium) ->
                    DeezerAudioProvider.setManualArl(arl, premium)
                }
        }

        // Observe the user-configured Paxsenix API key + endpoint and apply
        // them to PaxsenixLyrics. When the user changes the key in Settings
        // → Lyrics → Providers → Paxsenix API key, this collector fires and
        // PaxsenixLyrics.setApiKey()/setEndpoint() take effect immediately
        // (the Ktor client reads these vars at request time via
        // defaultRequest {}).
        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.awaitReady()
            dataStore.data
                .map { (it[PaxsenixApiKeyKey] ?: "") to (it[PaxsenixEndpointKey] ?: "") }
                .distinctUntilChanged()
                .collect { (key, endpoint) ->
                    PaxsenixLyrics.setApiKey(key)
                    PaxsenixLyrics.setEndpoint(normalizePaxsenixEndpoint(endpoint))
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.awaitReady()
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
                            applicationScope.launch(Dispatchers.IO) {
                                startupReadiness.runOptional {
                                    if (YouTube.currentPlaybackAuthState().sessionId == sessionId) {
                                        BotGuardTokenGenerator.preWarm(sessionId)
                                    }
                                }
                            }
                        }
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.awaitReady()
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
        applicationScope.launch(Dispatchers.IO) {
            startupReadiness.awaitReady()
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

        // Coil owns the journal and enforces maxSizeBytes with LRU eviction.
        // Deleting files beneath an active DiskCache corrupts its accounting.

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
        val startupReadiness = moe.rukamori.archivetune.utils.StartupReadiness()

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
