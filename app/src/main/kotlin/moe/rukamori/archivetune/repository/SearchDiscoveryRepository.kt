/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.ChartsPage
import moe.rukamori.archivetune.innertube.pages.MoodAndGenres
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SearchDiscoveryData(
    val moodAndGenres: List<MoodAndGenres.Item>,
    val newReleaseAlbums: List<AlbumItem>,
    val chartSections: List<ChartsPage.ChartSection>,
    val suggestedSongs: List<SongItem>,
    val searchedAlbums: List<AlbumItem>,
    val suggestedArtists: List<ArtistItem>,
) {
    val isEmpty: Boolean
        get() =
            moodAndGenres.isEmpty() &&
                newReleaseAlbums.isEmpty() &&
                chartSections.isEmpty() &&
                suggestedSongs.isEmpty() &&
                searchedAlbums.isEmpty() &&
                suggestedArtists.isEmpty()
}

@Singleton
class SearchDiscoveryRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {

        private data class CachedSnapshot(
            val data: SearchDiscoveryData,
            val expiresAtMs: Long,
        )

        private val cache = ConcurrentHashMap<String, CachedSnapshot>(1)

        private val loadMutex = Mutex()
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun warmUp() {
            if (cache[CacheKey]?.expiresAtMs ?: 0L > System.currentTimeMillis()) return
            refreshScope.launch {
                runCatching { loadDiscovery(forceRefresh = false) }
            }
        }

        suspend fun loadDiscovery(forceRefresh: Boolean = false): Result<SearchDiscoveryData> =
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                if (!forceRefresh) {
                    cache[CacheKey]?.let { snapshot ->
                        if (snapshot.expiresAtMs > now) {
                            return@withContext Result.success(snapshot.data)
                        }

                        if (snapshot.expiresAtMs > now - STALE_GRACE_MS) {
                            refreshScope.launch {
                                runCatching { loadDiscovery(forceRefresh = true) }
                            }
                            return@withContext Result.success(snapshot.data)
                        }
                    }
                }

                loadMutex.withLock {

                    if (!forceRefresh) {
                        cache[CacheKey]?.let { snapshot ->
                            if (snapshot.expiresAtMs > System.currentTimeMillis()) {
                                return@withContext Result.success(snapshot.data)
                            }
                        }
                    }

                    try {
                        val data = loadDiscoveryFromNetwork()
                        cache[CacheKey] =
                            CachedSnapshot(
                                data = data,
                                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                            )
                        Result.success(data)
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable

                        cache[CacheKey]?.let { snapshot ->
                            if (snapshot.expiresAtMs > System.currentTimeMillis() - STALE_GRACE_MS) {
                                return@withContext Result.success(snapshot.data)
                            }
                        }
                        Result.failure(throwable)
                    }
                }
            }

        private suspend fun loadDiscoveryFromNetwork(): SearchDiscoveryData =
            coroutineScope {

                val explorePageDeferred =
                    async {
                        runCatching { YouTube.explore().getOrThrow() }.getOrNull()
                    }
                val chartsPageDeferred =
                    async {
                        runCatching { YouTube.getChartsPage().getOrThrow() }.getOrNull()
                    }
                val suggestedSongsDeferred = async { loadSuggestedSongs() }
                val searchedAlbumsDeferred =
                    async {
                        searchItems<AlbumItem>(
                            query = TopAlbumsQuery,
                            filter = YouTube.SearchFilter.FILTER_ALBUM,
                        )
                    }
                val suggestedArtistsDeferred = async { loadSuggestedArtists() }

                val explorePage = explorePageDeferred.await()
                val chartsPage = chartsPageDeferred.await()

                SearchDiscoveryData(
                    moodAndGenres = explorePage?.moodAndGenres.orEmpty(),
                    newReleaseAlbums = explorePage?.newReleaseAlbums.orEmpty(),
                    chartSections = chartsPage?.sections.orEmpty(),
                    suggestedSongs = suggestedSongsDeferred.await(),
                    searchedAlbums = searchedAlbumsDeferred.await(),
                    suggestedArtists = suggestedArtistsDeferred.await(),
                )
            }

        private suspend inline fun <reified T> searchItems(
            query: String,
            filter: YouTube.SearchFilter,
        ): List<T> =
            try {
                YouTube
                    .search(
                        query = query,
                        filter = filter,
                        useAccountContext = false,
                    ).getOrThrow()
                    .items
                    .filterIsInstance<T>()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                emptyList()
            }

        private suspend fun loadSuggestedSongs(): List<SongItem> =
            coroutineScope {
                val seedSongs =
                    database
                        .mostPlayedSongs(
                            fromTimeStamp = AllHistoryTimestamp,
                            limit = MaxHistoryLookupItems,
                        ).first()
                        .filterNot { song -> song.song.isLocal }
                        .take(MaxSuggestionSeedItems)
                val seedSongIds = seedSongs.mapTo(HashSet()) { song -> song.id }

                val blockedSongIds = database.getBlockedSongIds().toHashSet()

                seedSongs
                    .map { song ->
                        async {
                            loadRelatedSongs(song)
                                .ifEmpty { searchRelatedSongs(song) }
                        }
                    }.awaitAll()
                    .flatten()
                    .filterNot { song -> song.id in seedSongIds }
                    .filterNot { song -> song.id in blockedSongIds }
                    .distinctBy { song -> song.id }
                    .take(MaxSuggestedItems)
            }

        private suspend fun loadRelatedSongs(song: Song): List<SongItem> =
            try {
                val nextResult = YouTube.next(WatchEndpoint(videoId = song.id)).getOrThrow()
                val relatedSongs =
                    nextResult
                        .relatedEndpoint
                        ?.let { endpoint -> YouTube.related(endpoint).getOrNull()?.songs }
                        .orEmpty()
                (relatedSongs + nextResult.items).distinctBy { item -> item.id }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                emptyList()
            }

        private suspend fun searchRelatedSongs(song: Song): List<SongItem> =
            searchItems(
                query =
                    buildString {
                        append(song.title)
                        song.artists
                            .firstOrNull()
                            ?.name
                            ?.takeIf(String::isNotBlank)
                            ?.let { artistName ->
                                append(' ')
                                append(artistName)
                            }
                    },
                filter = YouTube.SearchFilter.FILTER_SONG,
            )

        private suspend fun loadSuggestedArtists(): List<ArtistItem> =
            coroutineScope {
                val seedArtists =
                    database
                        .mostPlayedArtists(
                            fromTimeStamp = AllHistoryTimestamp,
                            limit = MaxHistoryLookupItems,
                        ).first()
                        .filter { artist -> artist.artist.isYouTubeArtist }
                        .take(MaxSuggestionSeedItems)
                val seedArtistIds = seedArtists.mapTo(HashSet()) { artist -> artist.id }

                seedArtists
                    .map { artist ->
                        async {
                            loadRelatedArtists(artist)
                                .ifEmpty { searchRelatedArtists(artist) }
                        }
                    }.awaitAll()
                    .flatten()
                    .filterNot { artist -> artist.id in seedArtistIds }
                    .distinctBy { artist -> artist.id }
                    .take(MaxSuggestedItems)
            }

        private suspend fun loadRelatedArtists(artist: Artist): List<ArtistItem> =
            try {
                YouTube
                    .artist(artist.id)
                    .getOrThrow()
                    .sections
                    .flatMap { section -> section.items }
                    .filterIsInstance<ArtistItem>()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                emptyList()
            }

        private suspend fun searchRelatedArtists(artist: Artist): List<ArtistItem> =
            searchItems(
                query = artist.title,
                filter = YouTube.SearchFilter.FILTER_ARTIST,
            )

        private companion object {
            const val AllHistoryTimestamp = 0L
            const val MaxHistoryLookupItems = 36

            const val MaxSuggestionSeedItems = 3
            const val MaxSuggestedItems = 12
            const val TopAlbumsQuery = "top albums"

            const val CacheKey = "default"

            const val CACHE_TTL_MS = 5L * 60 * 1000

            const val STALE_GRACE_MS = 30L * 60 * 1000
        }
    }
