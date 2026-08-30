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
import moe.rukamori.archivetune.constants.AiRomanizeExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.constants.AutoAiRomanizeLyricsKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AI-provided romanisation for the lyrics views.
 *
 * ## Why this is not just another branch inside [LyricsUtils]
 *
 * The built-in romanisers (Kuromoji for Japanese, hand-written tables for Korean/Hindi, ICU for
 * everything else) are pure, synchronous-ish and cheap, so the renderers call them **per line**. An
 * AI provider is the opposite on every count: it is network-bound, rate-limited and billed, so it has
 * to be called **once per track** with every line in one batch. That difference in granularity is why
 * this lives beside [LyricsUtils] rather than inside `romanizeLyricsLine`, and why results are held
 * in a per-track cache the renderers read from instead of being awaited inline.
 *
 * ## Lifecycle
 *
 * [request] is idempotent per session key: the first caller starts the work, everyone else joins the
 * same [Deferred]. Results land in [results], a StateFlow the renderers observe, so lyrics that were
 * already on screen pick up romanisation without a re-layout — the same mechanism
 * `LyricsEntry.romanizedTextFlow` uses for the built-in path.
 *
 * Results are memory-only and deliberately so. AI *translation* is persisted into `LyricsEntity`
 * because it replaces the lyrics text; romanisation is an annotation drawn above each line and has
 * nowhere to live in that schema without a second `source` value that could not coexist with
 * `AI_TRANSLATION`.
 */
object AiLyricsRomanization {
    private const val TAG = "AiRomanization"

    /**
     * Outcome of a [request] call. The manual "AI romanise now" menu action surfaces this to the
     * user as a specific toast so silent no-ops become actionable feedback.
     *
     * The auto-renderer path ignores the return value — it joins the in-flight [Deferred] and
     * re-reads [linesFor] when [results] emits, so the status is only useful to interactive callers.
     */
    enum class RequestStatus {
        /** A new request was started. Result will publish to [results] when the model responds. */
        STARTED,

        /** An existing cached result for this session key was re-published to [results]. */
        ALREADY_CACHED,

        /** A request for this session key is already in-flight. The caller joins the existing one. */
        IN_FLIGHT,

        /** [Settings.active] is false — provider/key/model are not fully configured. */
        SETTINGS_DISABLED,

        /** [linesOf] produced an empty list — lyrics are blank or `LYRICS_NOT_FOUND`. */
        NO_LYRICS,

        /** The lyrics' dominant language is in [Settings.excludedLanguages]. */
        EXCLUDED_LANGUAGE,

        /** No line contains any script the AI can romanise (e.g. all-Latin lyrics). */
        NO_ROMANIZABLE_SCRIPT,

        /**
         * The model returned no usable romanisation for any line (all-null or all-identical-to-source
         * echoes per [AiLyricsRomanizer]). Nothing was published to [results].
         */
        EMPTY_RESULT,
    }

    /** Everything the renderers need to decide whether, and how, to romanise with the AI. */
    @Immutable
    data class Settings(
        val enabled: Boolean,
        val auto: Boolean,
        val excludedLanguages: Set<String>,
        val config: AiServiceConfig,
    ) {
        /**
         * True when AI romanisation should take over from the built-in romanisers.
         *
         * Deliberately independent of [auto]: the user asked for the built-in romanisation to stop as
         * soon as AI romanisation is switched on, so a configured-but-not-automatic setup shows AI
         * results only, on demand, rather than silently mixing the two engines' spellings.
         */
        val active: Boolean get() = enabled && config.canCallApi

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

    /**
     * Romanisation for one lyrics session, addressed by the **original line text** rather than by
     * index.
     *
     * Index alignment is not available here, and that is not a simplification: the two renderers
     * parse the same lyrics into different index spaces. `LyricsV2` prepends a head entry and calls
     * `insertInstrumentalBreaks`, `LyricsEnhanced` does neither, and the lyrics menu's manual request
     * parses without either. All three derive the same [sessionKey] from the raw lyrics text, so an
     * index-aligned cache filled by one of them was applied off-by-N by the next — every line's
     * romanisation shifted onto its neighbour after a lyrics-style switch.
     *
     * Keying on the text is also just correct: identical lines have identical readings, so a chorus
     * repeat resolves from the first occurrence instead of costing another entry.
     *
     * ## The [nonce] field
     *
     * [Result] is intentionally NOT a `data class`: it carries a monotonically-increasing [nonce]
     * so that a re-[publish] of the same `byLine` map (e.g. when the user taps "AI romanise now"
     * again after a previous run cached the result) still emits through `MutableStateFlow`. With a
     * `data class`, `StateFlow.distinctUntilChanged` would silently swallow the re-emission because
     * the old and new values compare equal — the renderer would not re-resolve and the user would
     * see "clicking again does nothing" on a cached result. The [nonce] defeats that equality so
     * every explicit [publish] is observable. The [byLine] data is unchanged; only the identity of
     * the emission is made distinct.
     */
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

    /**
     * Signals that a romanisation finished. Renderers observe this only to know *when* to re-resolve;
     * the values themselves come from [linesFor], which is index-space independent.
     */
    val results: StateFlow<Result?> = _results.asStateFlow()

    /**
     * Terminal status of an async [request]. The manual menu caller observes this to surface a
     * follow-up toast when an async `STARTED` request later completes with no usable romanisation
     * (the [RequestStatus.EMPTY_RESULT] case — the model echoed back the source text or returned
     * all-null). Synchronous outcomes (SETTINGS_DISABLED, NO_LYRICS, EXCLUDED_LANGUAGE,
     * NO_ROMANIZABLE_SCRIPT, IN_FLIGHT, ALREADY_CACHED, STARTED) are returned from [request] directly
     * and the menu toasts immediately, so they are NOT re-emitted here. Only [RequestStatus.EMPTY_RESULT]
     * is emitted because it is the only status that materialises asynchronously.
     *
     * The flow is `replay = 0` so a fresh collector doesn't see a stale outcome from a previous
     * request, and `extraBufferCapacity = 1` so a slow collector doesn't drop the emission.
     */
    private val _requestOutcomes = MutableSharedFlow<RequestStatus>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val requestOutcomes: SharedFlow<RequestStatus> = _requestOutcomes.asSharedFlow()

    private val _running = MutableStateFlow(false)

    /** True while a request is outstanding, for progress affordances in the lyrics menu. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Reads the AI-romanisation settings inside composition.
     *
     * Mirrors how `AiIntegrationSettings` builds its own config so a provider/key/model change takes
     * effect on the next recomposition without any explicit invalidation.
     */
    @Composable
    fun rememberSettings(): Settings {
        val (enabled) = rememberPreference(AiRomanizeLyricsKey, defaultValue = false)
        val (auto) = rememberPreference(AutoAiRomanizeLyricsKey, defaultValue = false)
        val (excluded) = rememberPreference(AiRomanizeExcludedLanguagesKey, defaultValue = emptySet())
        // Note: romanisation exclusion is now read ONLY from the
        // romanisation exclusion list — NOT merged with the auto-translation
        // exclusion list. The previous merge (commit 717db4f19) was an
        // attempt to honour the user's mental model of "if I exclude Hindi
        // from auto-translation, Hindi shouldn't translate", but the side
        // effect was that AI romanisation stopped working for every
        // language the user had excluded from auto-translation — which
        // for many users is exactly the set of languages they want
        // romanised (Hindi, Korean, Japanese, etc.). Per user request
        // (2026-08-28): "Auto Ai romanisation doesn't work now after the
        // last commit. Fix it." The romanisation and translation paths
        // are now decoupled again; if a user excludes Hindi from
        // auto-translation but leaves it enabled for romanisation, the
        // AI romanisation will run and (for line-synced lyrics) land in
        // the translation slot. The translation-slot-sharing is the
        // mocharealm library's only path for line-synced phonetics and
        // cannot be fixed without a renderer fork.
        val provider by rememberEnumPreference(AiProviderKey, AiProvider.NONE)
        val (apiKey) = rememberPreference(AiApiKeyKey, defaultValue = "")
        val (customEndpoint) = rememberPreference(AiCustomEndpointKey, defaultValue = "")
        val (selectedModel) = rememberPreference(AiSelectedModelKey, defaultValue = "")
        val (customModel) = rememberPreference(AiCustomModelKey, defaultValue = "")

        return remember(enabled, auto, excluded, provider, apiKey, customEndpoint, selectedModel, customModel) {
            Settings(
                enabled = enabled,
                auto = auto,
                excludedLanguages = excluded,
                config =
                    AiServiceConfig(
                        provider = provider,
                        apiKey = apiKey,
                        customEndpoint = customEndpoint,
                        model = if (provider == AiProvider.CUSTOM) customModel else selectedModel,
                    ),
            )
        }
    }

    /**
     * Stable identity for "this track's lyrics as currently parsed". Romanisation is keyed on it so a
     * refetch, an edit or a translation invalidates the cache while a replay of the same text reuses
     * it.
     */
    fun sessionKey(
        mediaId: String?,
        lyrics: String?,
    ): String = "${mediaId.orEmpty()}|${lyrics?.length ?: 0}|${lyrics?.hashCode() ?: 0}"

    /**
     * Resolves romanisation for [lines] in the caller's own index space, or an empty list when
     * nothing has been fetched for [sessionKey] yet.
     *
     * This is the only way to read results, deliberately: it maps each line by its text, so a caller
     * that prepends a head entry or inserts instrumental breaks gets the same answer as one that
     * doesn't. Handing out the raw map instead would invite the index-aligned mistake back. See
     * [Result].
     */
    fun linesFor(
        sessionKey: String,
        lines: List<String>,
    ): List<String?> {
        val byLine = cache[sessionKey] ?: return emptyList()
        return lines.map { byLine[it.trim()] }
    }

    /**
     * Parses [lyrics] into lines to send, for the manual "Romanise with AI" action.
     *
     * Does not have to agree with either renderer's index space — results are stored by line text —
     * so this only has to produce the same *set* of lines. It parses rather than splitting on newlines
     * so that timestamps and TTML markup never reach the model.
     */
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

    /**
     * Requests romanisation for [lines], joining an in-flight request for the same [sessionKey].
     *
     * Returns a [RequestStatus] so the manual menu caller can show specific feedback instead of
     * the previous behaviour of unconditionally toasting "AI romanisation started" even when this
     * function silently no-op'd. The auto-renderer path ignores the return value.
     *
     * Idempotent per track — the coordinator joins an in-flight call and serves a cached one — so
     * a second tap is free. A cached-result re-publish intentionally emits a distinct [Result] (via
     * the [Result.nonce] field) so the renderer re-resolves even when the [Result.byLine] contents
     * are unchanged; this fixes the "clicking AI romanise again does nothing" cache-hit masking.
     */
    fun request(
        sessionKey: String,
        lines: List<String>,
        settings: Settings,
        force: Boolean = false,
    ): RequestStatus {
        if (!settings.active) return RequestStatus.SETTINGS_DISABLED
        if (lines.isEmpty()) return RequestStatus.NO_LYRICS

        cache[sessionKey]?.let { cached ->
            publish(sessionKey, cached)
            return RequestStatus.ALREADY_CACHED
        }
        if (inFlight.containsKey(sessionKey)) return RequestStatus.IN_FLIGHT

        // The exclusion list is checked against the whole lyric rather than per line: a single track
        // is one language for this purpose, and per-line detection would send a Japanese song's
        // occasional English hook to the model as if it were a different track.
        //
        // Comparison goes through LyricsUtils so this and the translation gate agree on what an
        // exclusion means — including that a detected "CHINESE" has to match the picker's
        // CHINESE_SIMPLIFIED / CHINESE_TRADITIONAL, which a direct string compare never did.
        val dominant = LyricsUtils.detectDominantLanguageCode(lines.joinToString("\n"))
        if (dominant != null && LyricsUtils.matchesExcludedLanguage(dominant, settings.excludedLanguages)) {
            Timber.tag(TAG).d("skipping %s: %s is excluded", sessionKey, dominant)
            return RequestStatus.EXCLUDED_LANGUAGE
        }
        // Nothing to do for lyrics that are already Latin script. Reuses the built-in detectors so
        // the two engines agree on which lines are candidates at all.
        //
        // The `force` flag is set ONLY by the manual "AI romanise now" menu action — auto-renderer
        // callers and other automatic triggers never bypass this gate. When the user explicitly
        // taps the menu, we hand the lines to the model even when they look Latin-script; the model
        // is instructed to echo Latin lines unchanged, so the visible effect is still "nothing
        // changes for Latin lyrics" — but the user no longer sees a misleading toast that says
        // "nothing to romanise" when they explicitly asked the AI to do it. Manual invocation now
        // behaves as "perform the operation the button promises".
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
        inFlight[sessionKey] = job
        scope.async {
            val result = runCatching { job.await() }.getOrDefault(emptyList())
            inFlight.remove(sessionKey)
            // Index-aligned coming out of the romanizer, then immediately folded into the text-keyed
            // form every reader uses. `associate` would keep the *last* occurrence of a repeated
            // line; build it forwards so a chorus resolves from the first, which is the one whose
            // batch had the most surrounding context.
            val byLine = LinkedHashMap<String, String>(result.size)
            lines.forEachIndexed { index, line ->
                val romanized = result.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                byLine.putIfAbsent(line.trim(), romanized)
            }
            if (byLine.isNotEmpty()) {
                cache[sessionKey] = byLine
                trimCache(sessionKey)
                publish(sessionKey, byLine)
            } else {
                // Surface EMPTY_RESULT via the outcomes flow so the manual menu caller can show a
                // follow-up toast explaining why the "Romanising…" toast the user just saw did not
                // result in any visible romanisation. (Auto-renderer path ignores this — it never
                // toasts.)
                _requestOutcomes.tryEmit(RequestStatus.EMPTY_RESULT)
            }
        }
        return RequestStatus.STARTED
    }

    private fun publish(
        sessionKey: String,
        byLine: Map<String, String>,
    ) {
        // A new `Result` instance is allocated per publish — the `nonce` field guarantees a fresh
        // identity even when `byLine` is structurally identical to the previously-published value,
        // so `MutableStateFlow.value = ...` is observable to collectors that compare by reference
        // (e.g. Compose `collectAsStateWithLifecycle`).
        _results.value = Result(sessionKey = sessionKey, byLine = byLine)
    }

    private fun trimCache(keep: String) {
        if (cache.size <= MaxCachedTracks) return
        // ConcurrentHashMap has no LRU; the eviction only has to keep memory bounded, and the
        // AiLyricsRomanizer already holds its own LRU of the expensive part (the model responses).
        cache.keys.firstOrNull { it != keep }?.let { cache.remove(it) }
    }

    private const val MaxCachedTracks = 8
}
