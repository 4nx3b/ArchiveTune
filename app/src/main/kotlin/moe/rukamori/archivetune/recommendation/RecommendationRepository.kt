/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.RecommendationsEnabledKey
import moe.rukamori.archivetune.constants.RecommendationRecencyWeightKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.RecFeedbackEntity
import moe.rukamori.archivetune.db.entities.RecPlaybackSignalEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.utils.dataStore

class RecommendationRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default

        private val statsMutex = Mutex()
        private var cachedInput: RecEngineInput? = null
        private var cachedAtMs: Long = 0L
        private val cacheTtlMs = 30_000L

        private val recentlyRecommended = MutableStateFlow<Map<String, Long>>(emptyMap())
        private val refreshTick = MutableStateFlow(0)

        val recommendationsEnabled: Flow<Boolean> =
            context.dataStore.data
                .map { it[RecommendationsEnabledKey] ?: true }
                .distinctUntilChanged()

        val recencyWeight: Flow<Float> =
            context.dataStore.data
                .map { it[RecommendationRecencyWeightKey] ?: 0.5f }
                .distinctUntilChanged()

        suspend fun refresh() {
            refreshTick.value++
            withContext(ioDispatcher) {
                statsMutex.withLock {
                    cachedInput = null
                    cachedAtMs = 0L
                }
            }
        }

        suspend fun buildState(context: RecContext): RecsState =
            withContext(computeDispatcher) {
                val enabled = withContext(ioDispatcher) { recommendationsEnabled.first() }
                if (!enabled) return@withContext RecsState(loading = false, coldStart = true)

                val input = loadInput()
                if (input.songs.isEmpty()) {
                    return@withContext RecsState(loading = false, coldStart = true)
                }

                val config =
                    RecEngineConfig(
                        recencyWeight = withContext(ioDispatcher) { recencyWeight.first() },
                    )

                val feedback = input.feedback
                val recently = recentlyRecommended.value

                val personalizedCandidates =
                    RecommendationEngine.generateCandidates(input.songs, input.artists, context, config)
                val personalized =
                    RecommendationEngine.rank(
                        candidates = personalizedCandidates,
                        songs = input.songs,
                        artists = input.artists,
                        feedback = feedback,
                        recentlyRecommended = recently,
                        context = context,
                        config = config,
                    )

                val discoverConfig = config.copy(outputLimit = config.outputLimit, diversityArtistCap = 3)
                val discoverCandidates =
                    personalizedCandidates.filter { it.stats.playCount == 0 }
                val discover =
                    RecommendationEngine.rank(
                        candidates = discoverCandidates,
                        songs = input.songs,
                        artists = input.artists,
                        feedback = feedback,
                        recentlyRecommended = recently,
                        context = RecContext(),
                        config = discoverConfig,
                    )

                val similarSounds =
                    if (context.currentSongId != null) {
                        RecommendationEngine.rank(
                            candidates = personalizedCandidates,
                            songs = input.songs,
                            artists = input.artists,
                            feedback = feedback,
                            recentlyRecommended = recently,
                            context = context,
                            config = config,
                        ).filter { it.reason == RecReason.SAME_ARTIST_AS_CONTEXT || it.reason == RecReason.SAME_ALBUM_AS_CONTEXT }
                    } else {
                        emptyList()
                    }

                recordRecentlyRecommended(personalized + discover)

                val coldStart = RecommendationEngine.isColdStart(input.songs, input.artists, config)
                RecsState(
                    loading = false,
                    personalized = personalized,
                    discover = discover,
                    similarSounds = similarSounds,
                    coldStart = coldStart,
                )
            }

        suspend fun discoverWithFilters(filters: DiscoveryFilters): List<RankedRecommendation> =
            withContext(computeDispatcher) {
                val input = loadInput()
                if (input.songs.isEmpty()) return@withContext emptyList()
                val config = RecEngineConfig(
                    recencyWeight = withContext(ioDispatcher) { recencyWeight.first() },
                )
                val candidates =
                    RecommendationEngine.generateDiscoveryCandidates(input.songs, input.artists, filters, config)
                RecommendationEngine.rank(
                    candidates = candidates,
                    songs = input.songs,
                    artists = input.artists,
                    feedback = input.feedback,
                    recentlyRecommended = recentlyRecommended.value,
                    context = RecContext(),
                    config = config,
                )
            }

        suspend fun recordFeedback(
            songId: String?,
            artistId: String?,
            kind: RecFeedbackKind,
        ) {
            withContext(ioDispatcher) {
                database.insertRecFeedback(
                    RecFeedbackEntity(
                        targetSongId = songId,
                        targetArtistId = artistId,
                        kind = kind.name,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
                if (kind == RecFeedbackKind.LIKE) {
                    songId?.let { database.removeRecFeedbackForSong(it, RecFeedbackKind.DISLIKE.name) }
                }
                if (kind == RecFeedbackKind.DISLIKE) {
                    songId?.let { database.removeRecFeedbackForSong(it, RecFeedbackKind.LIKE.name) }
                }
                invalidateCache()
            }
        }

        suspend fun recordPlaybackSignal(signal: RecPlaybackSignalSnapshot) {
            withContext(ioDispatcher) {
                database.insertRecPlaybackSignal(
                    RecPlaybackSignalEntity(
                        songId = signal.songId,
                        startedAtMs = signal.startedAtMs,
                        listenedMs = signal.listenedMs,
                        durationMs = signal.durationMs,
                        completed = signal.completed,
                        leftAtRatio = signal.leftAtRatio,
                        replay = signal.replay,
                    ),
                )
                val cutoff = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
                database.trimRecPlaybackSignals(cutoff)
                invalidateCache()
            }
        }

        suspend fun clearAllSignals() {
            withContext(ioDispatcher) {
                database.clearRecPlaybackSignals()
                database.clearRecFeedback()
                recentlyRecommended.value = emptyMap()
                invalidateCache()
            }
        }

        private suspend fun recordRecentlyRecommended(items: List<RankedRecommendation>) {
            if (items.isEmpty()) return
            val now = System.currentTimeMillis()
            recentlyRecommended.value = recentlyRecommended.value + items.associate { it.stats.songId to now }
        }

        private suspend fun invalidateCache() {
            statsMutex.withLock {
                cachedInput = null
                cachedAtMs = 0L
            }
        }

        private data class RecEngineInput(
            val songs: List<RecSongStats>,
            val artists: List<RecArtistStats>,
            val feedback: List<RecFeedback>,
        )

        private suspend fun loadInput(): RecEngineInput {
            val now = System.currentTimeMillis()
            statsMutex.withLock {
                cachedInput?.let {
                    if (now - cachedAtMs < cacheTtlMs) return it
                }
            }

            val input =
                withContext(ioDispatcher) {
                    val songsSnapshot = database.songsByPlayTimeAsc().first()

                    val eventAggregates = database.recEventAggregates()
                    val playlistAdds = database.recPlaylistAddCounts()
                    val signals = database.recPlaybackSignalsSince(0L)
                    val feedbackEntities = database.allRecFeedback()

                    val aggregatesBySong = eventAggregates.associateBy { it.songId }
                    val playlistAddsBySong = playlistAdds.associateBy { it.songId }

                    val signalsBySong = signals.groupBy { it.songId }

                    val songs = songsSnapshot.map { song ->
                        val aggregate = aggregatesBySong[song.song.id]
                        val songSignals = signalsBySong[song.song.id].orEmpty()
                        val validDurations = songSignals.filter { it.durationMs > 0 }
                        val completion =
                            if (validDurations.isEmpty()) {
                                if (aggregate != null && aggregate.totalListenedMs > 0) 0.5f else 0.5f
                            } else {
                                validDurations
                                    .map { (it.listenedMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
                                    .average()
                                    .toFloat()
                            }
                        val earlySkips =
                            songSignals.count { signal ->
                                signal.durationMs > 0 &&
                                    (signal.listenedMs.toFloat() / signal.durationMs) < 0.25f &&
                                    signal.listenedMs < 45_000L
                            }
                        RecSongStats(
                            songId = song.song.id,
                            title = song.song.title,
                            artistIds = song.artists.map { it.id },
                            artistNames = song.artists.map { it.name },
                            albumId = song.song.albumId,
                            albumTitle = song.album?.title ?: song.song.albumName,
                            thumbnailUrl = song.song.thumbnailUrl,
                            libraryAvailable = song.song.blockedAt == null,
                            playCount = aggregate?.playCount ?: 0,
                            totalListenedMs = aggregate?.totalListenedMs ?: 0L,
                            avgCompletion = completion,
                            earlySkipCount = earlySkips,
                            lastPlayedAtMs = aggregate?.lastPlayedAtMs ?: 0L,
                            liked = song.song.liked,
                            playlistAddCount = playlistAddsBySong[song.song.id]?.playlistAddCount ?: 0,
                        )
                    }

                    val artistStats = HashMap<String, RecArtistStats>()
                    songs.forEach { song ->
                        song.artistIds.forEachIndexed { index, artistId ->
                            val name = song.artistNames.getOrNull(index) ?: artistId
                            val existing = artistStats[artistId]
                            val entry =
                                existing ?: RecArtistStats(
                                    artistId = artistId,
                                    artistName = name,
                                    playCount = 0,
                                    totalListenedMs = 0L,
                                    lastPlayedAtMs = 0L,
                                    likedSongCount = 0,
                                )
                            artistStats[artistId] =
                                entry.copy(
                                    playCount = entry.playCount + song.playCount,
                                    totalListenedMs = entry.totalListenedMs + song.totalListenedMs,
                                    lastPlayedAtMs = maxOf(entry.lastPlayedAtMs, song.lastPlayedAtMs),
                                    likedSongCount = entry.likedSongCount + if (song.liked) 1 else 0,
                                )
                        }
                    }

                    val feedback =
                        feedbackEntities.map { entity ->
                            RecFeedback(
                                id = entity.id,
                                targetSongId = entity.targetSongId,
                                targetArtistId = entity.targetArtistId,
                                kind = runCatching { RecFeedbackKind.valueOf(entity.kind) }.getOrDefault(RecFeedbackKind.DISMISS),
                                createdAtMs = entity.createdAtMs,
                            )
                        }

                    RecEngineInput(songs = songs, artists = artistStats.values.toList(), feedback = feedback)
                }

            statsMutex.withLock {
                cachedInput = input
                cachedAtMs = now
            }
            return input
        }
    }
