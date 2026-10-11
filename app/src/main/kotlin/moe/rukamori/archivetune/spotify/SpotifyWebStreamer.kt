/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Direct Spotify streaming, ported from Stopify (github.com/Anattana-Labs/Stopify,
 * MIT) — the web-player pipeline: Dealer WebSocket -> virtual device registration
 * -> connect-state play command -> manifest file id -> storage-resolve CDN url.
 * The CDN file is a Widevine (CENC) encrypted fMP4; decryption goes through the
 * player's DRM session (see MusicService's Spotify DRM callback), not through
 * this streamer.
 */

package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

object SpotifyWebStreamer {
    private const val TAG = "SpotifyWebStreamer"

    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
    private const val APP_PLATFORM = "WebPlayer"
    private const val SPOTIFY_APP_VERSION = "1.3.5.316.gd873ee9db135"
    private const val CLIENT_VERSION = "harmony:4.80.0-3cdf02c91"
    private const val PLATFORM_IDENTIFIER = "web_player osx 10.15.7;chrome 155.0.0.0;desktop"

    private const val FALLBACK_CLIENT_TOKEN =
        "AAGCCRiw7WlW3Oy33uN5L1dI2knSs986nqFaI1YTbq66MFLs0oeinjyYUbv2zo9+9kleXdls6EnujpM6OElxp5zJtj+0ZP9RU9I0ApWvFeXJwZRYmCVMWBngB3AN6Ux1imVm+8ImYf9OvVc3mJJBjxLH4S3k77qOsXB6EekBL9hZQgx8umkPOLZZEfolSOP8wURVuQX5sZjnwIfPmLf/8ydY/LeeqBGTvkyZwV4InPyVxMlOI7DQViCkvmXQ2ZkpQASm2NrJ40wdF+BcpIDZV0YA+czHR2bx2K+tTKIaNMa7GUTB/By4+ls8IO6TL4zLlLoKGWnXa8N0xzthzHW9B2zdsW/kTD7JttelP1GHt/1lDuiRvvqtp2WGx6ZeArHvStJTLeO+sZHGs6IxJBY="

    private const val DEALER_WS_URL = "wss://gae2-dealer.g2.spotify.com/?access_token="
    private const val DEVICES_URL = "https://gae2-spclient.spotify.com/track-playback/v1/devices"
    private const val STORAGE_RESOLVE_URL = "https://gae2-spclient.spotify.com/storage-resolve/v2/files/audio/interactive/10"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val JS_SDK_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"

    private const val CONNECTION_ID_TIMEOUT_MS = 6_000L
    private const val MANIFEST_TIMEOUT_MS = 4_000L
    private const val CLIENT_TOKEN_TTL_MS = 24L * 60 * 60 * 1000

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private var activeWebSocket: WebSocket? = null
    private var dealerConnectionId: String? = null
    private var connectionIdDeferred: CompletableDeferred<String>? = null
    private var registeredDeviceId: String? = null
    private var registeredDualFormat = false

    private val audioFileMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pendingTrackResolutions = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    private var cachedClientToken: String? = null

    @Volatile
    private var cachedClientTokenAtMs = 0L

    @Volatile
    var accessTokenProvider: (suspend () -> String?)? = null

    fun resetSession() {
        activeWebSocket?.close(1000, "session reset")
        activeWebSocket = null
        dealerConnectionId = null
        connectionIdDeferred = null
        registeredDeviceId = null
        audioFileMap.clear()
    }

    private suspend fun token(): String? = accessTokenProvider?.invoke()

    suspend fun clientToken(): String {
        cachedClientToken?.takeIf { System.currentTimeMillis() - cachedClientTokenAtMs < CLIENT_TOKEN_TTL_MS }?.let { return it }
        val fetched = withContext(Dispatchers.IO) { fetchClientToken() }
        if (fetched != null) {
            cachedClientToken = fetched
            cachedClientTokenAtMs = System.currentTimeMillis()
            return fetched
        }
        return cachedClientToken ?: FALLBACK_CLIENT_TOKEN
    }

    private fun fetchClientToken(): String? =
        runCatching {
            val payload =
                JSONObject().apply {
                    put(
                        "client_data",
                        JSONObject().apply {
                            put("client_version", SPOTIFY_APP_VERSION)
                            put("client_id", JS_SDK_CLIENT_ID)
                            put(
                                "js_sdk_data",
                                JSONObject().apply {
                                    put("device_brand", "Apple")
                                    put("device_model", "unknown")
                                    put("os", "macos")
                                    put("os_version", "10.15.7")
                                    put("device_id", registeredDeviceId ?: "archivetune")
                                    put("device_type", "computer")
                                },
                            )
                        },
                    )
                }
            val request =
                Request
                    .Builder()
                    .url(CLIENT_TOKEN_URL)
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("origin", "https://open.spotify.com")
                    .header("referer", "https://open.spotify.com/")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                JSONObject(body).optJSONObject("granted_token")?.optString("token")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

    private fun connectDealer(token: String) {
        if (activeWebSocket != null) return
        val deferred = CompletableDeferred<String>()
        connectionIdDeferred = deferred
        val request = Request.Builder().url("${DEALER_WS_URL}$token").build()
        activeWebSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val connId = json.optJSONObject("headers")?.optString("Spotify-Connection-Id")
                        if (!connId.isNullOrEmpty() && dealerConnectionId == null) {
                            dealerConnectionId = connId
                            deferred.complete(connId)
                        }
                        if (text.contains("file_ids_mp4")) {
                            val payloads = json.optJSONArray("payloads") ?: return
                            for (p in 0 until payloads.length()) {
                                val stateMachine = payloads.getJSONObject(p).optJSONObject("state_machine") ?: continue
                                val tracks = stateMachine.optJSONArray("tracks") ?: continue
                                for (i in 0 until tracks.length()) {
                                    val trackObj = tracks.getJSONObject(i)
                                    val uri = trackObj.optJSONObject("metadata")?.optString("uri") ?: continue
                                    val manifest = trackObj.optJSONObject("manifest") ?: continue
                                    val fileId =
                                        manifest.optJSONArray("file_ids_mp4")?.optJSONObject(0)?.optString("file_id")
                                            ?: manifest.optJSONArray("file_ids_mp4_dual")?.optJSONObject(0)?.optString("file_id")
                                            ?: continue
                                    if (uri.isNotEmpty() && fileId.isNotEmpty()) {
                                        audioFileMap[uri] = fileId
                                        pendingTrackResolutions[uri]?.complete(fileId)
                                    }
                                }
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Timber.tag(TAG).w(t, "Dealer websocket failed")
                        activeWebSocket = null
                        dealerConnectionId = null
                        connectionIdDeferred = null
                        registeredDeviceId = null
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        activeWebSocket = null
                        dealerConnectionId = null
                        connectionIdDeferred = null
                        registeredDeviceId = null
                    }
                },
            )
    }

    private suspend fun ensureDeviceRegistered(
        token: String,
        clientToken: String,
        deviceId: String,
        dualFormat: Boolean,
    ): Boolean {
        if (registeredDeviceId == deviceId && dealerConnectionId != null && registeredDualFormat == dualFormat) {
            return true
        }
        connectDealer(token)
        val connId =
            dealerConnectionId
                ?: withTimeoutOrNull(CONNECTION_ID_TIMEOUT_MS) { connectionIdDeferred?.await() }
        if (connId == null) {
            Timber.tag(TAG).w("Timed out waiting for Dealer connection ID")
            return false
        }

        val payload =
            JSONObject().apply {
                put(
                    "device",
                    JSONObject().apply {
                        put("brand", "spotify")
                        put(
                            "capabilities",
                            JSONObject().apply {
                                put("change_volume", true)
                                put("enable_play_token", true)
                                put("supports_file_media_type", true)
                                put("play_token_lost_behavior", "pause")
                                put("disable_connect", false)
                                put("audio_podcasts", true)
                                put("video_playback", true)
                                put(
                                    "manifest_formats",
                                    JSONArray().apply {
                                        if (dualFormat) {
                                            put("file_ids_mp4_dual")
                                        } else {
                                            put("file_ids_mp4")
                                        }
                                    },
                                )
                                put("supports_preferred_media_type", true)
                                put("supports_playback_offsets", true)
                                put("supports_playback_speed", true)
                            },
                        )
                        put("device_id", deviceId)
                        put("device_type", "computer")
                        put("metadata", JSONObject())
                        put("model", "web_player")
                        put("name", "ArchiveTune Player")
                        put("platform_identifier", PLATFORM_IDENTIFIER)
                        put("is_group", false)
                        put("is_public", false)
                        put("correlation_id", "9167488b53cb6529d696cc87a46a8235")
                        put("client_version", CLIENT_VERSION)
                    },
                )
                put("outro_endcontent_snooping", false)
                put("connection_id", connId)
                put("client_version", CLIENT_VERSION)
                put("volume", 65535)
            }

        val ok =
            postJson(DEVICES_URL, token, clientToken, payload) { code, _ -> code in 200..299 }
        if (ok) {
            registeredDeviceId = deviceId
            registeredDualFormat = dualFormat
        } else {
            Timber.tag(TAG).w("Device registration failed")
        }
        return ok
    }

    private fun playCommandUrl(deviceId: String) =
        "https://gae2-spclient.spotify.com/connect-state/v1/player/command/from/$deviceId/to/$deviceId"

    private fun buildPlayPayload(trackUri: String): JSONObject =
        JSONObject().apply {
            put(
                "command",
                JSONObject().apply {
                    put(
                        "context",
                        JSONObject().apply {
                            put("uri", trackUri)
                            put("url", "context://$trackUri")
                            put("metadata", JSONObject())
                        },
                    )
                    put(
                        "play_origin",
                        JSONObject().apply {
                            put("feature_identifier", "auto_play_on_load")
                            put("feature_version", "web-player_2026-10-09_1791504000000_d873ee9db135")
                            put("referrer_identifier", "search")
                        },
                    )
                    put(
                        "options",
                        JSONObject().apply {
                            put("license", "tft")
                            put("skip_to", JSONObject())
                            put("player_options_override", JSONObject())
                        },
                    )
                    put(
                        "logging_params",
                        JSONObject().apply {
                            put("page_instance_ids", JSONArray())
                            put("interaction_ids", JSONArray())
                            put("command_id", UUID.randomUUID().toString().replace("-", ""))
                        },
                    )
                    put("endpoint", "play")
                },
            )
        }

    private suspend fun postJson(
        url: String,
        token: String,
        clientToken: String,
        payload: JSONObject,
        timeoutMs: Int = 15_000,
        result: (Int, String) -> Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("authorization", "Bearer $token")
                        .header("client-token", clientToken)
                        .header("content-type", "application/json;charset=UTF-8")
                        .header("app-platform", APP_PLATFORM)
                        .header("spotify-app-version", SPOTIFY_APP_VERSION)
                        .header("origin", "https://open.spotify.com")
                        .header("referer", "https://open.spotify.com/")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                client
                    .newBuilder()
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        val body = response.body?.string().orEmpty()
                        result(response.code, body)
                    }
            }.getOrDefault(false)
        }

    suspend fun resolveStreamUrl(
        trackUri: String,
        deviceId: String,
        dualFormat: Boolean,
    ): String? {
        val token = token() ?: run {
            Timber.tag(TAG).d("No Spotify access token; direct streaming unavailable")
            return null
        }
        val clientToken = clientToken()

        var fileId = audioFileMap[trackUri]
        if (fileId == null) {
            if (!ensureDeviceRegistered(token, clientToken, deviceId, dualFormat)) {
                return null
            }
            val deferred = CompletableDeferred<String>()
            pendingTrackResolutions[trackUri] = deferred
            try {
                val payload = buildPlayPayload(trackUri)
                val commandUrl = playCommandUrl(deviceId)
                var accepted =
                    postJson(commandUrl, token, clientToken, payload) { code, _ -> code in 200..299 }
                if (!accepted) {

                    activeWebSocket?.close(1000, "re-register")
                    activeWebSocket = null
                    dealerConnectionId = null
                    connectionIdDeferred = null
                    registeredDeviceId = null
                    if (ensureDeviceRegistered(token, clientToken, deviceId, dualFormat)) {
                        accepted = postJson(commandUrl, token, clientToken, payload) { code, _ -> code in 200..299 }
                    }
                }
                if (!accepted) {
                    Timber.tag(TAG).w("Connect-state play command rejected for %s", trackUri)
                    return null
                }
                fileId =
                    withTimeoutOrNull(MANIFEST_TIMEOUT_MS) { deferred.await() }
                        ?: audioFileMap[trackUri]
            } finally {
                pendingTrackResolutions.remove(trackUri)
            }
        }
        if (fileId == null) {
            Timber.tag(TAG).w("No manifest file id arrived for %s", trackUri)
            return null
        }
        return resolveAudioCdn(fileId, token, clientToken)
    }

    private suspend fun resolveAudioCdn(
        fileId: String,
        token: String,
        clientToken: String,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$STORAGE_RESOLVE_URL/$fileId?version=10000000&product=9&platform=39&alt=json"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("client-token", clientToken)
                        .header("User-Agent", USER_AGENT)
                        .header("app-platform", APP_PLATFORM)
                        .header("spotify-app-version", SPOTIFY_APP_VERSION)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w("Storage-resolve failed: HTTP %d", response.code)
                        return@use null
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    json.optJSONArray("cdnurl")?.optString(0)?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
}
