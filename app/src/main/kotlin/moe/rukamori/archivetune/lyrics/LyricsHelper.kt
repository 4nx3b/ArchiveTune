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

                UnisonLyricsProvider,

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

            val prioritizeWordSynced =
                !preferredProviderOnly && (context.dataStore[PrioritizeWordSyncedLyricsKey] ?: false)

            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                singleLyricsCache.get(cacheKey)?.let { cached ->
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)

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

        private suspend fun tryFetchWordSyncedFromPriorityProviders(
            mediaMetadata: MediaMetadata,
        ): LyricsResult? {

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

            return provider !is YouTubeLyricsProvider &&
                provider !is YouTubeSubtitleLyricsProvider
        }

        fun clearCache() {
            cache.evictAll()
            singleLyricsCache.evictAll()
        }

        suspend fun testAllProviders(): List<LyricsProviderTestResult> {

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

            private const val PROVIDER_TIMEOUT_MS = 8_000L

            private const val WORD_SYNC_PROVIDER_TIMEOUT_MS = 15_000L

            private const val PROVIDER_TEST_TIMEOUT_MS = 12_000L
        }
    }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

enum class LyricsProviderTestOutcome {
    OK,
    NO_MATCH,
    TIMEOUT,
    FAILED,
}

data class LyricsProviderTestResult(
    val providerName: String,
    val outcome: LyricsProviderTestOutcome,
)
