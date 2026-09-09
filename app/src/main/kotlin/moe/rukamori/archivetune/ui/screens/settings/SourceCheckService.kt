/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.utils.PoolAccountManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SourceCheckResult(
    val healthy: Boolean,
    val summary: String,
)

object SourceCheckService {

    private const val KOZU_PROBE_YT_ID = "dQw4w9WgXcQ"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(source: AudioSourceType, context: Context): SourceCheckResult =
        withContext(Dispatchers.IO) {
            when (source) {
                AudioSourceType.TIDAL -> checkTidal(context)
                AudioSourceType.QOBUZ -> checkQobuz(context)
                AudioSourceType.QOBUZ_BACKUP -> checkQobuzBackup()
                AudioSourceType.DEEZER -> checkDeezer(context)
                AudioSourceType.APPLE -> checkAppleMusic()
                AudioSourceType.JIOSAAVN -> checkJioSaavn()
                AudioSourceType.YOUTUBE -> SourceCheckResult(
                    healthy = true,
                    summary = "YouTube is always available as the fallback source.",
                )
            }
        }

    private suspend fun checkTidal(context: Context): SourceCheckResult {

        PoolAccountManager.refresh(context, force = false)
        val accounts = PoolAccountManager.tidalAccounts()
        if (accounts.isEmpty()) {
            return SourceCheckResult(
                healthy = false,
                summary = "No Tidal accounts in the source pool. Tap 'Refresh source pool' at the top, " +
                    "or add your own Tidal token via Integration → Manual source sign-in.",
            )
        }
        val premium = accounts.count { it.premium }

        val probeAccount = accounts.firstOrNull { it.premium } ?: accounts.first()
        val session = runCatching { TidalAccountManager.buildSessionFromBearer(probeAccount.token) }.getOrNull()
        val subscription =
            session?.userId?.let { userId ->
                runCatching { TidalAccountManager.fetchSubscription(probeAccount.token, userId) }.getOrNull()
            }
        val accountLabel =
            when {
                session == null -> "token rejected by the Tidal API (expired — refresh the source pool)"
                subscription == TidalAccountManager.Subscription.PREMIUM -> "valid (premium — lossless available)"
                subscription == TidalAccountManager.Subscription.FREE -> "valid but FREE (previews only, no lossless)"
                else -> "valid, subscription tier unknown"
            }
        val accountPathReady = session != null && subscription != TidalAccountManager.Subscription.FREE

        val healthyInstances = runCatching {
            moe.rukamori.archivetune.tidal.TidalInstanceHealthManager.healthyUrls(context).size
        }.getOrDefault(0)

        val summary = buildString {
            append("Pool accounts: ${accounts.size} ($premium premium)\n")
            append("Account stream path: $accountLabel\n")
            append("Public instances (optional fallback): $healthyInstances healthy")
            if (accountPathReady) {
                append("\n\nTidal source is READY via the account path.")
                if (healthyInstances == 0) {
                    append(
                        " No public instance is reachable, but none is needed — " +
                            "the pool's subscriber token streams directly from Tidal.",
                    )
                }
            } else {
                append("\n\nTidal source is NOT ready: ")
                append(
                    if (healthyInstances > 0) {
                        "the account path failed, so playback will fall back to a public instance " +
                            "(lower quality, may serve previews)."
                    } else {
                        "the account path failed and no public instance is reachable. " +
                            "Tap 'Refresh source pool' to pull fresh tokens, or add a private " +
                            "Tidal instance via Integration."
                    },
                )
            }
        }
        return SourceCheckResult(
            healthy = accountPathReady || healthyInstances > 0,
            summary = summary,
        )
    }

    private suspend fun checkQobuz(context: Context): SourceCheckResult {
        PoolAccountManager.refresh(context, force = false)
        val accounts = PoolAccountManager.qobuzAccounts()
        if (accounts.isEmpty()) {
            return SourceCheckResult(
                healthy = false,
                summary = "No Qobuz accounts in the source pool. Tap 'Refresh source pool' at the top, " +
                    "or add your own Qobuz token (with app_id + app_secret) via Integration → Manual source sign-in.",
            )
        }
        val premium = accounts.count { it.premium }

        val first = accounts.first()
        val token = QobuzToken(
            token = first.token,
            appId = first.appId,
            appSecret = first.appSecret,
            label = "Source Pool",
            subscription = if (first.premium) "premium" else "",
        )
        val health = QobuzAudioProvider.verifyToken(token, probeTrackId = null, formatId = 5)
        val healthLabel = when (health) {
            moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.HEALTHY -> "healthy (premium)"
            moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.PREVIEW_ONLY -> "preview-only (no subscription)"
            moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.UNREACHABLE -> "unreachable (token invalid / app_secret mismatch)"
            else -> "unknown"
        }
        return SourceCheckResult(
            healthy = health == moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.HEALTHY,
            summary = "Pool accounts: ${accounts.size} ($premium premium)\n" +
                "First token probe: $healthLabel\n" +
                "Qobuz source is ${if (health == moe.rukamori.archivetune.tidal.TidalAudioProvider.InstanceHealth.HEALTHY) "READY" else "NOT ready — see above"}.",
        )
    }

    private fun checkQobuzBackup(): SourceCheckResult {

        val resolverUrl = "https://mlc-ytify.kouzu.in/api/stream?id=$KOZU_PROBE_YT_ID"
        return runCatching {
            val resolverRequest = Request.Builder()
                .url(resolverUrl)
                .get()
                .header("x-request-source", "muzo")
                .header("User-Agent", "ArchiveTune-Android")
                .header("Accept", "application/json")
                .build()
            client.newCall(resolverRequest).execute().use { resolverResponse ->
                if (!resolverResponse.isSuccessful) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned HTTP ${resolverResponse.code}. " +
                            "The backup server may be down or rate-limiting your IP.",
                    )
                }
                val body = resolverResponse.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned an empty body.",
                    )
                }
                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned a non-JSON response.",
                    )
                }

                val losslessUrl = root.optString("lossless").takeIf { it.isNotBlank() }
                val lossyUrl = root.optString("url").takeIf { it.isNotBlank() }
                if (losslessUrl == null && lossyUrl == null) {
                    return@runCatching SourceCheckResult(
                        healthy = false,
                        summary = "Qobuz backup resolver returned a JSON envelope with no stream URL.",
                    )
                }

                val losslessProbe = losslessUrl?.let { probeCdn(it) }
                val lossyProbe = if (losslessProbe?.ok == true) null else lossyUrl?.let { probeCdn(it) }
                when {
                    losslessProbe?.ok == true ->
                        SourceCheckResult(
                            healthy = true,
                            summary = "Qobuz backup is reachable and served a lossless stream " +
                                "(${losslessProbe.contentType}${losslessProbe.sizeSuffix()}). " +
                                "Qobuz backup is READY.",
                        )

                    lossyProbe?.ok == true ->
                        SourceCheckResult(
                            healthy = true,
                            summary = "Qobuz backup is reachable but only the lossy mirror served audio " +
                                "(${lossyProbe.contentType}). The backup server has no lossless copy of the " +
                                "probe track yet — lossless will still be used for tracks that have one.",
                        )

                    else -> {
                        val failed = losslessProbe ?: lossyProbe
                        SourceCheckResult(
                            healthy = false,
                            summary = "Qobuz backup resolver returned a stream URL but the CDN served " +
                                "${failed?.describeFailure() ?: "no response"}. The backup server may be " +
                                "rebuilding its cache — try again in a few minutes.",
                        )
                    }
                }
            }
        }.getOrElse { e ->
            SourceCheckResult(
                healthy = false,
                summary = "Failed to reach Qobuz backup: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private data class CdnProbe(
        val ok: Boolean,
        val code: Int,
        val contentType: String,
        val totalBytes: Long?,
    ) {
        fun sizeSuffix(): String =
            totalBytes?.let { ", ${it / 1_000_000}MB" }.orEmpty()

        fun describeFailure(): String =
            if (code in 200..299) "an unexpected content type: $contentType" else "HTTP $code"
    }

    private fun probeCdn(url: String): CdnProbe? =
        runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "ArchiveTune-Android")
                .header("Range", "bytes=0-1")
                .build()
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                val isAudio =
                    contentType.startsWith("audio/") ||
                        contentType.startsWith("video/") ||
                        contentType.contains("octet-stream")
                val total =
                    response
                        .header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                CdnProbe(
                    ok = response.isSuccessful && isAudio,
                    code = response.code,
                    contentType = contentType.ifBlank { "unknown" },
                    totalBytes = total,
                )
            }
        }.getOrNull()

    private suspend fun checkAppleMusic(): SourceCheckResult {
        val mediaToken = AppleMusicAudioProvider.mediaUserToken()
        val devToken = AppleMusicAudioProvider.devToken()
        if (mediaToken == null || devToken == null) {
            val missing =
                buildList {
                    if (devToken == null) add("dev (Bearer) token")
                    if (mediaToken == null) add("Media-User-Token")
                }.joinToString(" and ")
            val pool = PoolAccountManager.appleMusicAccounts()
            if (pool.isNotEmpty()) {
                return SourceCheckResult(
                    healthy = true,
                    summary = "Signed in via the Source Pool (%d shared Apple Music account%s).".format(
                        pool.size,
                        if (pool.size == 1) "" else "s",
                    ),
                )
            }
            return SourceCheckResult(
                healthy = false,
                summary = "No $missing. Sign in via Settings → Apple Music, or refresh the source pool.",
            )
        }
        return runCatching {
            val storefront = AppleMusicAudioProvider.resolveStorefront()
            SourceCheckResult(
                healthy = true,
                summary = "Apple Music reachable — storefront '$storefront' resolved from your token.",
            )
        }.getOrElse {
            SourceCheckResult(
                healthy = false,
                summary = "Token present but the API rejected it (${it.message}). Re-paste a fresh Media-User-Token.",
            )
        }
    }

    private suspend fun checkDeezer(context: Context): SourceCheckResult {

        PoolAccountManager.refresh(context, force = true)

        val availability = DeezerAudioProvider.accountAvailability()
        if (availability.total == 0) {
            return SourceCheckResult(
                healthy = false,
                summary = "No Deezer credentials available. Sign in with your own Deezer account via " +
                    "Integration → Deezer, or tap 'Refresh source pool' at the top to pick up shared accounts.",
            )
        }

        val origin =
            buildList {
                if (availability.manual) {
                    add("your own account${if (availability.manualPremium) " (premium)" else ""}")
                }
                if (availability.pooled > 0) {
                    add("${availability.pooled} pool account(s), ${availability.pooledPremium} premium")
                }
            }.joinToString(" + ")

        val info = DeezerAudioProvider.verifyPreferredAccount()
        return if (info == null) {
            SourceCheckResult(
                healthy = false,
                summary = "Found $origin, but the Deezer gateway rejected the credential it would use " +
                    "first. Sign in again via Integration → Deezer, or refresh the source pool.",
            )
        } else {
            val tier = if (info.lossless) "lossless (FLAC) available" else "no lossless — 320kbps MP3 at best"
            SourceCheckResult(
                healthy = true,
                summary = "Credentials: $origin. Verified as '${info.name}' — $tier. Deezer source is READY.",
            )
        }
    }

    private fun checkJioSaavn(): SourceCheckResult {

        return runCatching {
            val result = kotlinx.coroutines.runBlocking {
                SaavnService.searchSongs("test query").getOrDefault(emptyList())
            }
            if (result.isEmpty()) {
                SourceCheckResult(
                    healthy = false,
                    summary = "JioSaavn search returned no results. The service may be down or " +
                        "rate-limiting your IP — try again in a minute.",
                )
            } else {
                SourceCheckResult(
                    healthy = true,
                    summary = "JioSaavn is reachable and returned ${result.size} results for a probe query. " +
                        "JioSaavn source is READY.",
                )
            }
        }.getOrElse { e ->
            SourceCheckResult(
                healthy = false,
                summary = "Failed to reach JioSaavn: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }
}
