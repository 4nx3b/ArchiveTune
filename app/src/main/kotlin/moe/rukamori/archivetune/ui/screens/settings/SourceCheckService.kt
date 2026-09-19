
package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.AmazonAccountNameKey
import moe.rukamori.archivetune.constants.AmazonAccountPremiumKey
import moe.rukamori.archivetune.amazon.AmazonMusicProvider
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.QobuzBackupEndpointsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzBackupProvider
import moe.rukamori.archivetune.qobuz.QobuzToken
import moe.rukamori.archivetune.tidal.TidalAccountManager
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.utils.PoolAccountManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class SourceCheckStatus {
    READY,
    DEGRADED,
    NOT_CONFIGURED,
    UNSUPPORTED,
    UNREACHABLE,
}

data class SourceCheckResult(
    val status: SourceCheckStatus,
    val summary: String,
    val checkedAtMs: Long = System.currentTimeMillis(),
) {
    val healthy: Boolean get() = status == SourceCheckStatus.READY
}

object SourceCheckService {
    private const val KOZU_PROBE_YT_ID = "dQw4w9WgXcQ"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val _results = MutableStateFlow<Map<AudioSourceType, SourceCheckResult>>(emptyMap())

    val results: StateFlow<Map<AudioSourceType, SourceCheckResult>> = _results.asStateFlow()

    fun cachedResult(source: AudioSourceType): SourceCheckResult? = _results.value[source]

    suspend fun check(source: AudioSourceType, context: Context): SourceCheckResult {
        val result =
            withContext(Dispatchers.IO) {
                when (source) {
                    AudioSourceType.TIDAL -> checkTidal(context)
                    AudioSourceType.QOBUZ -> checkQobuz(context)
                    AudioSourceType.QOBUZ_BACKUP -> checkQobuzBackup(context)
                    AudioSourceType.DEEZER -> checkDeezer(context)
                    AudioSourceType.APPLE -> checkAppleMusic()
                    AudioSourceType.AMAZON -> checkAmazon(context)
                    AudioSourceType.QQ -> checkQqMusic()
                    AudioSourceType.JIOSAAVN -> checkJioSaavn()
                    AudioSourceType.YOUTUBE -> checkYouTube()
                }
            }
        _results.update { it + (source to result) }
        return result
    }

    private fun checkQqMusic(): SourceCheckResult =
        // There is nothing to probe: QQ Music playback exists through Tencent's partner program
        // only, and a build without partner credentials cannot even open a session.
        if (!QqMusicProvider.isConfigured()) {
            SourceCheckResult(
                healthy = false,
                summary = "QQ Music needs a Tencent Music partner application (QQ_PARTNER_APP_ID). " +
                    "There is no public personal-developer playback API, so until the maintainer " +
                    "registers as a partner this source stays inert and playback falls through.",
            )
        } else {
            SourceCheckResult(
                healthy = true,
                summary = "Partner credentials are present. QQ Music resolves through Tencent's " +
                    "documented OpenAPI; encrypted formats are reported unavailable rather than bypassed.",
            )
        }

    private suspend fun checkTidal(context: Context): SourceCheckResult {
        PoolAccountManager.refresh(context, force = false)
        val accounts = PoolAccountManager.tidalAccounts()
        if (accounts.isEmpty()) {
            val healthyInstances = runCatching {
                moe.rukamori.archivetune.tidal.TidalInstanceHealthManager.healthyUrls(context).size
            }.getOrDefault(0)
            return if (healthyInstances > 0) {
                SourceCheckResult(
                    status = SourceCheckStatus.DEGRADED,
                    summary = "No Tidal accounts in the source pool, but $healthyInstances public " +
                        "instance(s) are reachable — playback works at reduced quality (may serve previews). " +
                        "For lossless, sign in with your own Tidal token via Integration → Manual source sign-in.",
                )
            } else {
                SourceCheckResult(
                    status = SourceCheckStatus.NOT_CONFIGURED,
                    summary = "No Tidal accounts in the source pool and no public instance is reachable. " +
                        "Sign in with your own Tidal token via Integration → Manual source sign-in, " +
                        "or re-toggle the Tidal source here to pull fresh pool accounts.",
                )
            }
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
                session == null -> "token rejected by the Tidal API (expired — re-toggle the source to refresh the pool)"
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
                append("\n\nTidal source is ")
                append(
                    if (healthyInstances > 0) {
                        "PARTIALLY ready: the account path failed, so playback will fall back to a public " +
                            "instance (lower quality, may serve previews)."
                    } else {
                        "NOT ready: the account path failed and no public instance is reachable. " +
                            "Re-toggle the Tidal source to pull fresh pool tokens, or add a private " +
                            "Tidal instance via Integration."
                    },
                )
            }
        }
        return SourceCheckResult(
            status = when {
                accountPathReady -> SourceCheckStatus.READY
                healthyInstances > 0 -> SourceCheckStatus.DEGRADED
                else -> SourceCheckStatus.UNREACHABLE
            },
            summary = summary,
        )
    }

    private suspend fun checkQobuz(context: Context): SourceCheckResult {
        PoolAccountManager.refresh(context, force = false)
        val accounts = PoolAccountManager.qobuzAccounts()
        if (accounts.isEmpty()) {
            return SourceCheckResult(
                status = SourceCheckStatus.NOT_CONFIGURED,
                summary = "No Qobuz accounts in the source pool. Sign in with your own Qobuz token " +
                    "(with app_id + app_secret) via Integration → Manual source sign-in, " +
                    "or re-toggle the Qobuz source here to pull fresh pool accounts.",
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
        return when (health) {
            TidalAudioProvider.InstanceHealth.HEALTHY ->
                SourceCheckResult(
                    status = SourceCheckStatus.READY,
                    summary = "Pool accounts: ${accounts.size} ($premium premium)\n" +
                        "First token probe: healthy (premium)\n" +
                        "Qobuz source is READY.",
                )

            TidalAudioProvider.InstanceHealth.PREVIEW_ONLY ->
                SourceCheckResult(
                    status = SourceCheckStatus.DEGRADED,
                    summary = "Pool accounts: ${accounts.size} ($premium premium)\n" +
                        "First token probe: preview-only (no subscription)\n" +
                        "The token works, but without a subscription only 30-second previews will play.",
                )

            else ->
                SourceCheckResult(
                    status = SourceCheckStatus.UNREACHABLE,
                    summary = "Pool accounts: ${accounts.size} ($premium premium)\n" +
                        "First token probe: unreachable (token invalid / app_secret mismatch)\n" +
                        "Qobuz source is NOT ready — re-toggle the source to pull fresh pool tokens, " +
                        "or add your own token via Integration.",
                )
        }
    }

    private suspend fun checkQobuzBackup(context: Context): SourceCheckResult {
        runCatching {
            val stored = context.dataStore.data.first()[QobuzBackupEndpointsKey].orEmpty()
            QobuzBackupProvider.configuredEndpoints =
                stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val endpoints = QobuzBackupProvider.endpointList()
        if (endpoints.isEmpty()) {
            return SourceCheckResult(
                status = SourceCheckStatus.NOT_CONFIGURED,
                summary = "No backup resolver endpoints configured. Add a mirror of the kouzu.in API " +
                    "under Settings → Sources → Qobuz backup → Backup resolver endpoints (one URL per line).",
            )
        }
        val reports = mutableListOf<String>()
        var anyHealthy = false

        for (base in endpoints) {
            val result = probeQobuzBackupEndpoint(base)
            if (result.ok) {
                anyHealthy = true
                reports.add("OK  $base — ${result.summary}")
                break
            }
            reports.add("DOWN  $base — ${result.summary}")
        }

        return if (anyHealthy) {
            SourceCheckResult(
                status = SourceCheckStatus.READY,
                summary = reports.joinToString("\n"),
            )
        } else {
            SourceCheckResult(
                status = SourceCheckStatus.UNREACHABLE,
                summary = reports.joinToString("\n") +
                    "\nNo live backup endpoint. The shipped community mirror " +
                    "(mlc-ytify.kouzu.in) went dark in September 2026 — add a live " +
                    "mirror of the same API under Settings → Sources → Qobuz backup → " +
                    "Backup resolver endpoints (one URL per line). Dead endpoints are " +
                    "skipped for 10 minutes after 3 failures, so a down mirror does not " +
                    "slow down playback.",
            )
        }
    }

    private fun probeQobuzBackupEndpoint(base: String): EndpointProbe {
        val resolverUrl = "$base/api/stream?id=$KOZU_PROBE_YT_ID"
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
                    return@runCatching EndpointProbe(
                        ok = false,
                        summary = "resolver returned HTTP ${resolverResponse.code}.",
                    )
                }
                val body = resolverResponse.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@runCatching EndpointProbe(
                        ok = false,
                        summary = "resolver returned an empty body.",
                    )
                }
                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) {
                    return@runCatching EndpointProbe(
                        ok = false,
                        summary = "resolver returned a non-JSON response.",
                    )
                }

                val losslessUrl = root.optString("lossless").takeIf { it.isNotBlank() }
                val lossyUrl = root.optString("url").takeIf { it.isNotBlank() }
                if (losslessUrl == null && lossyUrl == null) {
                    return@runCatching EndpointProbe(
                        ok = false,
                        summary = "resolver returned a JSON envelope with no stream URL.",
                    )
                }

                val losslessProbe = losslessUrl?.let { probeCdn(it) }
                val lossyProbe = if (losslessProbe?.ok == true) null else lossyUrl?.let { probeCdn(it) }
                when {
                    losslessProbe?.ok == true ->
                        EndpointProbe(
                            ok = true,
                            summary = "reachable and served a lossless stream " +
                                "(${losslessProbe.contentType}${losslessProbe.sizeSuffix()}).",
                        )

                    lossyProbe?.ok == true ->
                        EndpointProbe(
                            ok = true,
                            summary = "reachable but only the lossy mirror served audio " +
                                "(${lossyProbe.contentType}). No lossless copy of the probe track yet.",
                        )

                    else -> {
                        val failed = losslessProbe ?: lossyProbe
                        EndpointProbe(
                            ok = false,
                            summary = "resolver returned a stream URL but the CDN served " +
                                "${failed?.describeFailure() ?: "no response"}.",
                        )
                    }
                }
            }
        }.getOrElse { e ->
            EndpointProbe(
                ok = false,
                summary = "failed to reach endpoint: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private data class EndpointProbe(
    val ok: Boolean,
    val summary: String,
)

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
                    status = SourceCheckStatus.DEGRADED,
                    summary = "Signed in via the Source Pool (%d shared Apple Music account%s), but " +
                        "playback also needs a developer token and none is set. Sign in once via " +
                        "Settings → Apple Music → Sign in with Apple Music (web) — it fetches both " +
                        "tokens automatically.".format(pool.size, if (pool.size == 1) "" else "s"),
                )
            }
            return SourceCheckResult(
                status = SourceCheckStatus.NOT_CONFIGURED,
                summary = "No $missing. Sign in via Settings → Apple Music → Sign in with Apple Music (web) " +
                    "— the token pair is fetched automatically — or paste them in the Tokens sheet.",
            )
        }
        return runCatching {
            val storefront = AppleMusicAudioProvider.resolveStorefront()
            SourceCheckResult(
                status = SourceCheckStatus.READY,
                summary = "Apple Music reachable — storefront '$storefront' resolved from your token.",
            )
        }.getOrElse {
            SourceCheckResult(
                status = SourceCheckStatus.UNREACHABLE,
                summary = "Token present but the API rejected it (${it.message}). Sign in again via " +
                    "Settings → Apple Music — a fresh token pair is fetched automatically.",
            )
        }
    }

    private suspend fun checkDeezer(context: Context): SourceCheckResult {

        PoolAccountManager.refresh(context, force = false)

        val availability = DeezerAudioProvider.accountAvailability()
        if (availability.total == 0) {
            return SourceCheckResult(
                status = SourceCheckStatus.NOT_CONFIGURED,
                summary = "No Deezer credentials available. Sign in with your own Deezer account via " +
                    "Integration → Deezer, or re-toggle the Deezer source here to pick up shared accounts.",
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
                status = SourceCheckStatus.UNREACHABLE,
                summary = "Found $origin, but the Deezer gateway rejected the credential it would use " +
                    "first. Sign in again via Integration → Deezer, or re-toggle the Deezer source " +
                    "to pull fresh pool accounts.",
            )
        } else {
            val tier = if (info.lossless) "lossless (FLAC) available" else "no lossless — 320kbps MP3 at best"
            SourceCheckResult(
                status = SourceCheckStatus.READY,
                summary = "Credentials: $origin. Verified as '${info.name}' — $tier. Deezer source is READY.",
            )
        }
    }

    private suspend fun checkAmazon(context: Context): SourceCheckResult {
        PoolAccountManager.refresh(context, force = false)
        val pooled = PoolAccountManager.amazonAccounts()
        val prefs = context.dataStore.data.first()
        val manualName = prefs[AmazonAccountNameKey]?.takeIf { it.isNotBlank() }
        val manualPremium = prefs[AmazonAccountPremiumKey] == true
        // The Web API is approval-gated: without the security profile this build cannot talk to
        // Amazon at all, so credentials alone would not make the source work and reporting them as
        // healthy would promise playback that cannot happen.
        if (!AmazonMusicProvider.isConfigured()) {
            return SourceCheckResult(
                healthy = false,
                summary = "Amazon Music needs an approved Web API security profile (AMAZON_LWA_CLIENT_ID) " +
                    "in this build. Until the maintainer provisions one the source stays inert and " +
                    "playback falls through to the next source.",
            )
        }
        if (pooled.isEmpty() && manualName == null) {
            return SourceCheckResult(
                status = SourceCheckStatus.NOT_CONFIGURED,
                summary = "No Amazon Music credentials available. Sign in via Integration → Amazon Music, " +
                    "or re-toggle the Amazon source here to pick up shared accounts.",
            )
        }
        val origin =
            buildList {
                if (manualName != null) {
                    add("your own account '$manualName'${if (manualPremium) " (HD/Ultra HD)" else ""}")
                }
                if (pooled.isNotEmpty()) {
                    add("${pooled.size} pool account(s), ${pooled.count { it.premium }} HD/Ultra HD")
                }
            }.joinToString(" + ")
        return SourceCheckResult(
            healthy = true,
            summary = "Credentials: $origin. Playback resolves through Amazon's Web API and is licensed " +
                "by Amazon's own server for the signed-in account; the quality tier the account is " +
                "entitled to is the tier it gets.",
        )
    }

    private suspend fun checkJioSaavn(): SourceCheckResult {

        return runCatching {
            val result = SaavnService.searchSongs("a").getOrDefault(emptyList())
            if (result.isEmpty()) {
                SourceCheckResult(
                    status = SourceCheckStatus.UNREACHABLE,
                    summary = "JioSaavn search returned no results for a probe query. The service may be " +
                        "down or rate-limiting your IP — try again in a minute.",
                )
            } else {
                SourceCheckResult(
                    status = SourceCheckStatus.READY,
                    summary = "JioSaavn is reachable and returned ${result.size} results for a probe query. " +
                        "JioSaavn source is READY.",
                )
            }
        }.getOrElse { e ->
            SourceCheckResult(
                status = SourceCheckStatus.UNREACHABLE,
                summary = "Failed to reach JioSaavn: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private suspend fun checkYouTube(): SourceCheckResult {

        val probe =
            runCatching { YouTube.getMediaInfo(KOZU_PROBE_YT_ID).getOrNull() }
        return if (probe.getOrNull() != null) {
            SourceCheckResult(
                status = SourceCheckStatus.READY,
                summary = "YouTube is reachable — the InnerTube API answered a probe request. " +
                    "YouTube is always available as the fallback source.",
            )
        } else {
            val detail = probe.exceptionOrNull()?.message?.let { " ($it)" }.orEmpty()
            SourceCheckResult(
                status = SourceCheckStatus.UNREACHABLE,
                summary = "YouTube's InnerTube API did not answer a probe request$detail. Check your " +
                    "connection — every other source falls back to YouTube, so a failure here affects " +
                    "all playback.",
            )
        }
    }
}
