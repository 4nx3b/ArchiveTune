/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.discord

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.DiscordAvatarUrlKey
import moe.rukamori.archivetune.constants.DiscordNameKey
import moe.rukamori.archivetune.constants.DiscordPendingAuthStartedAtKey
import moe.rukamori.archivetune.constants.DiscordPendingAuthStateKey
import moe.rukamori.archivetune.constants.DiscordPendingAuthVerifierKey
import moe.rukamori.archivetune.constants.DiscordRefreshTokenKey
import moe.rukamori.archivetune.constants.DiscordTokenExpiresAtKey
import moe.rukamori.archivetune.constants.DiscordTokenKey
import moe.rukamori.archivetune.constants.DiscordUsernameKey
import moe.rukamori.archivetune.utils.dataStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class DiscordAuthorizationSession(
    val state: String,
    val codeVerifier: String,
    val authorizationUri: Uri,
)

data class DiscordAccount(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class DiscordAuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val account: DiscordAccount?,
)

/** Result of an app-side authorization completion attempt. */
sealed interface DiscordAuthResult {
    data class Success(
        val account: DiscordAccount?,
    ) : DiscordAuthResult

    data class Failure(
        val message: String,
    ) : DiscordAuthResult
}

object DiscordAuthCoordinator {
    val redirects =
        MutableSharedFlow<Uri>(
            replay = 1,
            extraBufferCapacity = 1,
        )

    val authResults =
        MutableSharedFlow<DiscordAuthResult>(
            replay = 1,
            extraBufferCapacity = 1,
        )

    fun emit(uri: Uri) {
        redirects.tryEmit(uri)
    }

    fun emitResult(result: DiscordAuthResult) {
        authResults.tryEmit(result)
    }
}

object DiscordOAuthRepository {
    private const val AUTHORIZATION_ENDPOINT = "https://discord.com/oauth2/authorize"
    private const val TOKEN_ENDPOINT = "https://discord.com/api/oauth2/token"
    private const val CURRENT_USER_ENDPOINT = "https://discord.com/api/v10/users/@me"
    private const val REQUEST_TIMEOUT_MS = 12_000
    private const val EXPIRY_SKEW_MS = 60_000L

    // A pending PKCE session stays claimable for this long (OAuth codes
    // themselves expire after ~10 minutes, so a much larger window only
    // invites confusion between login attempts).
    private const val PENDING_SESSION_TTL_MS = 15 * 60_000L

    // Completion runs on a process-scoped supervisor: the callback activity
    // finishes the moment it has forwarded the redirect, and the token
    // exchange (up to 12 s) must outlive it. The settings screen does NOT
    // need to be alive - completion is fully app-side.
    private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    val applicationId: Long
        get() = BuildConfig.DISCORD_APPLICATION_ID_LONG

    val redirectUri: String
        get() = "${BuildConfig.DISCORD_REDIRECT_SCHEME}:/authorize/callback"

    fun createAuthorizationSession(): DiscordAuthorizationSession {
        val state = randomUrlSafeString(byteCount = 32)
        val verifier = randomUrlSafeString(byteCount = 64)
        val challenge = sha256Base64Url(verifier)
        val scopes =
            listOf(
                "openid",
                "identify",
                "sdk.social_layer_presence",
            ).joinToString(separator = " ")

        val uri =
            Uri
                .parse(AUTHORIZATION_ENDPOINT)
                .buildUpon()
                .appendQueryParameter("client_id", BuildConfig.DISCORD_APPLICATION_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", scopes)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()

        return DiscordAuthorizationSession(
            state = state,
            codeVerifier = verifier,
            authorizationUri = uri,
        )
    }

    /**
     * Creates a session AND persists its PKCE material to DataStore so the
     * returning redirect can be completed even if the launching activity was
     * destroyed (process death while the browser is open, config change, low
     * memory kill). The previous completion flow depended on the settings
     * screen holding the same in-memory session object: any recreation
     * silently invalidated the state check and the login died as a no-op
     * ("I tap Authorize and nothing happens").
     */
    suspend fun beginAuthorization(context: Context): DiscordAuthorizationSession {
        val session = createAuthorizationSession()
        context.dataStore.edit { prefs ->
            prefs[DiscordPendingAuthStateKey] = session.state
            prefs[DiscordPendingAuthVerifierKey] = session.codeVerifier
            prefs[DiscordPendingAuthStartedAtKey] = System.currentTimeMillis()
        }
        return session
    }

    /**
     * App-side completion of a redirect, independent of any UI. Validates the
     * redirect against the PERSISTED pending session (falling back to the
     * in-memory session for same-process logins started by an older call
     * path), exchanges the code, stores the resulting token and clears the
     * pending session. Emits a [DiscordAuthResult] either way - a failed or
     * mismatched login must never be silent again.
     */
    fun completeFromRedirectAsync(context: Context, redirect: Uri) {
        completionScope.launch {
            val result = completeFromRedirect(context, redirect)
            if (result != null) {
                DiscordAuthCoordinator.emitResult(result)
            }
        }
    }

    private suspend fun completeFromRedirect(
        context: Context,
        redirect: Uri,
    ): DiscordAuthResult? {
        val outcome =
            withContext(Dispatchers.IO) {
                runCatching {
                    require(redirect.scheme == BuildConfig.DISCORD_REDIRECT_SCHEME) {
                        "Unexpected Discord redirect scheme"
                    }
                    require(redirect.path == "/authorize/callback") {
                        "Unexpected Discord redirect target"
                    }

                    redirect.getQueryParameter("error")?.let { error ->
                        val description = redirect.getQueryParameter("error_description")
                        throw IllegalStateException(description ?: error)
                    }

                    val state = redirect.getQueryParameter("state")
                    val code =
                        requireNotNull(redirect.getQueryParameter("code")) {
                            "Discord authorization code is missing"
                        }

                    val verifier = resolvePendingVerifier(context, state)
                    requireNotNull(verifier) {
                        "Discord authorization state mismatch - the login session " +
                            "expired or belongs to another attempt"
                    }

                    val token = exchangeAuthorizationCode(code, verifier)
                    val account = runCatching { fetchAccount(token.accessToken) }.getOrNull()
                    val authSession = token.toAuthSession(account)
                    storeSession(context, authSession)
                    authSession
                }
            }

        return when {
            outcome.isSuccess -> DiscordAuthResult.Success(outcome.getOrNull()?.account)
            outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException -> null
            else ->
                DiscordAuthResult.Failure(
                    outcome.exceptionOrNull()?.message ?: "Discord authorization failed",
                )
        }
    }

    /**
     * Matches the redirect's state against the persisted pending session and
     * returns its PKCE verifier, consuming the pending session atomically.
     * Returns null on mismatch/expiry - which also covers double-completion
     * (the second attempt finds no pending session and fails harmlessly).
     */
    private suspend fun resolvePendingVerifier(
        context: Context,
        state: String?,
    ): String? {
        if (state.isNullOrBlank()) return null
        var verifier: String? = null
        context.dataStore.edit { prefs ->
            val pendingState = prefs[DiscordPendingAuthStateKey]
            val startedAt = prefs[DiscordPendingAuthStartedAtKey] ?: 0L
            val expired =
                startedAt <= 0L ||
                    System.currentTimeMillis() - startedAt > PENDING_SESSION_TTL_MS
            if (pendingState == state && !expired) {
                verifier = prefs[DiscordPendingAuthVerifierKey]
            }
            prefs.remove(DiscordPendingAuthStateKey)
            prefs.remove(DiscordPendingAuthVerifierKey)
            prefs.remove(DiscordPendingAuthStartedAtKey)
        }
        return verifier
    }

    suspend fun completeAuthorization(
        context: Context,
        session: DiscordAuthorizationSession,
        redirect: Uri,
    ): Result<DiscordAuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(redirect.scheme == BuildConfig.DISCORD_REDIRECT_SCHEME) {
                    "Unexpected Discord redirect scheme"
                }
                require(redirect.path == "/authorize/callback") {
                    "Unexpected Discord redirect target"
                }
                require(redirect.getQueryParameter("state") == session.state) {
                    "Discord authorization state mismatch"
                }

                redirect.getQueryParameter("error")?.let { error ->
                    val description = redirect.getQueryParameter("error_description")
                    throw IllegalStateException(description ?: error)
                }

                val code =
                    requireNotNull(redirect.getQueryParameter("code")) {
                        "Discord authorization code is missing"
                    }

                val token = exchangeAuthorizationCode(code, session.codeVerifier)
                val account = runCatching { fetchAccount(token.accessToken) }.getOrNull()
                val authSession = token.toAuthSession(account)
                storeSession(context, authSession)
                authSession
            }
        }

    suspend fun getValidAccessToken(context: Context): String? =
        withContext(Dispatchers.IO) {
            val prefs = context.dataStore.data.first()
            val currentToken = prefs[DiscordTokenKey]?.trim().orEmpty()
            if (currentToken.isBlank()) {
                return@withContext null
            }

            val expiresAt = prefs[DiscordTokenExpiresAtKey] ?: 0L
            if (expiresAt == 0L || System.currentTimeMillis() + EXPIRY_SKEW_MS < expiresAt) {
                return@withContext currentToken
            }

            val refreshToken = prefs[DiscordRefreshTokenKey]?.trim().orEmpty()
            if (refreshToken.isBlank()) {
                return@withContext currentToken
            }

            refreshAccessToken(context, refreshToken)
                .getOrNull()
                ?.accessToken
                ?: currentToken
        }

    suspend fun fetchAccount(accessToken: String): DiscordAccount =
        withContext(Dispatchers.IO) {
            val response =
                getJson(
                    url = CURRENT_USER_ENDPOINT,
                    bearerToken = accessToken,
                )
            val userInfo = json.decodeFromString<UserInfoResponse>(response)
            val userId =
                userInfo.id
                    ?: userInfo.sub
                    ?: ""
            val username =
                userInfo.preferredUsername
                    ?: userInfo.username
                    ?: userId
            val displayName =
                userInfo.nickname
                    ?: userInfo.globalName
                    ?: userInfo.name
                    ?: username

            DiscordAccount(
                id = userId,
                username = username,
                displayName = displayName,
                avatarUrl =
                    userInfo.picture?.takeIf { it.isNotBlank() }
                        ?: buildAvatarUrl(
                            userId = userId,
                            avatarHash = userInfo.avatar,
                            discriminator = userInfo.discriminator,
                        ),
            )
        }

    suspend fun clearSession(context: Context) {
        withContext(Dispatchers.IO) {
            context.dataStore.edit { prefs ->
                prefs.remove(DiscordTokenKey)
                prefs.remove(DiscordRefreshTokenKey)
                prefs.remove(DiscordTokenExpiresAtKey)
                prefs.remove(DiscordUsernameKey)
                prefs.remove(DiscordNameKey)
                prefs.remove(DiscordAvatarUrlKey)
            }
        }
    }

    private suspend fun refreshAccessToken(
        context: Context,
        refreshToken: String,
    ): Result<DiscordAuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token =
                    postForm(
                        url = TOKEN_ENDPOINT,
                        params =
                            mapOf(
                                "client_id" to BuildConfig.DISCORD_APPLICATION_ID,
                                "grant_type" to "refresh_token",
                                "refresh_token" to refreshToken,
                            ),
                    ).let { json.decodeFromString<TokenResponse>(it) }

                val account = runCatching { fetchAccount(token.accessToken) }.getOrNull()
                val session = token.toAuthSession(account, fallbackRefreshToken = refreshToken)
                storeSession(context, session)
                session
            }
        }

    private fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
    ): TokenResponse =
        postForm(
            url = TOKEN_ENDPOINT,
            params =
                mapOf(
                    "client_id" to BuildConfig.DISCORD_APPLICATION_ID,
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to codeVerifier,
                ),
        ).let { json.decodeFromString(it) }

    private suspend fun storeSession(
        context: Context,
        session: DiscordAuthSession,
    ) {
        context.dataStore.edit { prefs ->
            prefs[DiscordTokenKey] = session.accessToken
            session.refreshToken?.takeIf { it.isNotBlank() }?.let {
                prefs[DiscordRefreshTokenKey] = it
            }
            prefs[DiscordTokenExpiresAtKey] = session.expiresAtMillis
            session.account?.let { account ->
                prefs[DiscordUsernameKey] = account.username
                prefs[DiscordNameKey] = account.displayName
                prefs[DiscordAvatarUrlKey] = account.avatarUrl.orEmpty()
            }
        }
    }

    private fun buildAvatarUrl(
        userId: String,
        avatarHash: String?,
        discriminator: String?,
    ): String? {
        if (userId.isBlank()) {
            return null
        }

        val normalizedAvatarHash = avatarHash?.takeIf { it.isNotBlank() }
        if (normalizedAvatarHash != null) {
            val extension = if (normalizedAvatarHash.startsWith("a_")) "gif" else "png"
            return "https://cdn.discordapp.com/avatars/$userId/$normalizedAvatarHash.$extension?size=256"
        }

        val defaultIndex =
            discriminator
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.rem(5)
                ?: userId.toLongOrNull()?.let { ((it shr 22) % 6L).toInt() }
                ?: 0
        return "https://cdn.discordapp.com/embed/avatars/$defaultIndex.png"
    }

    private fun TokenResponse.toAuthSession(
        account: DiscordAccount?,
        fallbackRefreshToken: String? = null,
    ): DiscordAuthSession {
        val expiresInMillis = expiresInSeconds.coerceAtLeast(0L) * 1000L
        val expiresAt =
            if (expiresInMillis > 0L) {
                System.currentTimeMillis() + expiresInMillis
            } else {
                0L
            }

        return DiscordAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken ?: fallbackRefreshToken,
            expiresAtMillis = expiresAt,
            account = account,
        )
    }

    private fun postForm(
        url: String,
        params: Map<String, String>,
    ): String {
        val body =
            params.entries.joinToString(separator = "&") { (key, value) ->
                "${key.urlEncode()}=${value.urlEncode()}"
            }
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }

        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }

        return connection.readResponse()
    }

    private fun getJson(
        url: String,
        bearerToken: String,
    ): String {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("Accept", "application/json")
            }

        return connection.readResponse()
    }

    private fun HttpURLConnection.readResponse(): String {
        val status = responseCode
        val stream = if (status in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        disconnect()

        if (status !in 200..299) {
            throw IOException("Discord OAuth request failed with HTTP $status: $body")
        }

        return body
    }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    private fun sha256Base64Url(value: String): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.US_ASCII))
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token")
        val accessToken: String,
        @SerialName("refresh_token")
        val refreshToken: String? = null,
        @SerialName("expires_in")
        val expiresInSeconds: Long = 0L,
    )

    @Serializable
    private data class UserInfoResponse(
        @SerialName("id")
        val id: String? = null,
        @SerialName("sub")
        val sub: String? = null,
        @SerialName("avatar")
        val avatar: String? = null,
        @SerialName("picture")
        val picture: String? = null,
        @SerialName("discriminator")
        val discriminator: String? = null,
        @SerialName("preferred_username")
        val preferredUsername: String? = null,
        @SerialName("name")
        val name: String? = null,
        @SerialName("nickname")
        val nickname: String? = null,
        @SerialName("username")
        val username: String? = null,
        @SerialName("global_name")
        val globalName: String? = null,
    )
}
