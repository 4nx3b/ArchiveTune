/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.ai.AiLyricsRomanizer
import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiCustomModelKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.AiRomanizeApiKeyKey
import moe.rukamori.archivetune.constants.AiRomanizeCustomEndpointKey
import moe.rukamori.archivetune.constants.AiRomanizeCustomModelKey
import moe.rukamori.archivetune.constants.AiRomanizeExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AiRomanizeProviderKey
import moe.rukamori.archivetune.constants.AiRomanizeSelectedModelKey
import moe.rukamori.archivetune.constants.AiRomanizeSeparateProviderKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.constants.AutoAiRomanizeLyricsKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object AiLyricsRomanization {
    private const val TAG = "AiRomanization"

    enum class RequestStatus {

        STARTED,

        ALREADY_CACHED,

        IN_FLIGHT,

        SETTINGS_DISABLED,

        NO_LYRICS,

        EXCLUDED_LANGUAGE,

        NO_ROMANIZABLE_SCRIPT,

        EMPTY_RESULT,
    }

    @Immutable
    data class Settings(
        val enabled: Boolean,
        val auto: Boolean,
        val excludedLanguages: Set<String>,
        val config: AiServiceConfig,
    ) {

        val active: Boolean get() = enabled && config.canCallApi

        /**
         * Identity of the provider this run of romanisation belongs to. Every
         * cached / in-flight / published result is keyed by it so a romanisation
         * produced by one provider is never served while another provider owns
         * the feature — e.g. results from the main provider must not reappear
         * after the user switches to the dedicated romanisation provider, and
         * vice versa.
         */
        val configKey: String
            get() = "${config.provider}|${config.apiKey}|${config.customEndpoint}|${config.model}"

        companion object {
            val Disabled =
                Settings(
                    enabled = false,
                    auto = false,
                    excludedLanguages = emptySet(),
                    config = AiServiceConfig(AiProvider.NONE, "", "", ""),
                )
        }
    }

    class Result(
        val sessionKey: String,
        val byLine: Map<String, String>,
        private val nonce: Long = nextNonce(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val romanizer = AiLyricsRomanizer()
    private val inFlight = ConcurrentHashMap<String, Deferred<List<String?>>>()
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    private val _results = MutableStateFlow<Result?>(null)
    private val nonceCounter = AtomicLong(0L)
    private fun nextNonce(): Long = nonceCounter.incrementAndGet()

    val results: StateFlow<Result?> = _results.asStateFlow()

    private val _requestOutcomes = MutableSharedFlow<RequestStatus>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val requestOutcomes: SharedFlow<RequestStatus> = _requestOutcomes.asSharedFlow()

    private val _running = MutableStateFlow(false)

    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Composable
    fun rememberSettings(): Settings {
        val (enabled) = rememberPreference(AiRomanizeLyricsKey, defaultValue = false)
        val (auto) = rememberPreference(AutoAiRomanizeLyricsKey, defaultValue = false)
        val (excluded) = rememberPreference(AiRomanizeExcludedLanguagesKey, defaultValue = emptySet())

        val provider by rememberEnumPreference(AiProviderKey, AiProvider.NONE)
        val (apiKey) = rememberPreference(AiApiKeyKey, defaultValue = "")
        val (customEndpoint) = rememberPreference(AiCustomEndpointKey, defaultValue = "")
        val (selectedModel) = rememberPreference(AiSelectedModelKey, defaultValue = "")
        val (customModel) = rememberPreference(AiCustomModelKey, defaultValue = "")

        // Separate-provider override, the user's exact rule:
        // - Separate provider ON (toggle on + a dedicated provider selected):
        //   ONLY that provider ever romanises — the main provider is never
        //   used for romanisation in this state; it only does translation.
        // - Separate provider OFF (toggle off, or provider left at "None"):
        //   the main provider does BOTH romanisation and translation.
        val (separateProviderEnabled) = rememberPreference(AiRomanizeSeparateProviderKey, defaultValue = false)
        val romanizeProvider by rememberEnumPreference(AiRomanizeProviderKey, AiProvider.NONE)
        val (romanizeApiKey) = rememberPreference(AiRomanizeApiKeyKey, defaultValue = "")
        val (romanizeCustomEndpoint) = rememberPreference(AiRomanizeCustomEndpointKey, defaultValue = "")
        val (romanizeSelectedModel) = rememberPreference(AiRomanizeSelectedModelKey, defaultValue = "")
        val (romanizeCustomModel) = rememberPreference(AiRomanizeCustomModelKey, defaultValue = "")

        val useSeparate = separateProviderEnabled && romanizeProvider != AiProvider.NONE
        val effectiveProvider = if (useSeparate) romanizeProvider else provider
        val effectiveApiKey = if (useSeparate) romanizeApiKey else apiKey
        val effectiveCustomEndpoint = if (useSeparate) romanizeCustomEndpoint else customEndpoint
        val effectiveModel =
            if (useSeparate) {
                if (romanizeProvider == AiProvider.CUSTOM) romanizeCustomModel else romanizeSelectedModel
            } else {
                if (provider == AiProvider.CUSTOM) customModel else selectedModel
            }

        return remember(
            enabled,
            auto,
            excluded,
            effectiveProvider,
            effectiveApiKey,
            effectiveCustomEndpoint,
            effectiveModel,
        ) {
            Settings(
                enabled = enabled,
                auto = auto,
                excludedLanguages = excluded,
                config =
                    AiServiceConfig(
                        provider = effectiveProvider,
                        apiKey = effectiveApiKey,
                        customEndpoint = effectiveCustomEndpoint,
                        model = effectiveModel,
                    ),
            )
        }
    }

    fun sessionKey(
        mediaId: String?,
        lyrics: String?,
    ): String = "${mediaId.orEmpty()}|${lyrics?.length ?: 0}|${lyrics?.hashCode() ?: 0}"

    fun linesFor(
        sessionKey: String,
        lines: List<String>,
        settings: Settings,
    ): List<String?> {
        // Provider-scoped lookup: a cached romanisation only ever resolves for
        // the provider that produced it (see Settings.configKey).
        val byLine = cache[fullKey(sessionKey, settings)] ?: return emptyList()
        return lines.map { byLine[it.trim()] }
    }

    fun linesOf(
        lyrics: String?,
        durationSeconds: Int? = null,
    ): List<String> {
        val text = lyrics?.trim().orEmpty()
        if (text.isEmpty() || text == LyricsEntity.LYRICS_NOT_FOUND) return emptyList()
        return runCatching {
            when {
                LyricsUtils.isTtml(text) -> LyricsUtils.parseTtml(text, durationSeconds).map { it.text }
                LyricsUtils.isLineSyncedLrc(text) -> LyricsUtils.parseLyrics(text).map { it.text }
                else -> text.lines().filter { it.isNotBlank() }.map { it.trim() }
            }
        }.getOrDefault(emptyList())
    }

    fun request(
        sessionKey: String,
        lines: List<String>,
        settings: Settings,
        force: Boolean = false,
    ): RequestStatus {
        if (!settings.active) return RequestStatus.SETTINGS_DISABLED
        if (lines.isEmpty()) return RequestStatus.NO_LYRICS

        // The romanisation identity of a request is its lyrics PLUS the
        // provider that will run it. Without the provider half, a cached or
        // in-flight result from the main provider kept answering after the
        // user switched to the dedicated romanisation provider (and vice
        // versa) — romanisation visibly "still ran" on the wrong provider.
        val requestKey = fullKey(sessionKey, settings)

        cache[requestKey]?.let { cached ->
            publish(requestKey, cached)
            return RequestStatus.ALREADY_CACHED
        }
        if (inFlight.containsKey(requestKey)) return RequestStatus.IN_FLIGHT

        val dominant = LyricsUtils.detectDominantLanguageCode(lines.joinToString("\n"))
        if (dominant != null && LyricsUtils.matchesExcludedLanguage(dominant, settings.excludedLanguages)) {
            Timber.tag(TAG).d("skipping %s: %s is excluded", sessionKey, dominant)
            return RequestStatus.EXCLUDED_LANGUAGE
        }

        if (!force && lines.none { LyricsUtils.hasRomanizableScript(it) }) return RequestStatus.NO_ROMANIZABLE_SCRIPT

        val job =
            scope.async {
                _running.value = true
                try {
                    romanizer.romanize(settings.config, lines)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "AI romanisation failed for %s", sessionKey)
                    emptyList()
                } finally {
                    _running.value = false
                }
            }
        inFlight[requestKey] = job
        scope.async {
            val result = runCatching { job.await() }.getOrDefault(emptyList())
            inFlight.remove(requestKey)

            val byLine = LinkedHashMap<String, String>(result.size)
            lines.forEachIndexed { index, line ->
                val romanized = result.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                byLine.putIfAbsent(line.trim(), romanized)
            }
            if (byLine.isNotEmpty()) {
                cache[requestKey] = byLine
                trimCache(requestKey)
                publish(requestKey, byLine)
            } else {

                _requestOutcomes.tryEmit(RequestStatus.EMPTY_RESULT)
            }
        }
        return RequestStatus.STARTED
    }

    /** Lyrics content key + provider identity (see Settings.configKey). */
    private fun fullKey(
        sessionKey: String,
        settings: Settings,
    ): String = "$sessionKey|${settings.configKey}"

    private fun publish(
        requestKey: String,
        byLine: Map<String, String>,
    ) {

        _results.value = Result(sessionKey = requestKey, byLine = byLine)
    }

    private fun trimCache(keep: String) {
        if (cache.size <= MaxCachedTracks) return

        cache.keys.firstOrNull { it != keep }?.let { cache.remove(it) }
    }

    private const val MaxCachedTracks = 32
}
