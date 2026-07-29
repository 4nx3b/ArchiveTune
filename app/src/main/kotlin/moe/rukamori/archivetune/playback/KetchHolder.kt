/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import com.ketch.DownloadConfig
import com.ketch.Ketch
import moe.rukamori.archivetune.innertube.YouTube
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Process-wide holder for the Ketch downloader instance.
 *
 * Ketch is initialized once at app start (see [App.kt]) with a tuned
 * OkHttp client that mirrors the playback/download client used elsewhere
 * in the app — HTTP/2 multiplexing, generous read timeouts for large
 * FLAC files, and the YouTube stream proxy.
 *
 * [KetchHttpDataSource] retrieves the instance via [get] so it can be
 * used as the upstream HTTP fetcher inside Media3's [CacheDataSource]
 * chain. Keeping the holder separate from [DownloadUtil] avoids a
 * circular Hilt dependency (DownloadUtil injects Ketch via this holder
 * rather than requiring a Ketch binding in the DI graph).
 */
object KetchHolder {
    @Volatile
    private var instance: Ketch? = null

    fun init(context: Context): Ketch {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val client = OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .connectionPool(
                    ConnectionPool(
                        /* maxIdleConnections = */ 32,
                        /* keepAliveDuration = */ 10,
                        TimeUnit.MINUTES,
                    ),
                ).protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) {
                        // Hint binary content + disable transparent
                        // compression (already-encoded audio just wastes
                        // CPU re-compressing).
                        return@addInterceptor chain.proceed(
                            request
                                .newBuilder()
                                .header("Accept-Encoding", "identity")
                                .header("Connection", "keep-alive")
                                .build(),
                        )
                    }

                    val requestProfile =
                        moe.rukamori.archivetune.utils.StreamClientUtils.resolveRequestProfile(request.url)
                    chain.proceed(
                        moe.rukamori.archivetune.utils.StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                }.build()

            val ketch = Ketch
                .builder()
                .setOkHttpClient(client)
                .setDownloadConfig(
                    DownloadConfig(
                        connectTimeOutInMs = 8_000L,
                        readTimeOutInMs = 60_000L,
                    ),
                ).enableLogs(false)
                .build(context.applicationContext)
            instance = ketch
            return ketch
        }
    }

    fun get(): Ketch =
        instance ?: error("KetchHolder not initialized — call KetchHolder.init(context) in App.onCreate()")

    /**
     * Returns the Ketch instance or null if it hasn't been initialized.
     * Used by [KetchHttpDataSource.Factory] so it can fail soft instead
     * of throwing during DI graph construction.
     */
    fun getOrNull(): Ketch? = instance
}
