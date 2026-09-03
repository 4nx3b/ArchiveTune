/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.LyricsProviderOrderKey
import moe.rukamori.archivetune.constants.PreferredLyricsProvider
import moe.rukamori.archivetune.constants.PrioritizeWordSyncedLyricsKey
import moe.rukamori.archivetune.constants.deserializeLyricsProviderOrder
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject

class LyricsHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val networkConnectivity: NetworkConnectivityObserver,
    ) {
        private val baseProviders =
            listOf(
                BetterLyricsProvider,
                BetterLyricsPortatoProvider,
                YouLyPlusLyricsProvider,
                LrcLibLyricsProvider,
                KuGouLyricsProvider,
                // MegalobizLyricsProvider removed per user request (2026-08-28):
                // "Remove megalobiz lyrics provider". The provider file was
                // deleted; the PreferredLyricsProvider enum and the
                // DefaultLyricsProviderOrder list below also no longer
                // include the MEGALOBIZ entry.
                //
                // SimpMusicLyricsProvider and BiniLyricsProvider removed per
                // user request (2026-08-30): "Remove simpmusic and binilyrics
                // lyrics provider and their entire code too". Provider files,
                // enum entries, settings toggles, gradle module includes, and
                // the underlying :lyrics:simpmusic / :lyrics:paxsenix gradle
                // modules have all been deleted.
                UnisonLyricsProvider,
                // Ported from upstream (2026-08-31 window): Apple Music account
                // lyrics via the logged-in pool account. The Paxsenix* / Tidal /
                // Deezer provider entries from the same upstream hunk are NOT
                // ported — this fork removed the entire Paxsenix layer on
                // 2026-08-30 (batch-10) and never carried the account Tidal /
                // Deezer lyrics providers.
                AppleMusicAccountLyricsProvider,
                YouTubeSubtitleLyricsProvider,
                YouTubeLyricsProvider,

                MusixmatchExperimentalLyricsProvider,
            )

        private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
        private val singleLyricsCache = LruCache<String, LyricsResult>(MAX_CACHE_SIZE)

        suspend fun getLyrics(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): String = getLyricsWithProvider(
            mediaMetadata = mediaMetadata,
            preferredProviderOnly = preferredProviderOnly,
            forceRefresh = forceRefresh,
        ).lyrics

        suspend fun getLyricsWithProvider(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): LyricsResult {
            val cacheKey = mediaMetadata.lyricsCacheKey

            // Read the "Prioritize Word Synced Lyrics" toggle once up-front. We need
            // it during the cache check below because when the toggle is ON, cached
            // non-word-synced lyrics must be treated as stale — otherwise turning the
            // toggle on and replaying a song that was already cached would keep
            // returning the old line-synced/plain result and the word-synced lookup
            // would never run.
            val prioritizeWordSynced =
                !preferredProviderOnly && (context.dataStore[PrioritizeWordSyncedLyricsKey] ?: false)

            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                singleLyricsCache.get(cacheKey)?.let { cached ->
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)
                    // When prioritizing word-synced lyrics, only honor the cache if the
                    // cached lyrics are themselves word-synced. Otherwise skip the cache
                    // so the word-synced lookup gets a chance to find better lyrics.
                    if (!prioritizeWordSynced || cachedIsWordSynced) {
                        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                        return cached
                    }
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        "Skipping cache for ${mediaMetadata.title}: prioritizeWordSynced=true, cached lyrics not word-synced",
                    )
                }

                val cached = cache.get(cacheKey)?.firstOrNull()
                if (cached != null) {
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)
                    if (!prioritizeWordSynced || cachedIsWordSynced) {
                        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                        return cached
                    }
                }
            }

            GlobalLog.append(
                Log.DEBUG,
                "LyricsHelper",
                "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString {
                    it.name
                }}, Album: ${mediaMetadata.album?.title})",
            )

            val isNetworkAvailable =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
                return LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)
            }

            // When "Prioritize Word Synced Lyrics" is ON (and the caller isn't asking
            // for the preferred provider only), first try to obtain word-synced lyrics
            // from the three word-sync-capable providers (BetterLyrics, YouLyPlus,
            // Unison). These are queried DIRECTLY — bypassing both the per-provider
            // enable toggles AND the user's provider-priority order — because when
            // this feature is on the user has explicitly said they want word-synced
            // lyrics from these three sources first, full stop.
            //
            // If any of the three returns lyrics that are actually word-synced
            // (QRC/YRC/TTML with word-level timings), we use that immediately.
            // Otherwise we fall through to the normal priority ranking across all
            // enabled providers (the regular flow below).
            if (prioritizeWordSynced) {
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "PrioritizeWordSynced=on: querying BetterLyrics/YouLyPlus/Unison for word-synced lyrics",
                )
                val wordSyncedResult = tryFetchWordSyncedFromPriorityProviders(mediaMetadata)
                if (wordSyncedResult != null && isMeaningfulLyrics(wordSyncedResult.lyrics)) {
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        "Word-synced lyrics found via ${wordSyncedResult.providerName}",
                    )
                    singleLyricsCache.put(cacheKey, wordSyncedResult)
                    return wordSyncedResult
                }
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "No word-synced lyrics from priority providers, falling back to normal priority flow",
                )
            }

            val ordered =
                orderedProviders()
                    .filter { it.isEnabled(context) }
                    .filter { supportsMediaId(it, mediaMetadata.id) }
            val providers = if (preferredProviderOnly) ordered.take(1) else ordered

            val result = fetchPriorityLyricsResult(providers, mediaMetadata)
            if (isMeaningfulLyrics(result.lyrics)) {
                singleLyricsCache.put(cacheKey, result)
            }

            return result
        }

        /**
         * Queries the three word-sync-capable providers (BetterLyrics, YouLyPlus,
         * Unison) IN PARALLEL and returns the first one whose response is actually
         * word-synced (QRC/YRC/TTML with word-level timings). Returns null if none
         * of them return word-synced lyrics, so the caller can fall back to the
         * normal priority flow.
         *
         * IMPORTANT: This is invoked when the "Prioritize Word Synced Lyrics" toggle
         * is ON. The three providers are queried DIRECTLY — their per-provider enable
         * toggles in the Lyrics Providers settings screen are deliberately bypassed,
         * because the toggle being ON is an explicit override that says "I want
         * word-synced lyrics from these three sources regardless of any other
         * provider config". Likewise the user's provider-priority order is ignored
         * here — among these three, the first one (in the fixed order below) that
         * returns word-synced lyrics wins.
         *
         * Only results that pass [LyricsUtils.hasWordSyncedLyrics] are eligible —
         * a provider returning plain LRC or plain text is ignored, even if it was
         * the only one to respond.
         */
        private suspend fun tryFetchWordSyncedFromPriorityProviders(
            mediaMetadata: MediaMetadata,
        ): LyricsResult? {
            // Fixed canonical order. This is independent of the user's provider
            // priority order so the behaviour is predictable when the toggle is ON.
            val wordSyncCapable: List<LyricsProvider> =
                listOf(
                    BetterLyricsProvider,
                    YouLyPlusLyricsProvider,
                    UnisonLyricsProvider,
                )

            val artist = mediaMetadata.artists.joinToString { it.name }
            val results =
                supervisorScope {
                    wordSyncCapable
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val lyrics =
                                    withTimeoutOrNull(WORD_SYNC_PROVIDER_TIMEOUT_MS) {
                                        fetchProviderLyrics(provider, mediaMetadata, artist)
                                    }
                                if (lyrics == null) {
                                    GlobalLog.append(
                                        Log.DEBUG,
                                        "LyricsHelper",
                                        "${provider.name} returned no lyrics (timeout or error)",
                                    )
                                    null
                                } else {
                                    val isWordSynced = LyricsUtils.hasWordSyncedLyrics(lyrics)
                                    GlobalLog.append(
                                        Log.DEBUG,
                                        "LyricsHelper",
                                        "${provider.name} returned lyrics (word-synced=$isWordSynced, length=${lyrics.length})",
                                    )
                                    if (isWordSynced) provider.name to lyrics else null
                                }
                            }
                        }.mapNotNull { it.await() }
                }

            if (results.isEmpty()) return null

            // Walk results in canonical provider order (because `wordSyncCapable`
            // is ordered) and return the first one. We already filtered out
            // non-word-synced responses above, so every entry here is word-synced.
            val first = results.first()
            return LyricsResult(providerName = first.first, lyrics = first.second)
        }

        suspend fun getAllLyrics(
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
            forceRefresh: Boolean = false,
            callback: (LyricsResult) -> Unit,
        ) {
            val cacheKey = lyricsCacheKey(songTitle, songArtists)
            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                cache.get(cacheKey)?.let { results ->
                    results.forEach(callback)
                    return
                }
            }

            val isNetworkAvailable =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                return
            }

            val allResult = mutableListOf<LyricsResult>()
            val providers = orderedProviders().filter { it.isEnabled(context) }

            // Fan out all enabled providers in parallel. The previous implementation
            // iterated providers sequentially with `forEach`, which meant the search
            // dialog stayed on "Searching providers…" until every provider returned in
            // order — a single slow provider (Musixmatch can take 10–15s) held back
            // results from faster ones (LRCLIB ~100ms). Running them concurrently lets
            // results stream into the UI as each provider finishes.
            //
            // Each provider call is wrapped in a per-provider timeout so a hung
            // provider can't pin the search dialog indefinitely. Failures and timeouts
            // are reported but never propagated — the dialog just shows fewer results.
            withContext(Dispatchers.IO) {
                supervisorScope {
                    providers.map { provider ->
                        async {
                            try {
                                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                    provider.getAllLyrics(mediaId, songTitle, songArtists, songAlbum, duration) lyricsCallback@{ lyrics ->
                                        val normalizedLyrics = LyricsUtils.lyricsOrNotFound(lyrics)
                                        if (normalizedLyrics == LYRICS_NOT_FOUND) return@lyricsCallback
                                        val result = LyricsResult(provider.name, normalizedLyrics)
                                        synchronized(allResult) {
                                            allResult += result
                                        }
                                        callback(result)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                reportException(e)
                            }
                        }
                    }.forEach { it.await() }
                }
            }
            cache.put(cacheKey, allResult.toList())
        }

        /**
         * Resolves lyrics from all providers in parallel and returns the best result by
         * (sync tier: word > line > plain) then by provider priority (lower index wins).
         *
         * This is the original priority-respecting implementation. The previous
         * "streaming first-result-wins" approach (commit 9975a15ac) was faster but
         * silently broke priority — a fast low-priority provider's line-synced lyrics
         * would preempt a slightly slower top-priority provider's word-synced lyrics
         * during the grace window, because the grace period wasn't long enough to cover
         * the typical 10–15s Musixmatch latency.
         *
         * Speed: each provider call is wrapped in [withTimeoutOrNull] so a single hung
         * provider can't pin the panel for its full 15–20s timeout. Providers that
         * exceed [PROVIDER_TIMEOUT_MS] are simply dropped from the ranking — they
         * contribute nothing to the result. The hard ceiling on panel load latency is
         * therefore min(provider timeout, slowest responsive provider's response time),
         * which in practice is the provider timeout (~8s) since at least one provider
         * usually responds within a few seconds.
         */
        private suspend fun fetchPriorityLyricsResult(
            providers: List<LyricsProvider>,
            mediaMetadata: MediaMetadata,
        ): LyricsResult {
            if (providers.isEmpty()) return LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)

            val artist = mediaMetadata.artists.joinToString { it.name }
            val results =
                supervisorScope {
                    providers
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val lyrics =
                                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                        fetchProviderLyrics(provider, mediaMetadata, artist)
                                    }
                                if (lyrics == null) null else provider.name to lyrics
                            }
                        }.mapNotNull { it.await() }
                }

            if (results.isEmpty()) return LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)

            // Ranking: word-synced > line-synced > plain. `firstOrNull` walks the
            // results in provider-priority order (because `providers` is ordered), so
            // when multiple providers return the same tier the higher-priority one
            // wins — this is what restores the priority order the streaming
            // implementation broke.
            val wordSynced = results.firstOrNull { LyricsUtils.hasWordSyncedLyrics(it.second) }
            if (wordSynced != null) return LyricsResult(providerName = wordSynced.first, lyrics = wordSynced.second)

            val lineSynced = results.firstOrNull { LyricsUtils.isLineSyncedLrc(it.second) }
            if (lineSynced != null) return LyricsResult(providerName = lineSynced.first, lyrics = lineSynced.second)

            val first = results.first()
            return LyricsResult(providerName = first.first, lyrics = first.second)
        }

        private suspend fun fetchProviderLyrics(
            provider: LyricsProvider,
            mediaMetadata: MediaMetadata,
            artist: String,
        ): String? =
            try {
                provider
                    .getLyrics(
                        mediaMetadata.id,
                        mediaMetadata.title,
                        artist,
                        mediaMetadata.album?.title,
                        mediaMetadata.duration,
                    ).fold(
                        onSuccess = { lyrics ->
                            LyricsUtils.lyricsOrNotFound(lyrics).takeIf { it != LYRICS_NOT_FOUND }
                        },
                        onFailure = {
                            reportException(it)
                            null
                        },
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                null
            }

        private suspend fun orderedProviders(): List<LyricsProvider> {
            val orderStr = context.dataStore.data.first()[LyricsProviderOrderKey]
            val orderedEnums = deserializeLyricsProviderOrder(orderStr)
            val providerMap: Map<PreferredLyricsProvider, LyricsProvider> =
                mapOf(
                    PreferredLyricsProvider.LRCLIB to LrcLibLyricsProvider,
                    PreferredLyricsProvider.KUGOU to KuGouLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS to BetterLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS_PORTATO to BetterLyricsPortatoProvider,
                    PreferredLyricsProvider.YOULY_PLUS to YouLyPlusLyricsProvider,
                    // SIMPMUSIC and BINI_LYRICS entries removed along with the
                    // providers themselves — see baseProviders list above.
                    // APPLE_MUSIC (account lyrics) ported from upstream
                    // 2026-08-31 window; Paxsenix* / TIDAL / DEEZER not ported
                    // (see baseProviders note).
                    PreferredLyricsProvider.APPLE_MUSIC to AppleMusicAccountLyricsProvider,
                    PreferredLyricsProvider.UNISON to UnisonLyricsProvider,
                    PreferredLyricsProvider.MUSIXMATCH_EXPERIMENTAL to MusixmatchExperimentalLyricsProvider,
                )
            val userOrdered = orderedEnums.mapNotNull { providerMap[it] }
            val rest = baseProviders.filterNot { it in userOrdered }
            return userOrdered + rest
        }

        private fun isMeaningfulLyrics(lyrics: String): Boolean = LyricsUtils.hasMeaningfulLyricsContent(lyrics)

        private fun supportsMediaId(
            provider: LyricsProvider,
            mediaId: String,
        ): Boolean {
            val isNonYouTubeId = mediaId.isTelegramMediaId() || mediaId.isLocalMediaId()
            if (!isNonYouTubeId) return true
            // SimpMusicLyricsProvider clause removed — provider no longer exists.
            return provider !is YouTubeLyricsProvider &&
                provider !is YouTubeSubtitleLyricsProvider
        }

        fun clearCache() {
            cache.evictAll()
            singleLyricsCache.evictAll()
        }

        /**
         * Probes every provider registered in [baseProviders] for a fixed, well-known
         * test case (Ed Sheeran — "Shape of You", 233s) and reports each provider's
         * outcome. Used by the "Lyrics test" entry in the Lyrics Providers settings
         * page so the user can verify at a glance which providers are reachable and
         * returning meaningful lyrics, without having to navigate to a track and
         * inspect the lyrics source.
         *
         * The test is best-effort: a provider that returns `LYRICS_NOT_FOUND` for
         * the test case is marked as `NoMatch` (provider works, just no lyrics for
         * this specific test track) rather than `Failed`, so the user can
         * distinguish "provider is down" from "provider doesn't have this song".
         * Network/exception failures are `Failed`.
         *
         * Returns a snapshot list — callers should treat it as the final result
         * of a single sweep, not as an observable stream.
         */
        suspend fun testAllProviders(): List<LyricsProviderTestResult> {
            // Fixed test case: a wildly popular song with broad catalog coverage.
            // Picked because every major lyrics catalog indexes it under both
            // English and Latin-normalised titles, which keeps false negatives
            // to a minimum.
            val testTitle = "Shape of You"
            val testArtist = "Ed Sheeran"
            val testDuration = 233
            val testId = "test-ed-sheeran-shape-of-you"

            val enabled = baseProviders.filter { it.isEnabled(context) }
            return coroutineScope {
                enabled.map { provider ->
                    async(Dispatchers.IO) {
                        val outcome =
                            try {
                                val result =
                                    withTimeoutOrNull(PROVIDER_TEST_TIMEOUT_MS) {
                                        provider.getLyrics(testId, testTitle, testArtist, null, testDuration)
                                    }
                                when {
                                    result == null ->
                                        LyricsProviderTestOutcome.TIMEOUT
                                    result.isFailure ->
                                        LyricsProviderTestOutcome.FAILED
                                    result.getOrNull().isNullOrBlank() ||
                                        result.getOrNull() == LYRICS_NOT_FOUND ->
                                        LyricsProviderTestOutcome.NO_MATCH
                                    else ->
                                        LyricsProviderTestOutcome.OK
                                }
                            } catch (_: CancellationException) {
                                throw CancellationException()
                            } catch (_: Throwable) {
                                LyricsProviderTestOutcome.FAILED
                            }
                        LyricsProviderTestResult(
                            providerName = provider.name,
                            outcome = outcome,
                        )
                    }
                }.awaitAll()
            }
        }

        private fun invalidateCache(cacheKey: String) {
            cache.remove(cacheKey)
            singleLyricsCache.remove(cacheKey)
        }

        private val MediaMetadata.lyricsCacheKey: String
            get() =
                lyricsCacheKey(
                    title = title,
                    artists = artists.joinToString { it.name },
                )

        private fun lyricsCacheKey(
            title: String,
            artists: String,
        ): String = "$artists-$title".replace(" ", "")

        companion object {
            private const val MAX_CACHE_SIZE = 16

            // Per-provider hard timeout for the normal priority flow. Provider calls
            // that exceed this are cancelled and dropped from ranking. Tuned to be
            // long enough for typical provider latency (~3–5s for Musixmatch under
            // good conditions) but short enough that a hung provider can't pin the
            // lyrics panel.
            private const val PROVIDER_TIMEOUT_MS = 8_000L

            // Longer timeout for the "Prioritize Word Synced Lyrics" path. YouLyPlus
            // in particular fans out across 5 mirrors × 2 endpoints (up to 10 HTTP
            // requests in sequence) and can legitimately take 10–15s. Using the
            // regular 8s timeout here caused YouLyPlus to be silently skipped even
            // when it had word-synced lyrics available — exactly the bug the user
            // reported. Since this path only runs once per song when the toggle is
            // ON (and the user has explicitly opted in for higher-quality lyrics),
            // the extra latency is acceptable.
            private const val WORD_SYNC_PROVIDER_TIMEOUT_MS = 15_000L

            /** Per-provider budget for the "Lyrics test" sweep in [testAllProviders]. */
            private const val PROVIDER_TEST_TIMEOUT_MS = 12_000L
        }
    }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

/**
 * Outcome of probing a single provider through [LyricsHelper.testAllProviders].
 *
 * `OK` means the provider returned meaningful lyrics for the test case.
 * `NO_MATCH` means the provider responded cleanly but had no lyrics for the
 * test track — i.e. the provider is reachable, just empty for this query.
 * `TIMEOUT` and `FAILED` indicate the provider could not be reached or
 * errored out within the per-provider budget.
 */
enum class LyricsProviderTestOutcome {
    OK,
    NO_MATCH,
    TIMEOUT,
    FAILED,
}

/**
 * One row in the "Lyrics test" dialog — the provider's display name and the
 * outcome of probing it. See [LyricsHelper.testAllProviders] for the source.
 */
data class LyricsProviderTestResult(
    val providerName: String,
    val outcome: LyricsProviderTestOutcome,
)
