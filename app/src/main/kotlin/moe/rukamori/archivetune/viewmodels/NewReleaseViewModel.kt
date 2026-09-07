/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.ReadNewReleaseIdsKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.AlbumReleaseType
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.utils.NewReleaseNotificationManager
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.reportException
import androidx.datastore.preferences.core.edit
import java.time.Year
import javax.inject.Inject

@Immutable
data class NewReleaseContent(
    val albums: List<AlbumItem>,
    val singles: List<AlbumItem>,
    val eps: List<AlbumItem>,
) {
    val totalReleases: Int
        get() = albums.size + singles.size + eps.size

    val isEmpty: Boolean
        get() = totalReleases == 0
}

sealed interface NewReleaseUiState {
    data object Loading : NewReleaseUiState

    data class Success(
        val content: NewReleaseContent,
    ) : NewReleaseUiState

    data object Empty : NewReleaseUiState

    data object Error : NewReleaseUiState
}

@HiltViewModel
class NewReleaseViewModel
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        private val database: MusicDatabase,
        private val loadAiContentFilterPolicy: LoadAiContentFilterPolicyUseCase,
        private val filterAiContent: FilterAiContentUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<NewReleaseUiState>(NewReleaseUiState.Loading)
        val uiState = _uiState.asStateFlow()

        private var readIds: Set<String> = emptySet()

        private var lastCatalogue: List<AlbumItem> = emptyList()

        init {
            load()
            observeReadIds()
        }

        fun retry() {
            load()
        }

        private fun load() {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.value = NewReleaseUiState.Loading
                try {

                    val cacheSnapshot = CachedCatalogue.get()
                    if (cacheSnapshot != null) {
                        lastCatalogue = cacheSnapshot
                        reemitContent()
                        if (CachedCatalogue.isFresh()) return@launch
                    }

                    val albums = YouTube.newReleaseAlbums().getOrThrow()

                    lastCatalogue = albums.distinctBy { it.id }
                    reemitContent()

                    val enriched = enrichCatalogue(albums)

                    val blockedArtistIds = database.getBlockedArtistIds().toSet()
                    val aiContentFilterPolicy = loadAiContentFilterPolicy()
                    val artistRanks: MutableMap<String, Int> = mutableMapOf()
                    val favouriteArtistRanks: MutableMap<String, Int> = mutableMapOf()
                    database.allArtistsByPlayTime().first().let { list ->
                        var favIndex = 0
                        for ((artistsIndex, artist) in list.withIndex()) {
                            artistRanks[artist.id] = artistsIndex
                            if (artist.artist.bookmarkedAt != null) {
                                favouriteArtistRanks[artist.id] = favIndex
                                favIndex++
                            }
                        }
                    }
                    val filtered =
                        filterAiContent(
                            enriched
                                .sortedBy { album ->
                                    val artistIds = album.artists.orEmpty().mapNotNull { it.id }
                                    val firstArtistKey =
                                        artistIds.firstNotNullOfOrNull { artistId ->
                                            favouriteArtistRanks[artistId] ?: artistRanks[artistId]
                                        } ?: Int.MAX_VALUE
                                    firstArtistKey
                                }.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                .filterVideo(context.dataStore.get(HideVideoKey, false))
                                .filterBlockedArtists(blockedArtistIds),
                            aiContentFilterPolicy,
                        ).distinctBy { it.id }

                    lastCatalogue = filtered
                    CachedCatalogue.store(filtered)
                    reemitContent()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    reportException(t)

                    if (lastCatalogue.isEmpty()) {
                        _uiState.value = NewReleaseUiState.Error
                    } else {
                        reemitContent()
                    }
                }
            }
        }

        private suspend fun enrichCatalogue(baseAlbums: List<AlbumItem>): List<AlbumItem> {
            val exploreItems =
                runCatching {
                        YouTube.explore().getOrNull()?.newReleaseAlbums.orEmpty()
                    }.getOrDefault(emptyList())

            val subscribedArtists =
                runCatching {
                        database
                            .artistsBookmarkedByCreateDateAsc()
                            .first()
                            .mapNotNull { entity ->
                                val id = entity.artist.id.takeIf(String::isNotBlank)
                                if (id != null) id to entity.artist.name else null
                            }
                    }.getOrDefault(emptyList())

            val swept =
                if (subscribedArtists.isEmpty()) {
                    emptyList()
                } else {
                    sweepSubscribedArtists(subscribedArtists.take(MAX_SWEEP_ARTISTS))
                }

            return (baseAlbums + exploreItems + swept).distinctBy { it.id }
        }

        private suspend fun sweepSubscribedArtists(artists: List<Pair<String, String>>): List<AlbumItem> {
            val currentYear = Year.now().value
            val semaphore = Semaphore(SWEEP_CONCURRENCY)
            return coroutineScope {
                artists
                    .map { (artistId, artistName) ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                runCatching { YouTube.artist(artistId).getOrNull() }.getOrNull()
                                    ?.let { page ->
                                        Triple(artistId, artistName, page)
                                    }
                            }
                        }
                    }.awaitAll()
            }.mapNotNull { result ->
                if (result == null) return@mapNotNull null
                val (artistId, artistName, page) = result
                page.sections.orEmpty().flatMap { section ->
                    val releaseType =
                        when {
                            section.title.contains("single", ignoreCase = true) -> AlbumReleaseType.SINGLE
                            section.title.contains("ep", ignoreCase = true) -> AlbumReleaseType.EP
                            else -> AlbumReleaseType.ALBUM
                        }
                    section.items
                        .filterIsInstance<AlbumItem>()
                        .map { album ->
                            album.copy(
                                artists =
                                    album.artists?.takeIf { it.isNotEmpty() }
                                        ?: listOf(Artist(name = artistName, id = artistId)),
                                releaseType = releaseType,
                            )
                        }
                }
            }.flatten()
                .filter { album ->

                    val year = album.year
                    year == null || year >= currentYear - 1
                }
        }

        private fun observeReadIds() {
            viewModelScope.launch(Dispatchers.IO) {
                context.dataStore.data
                    .map { it[ReadNewReleaseIdsKey] ?: "" }
                    .collect { raw ->
                        readIds =
                            raw.splitToSequence(',').filter { it.isNotBlank() }.toSet()
                        reemitContent()
                    }
            }
        }

        fun markAllRead() {
            val visible = lastCatalogue.filter { it.id !in readIds }.map { it.id }
            if (visible.isEmpty()) return
            NewReleaseNotificationManager.cancelNotifications(context, visible)
            viewModelScope.launch(Dispatchers.IO) {
                writeReadIds(visible + readIds.toList())
            }
        }

        fun markRead(releaseId: String) {
            if (releaseId in readIds) return
            NewReleaseNotificationManager.cancelNotifications(context, listOf(releaseId))
            viewModelScope.launch(Dispatchers.IO) {
                writeReadIds(listOf(releaseId) + readIds.toList())
            }
        }

        fun markAsRead(ids: Set<String>) {
            if (ids.isEmpty()) return
            val newIds = ids.filter { it.isNotBlank() && it !in readIds }
            if (newIds.isEmpty()) return
            NewReleaseNotificationManager.cancelNotifications(context, newIds)
            viewModelScope.launch(Dispatchers.IO) {
                writeReadIds(newIds + readIds.toList())
            }
        }

        private suspend fun writeReadIds(newestFirst: List<String>) {
            val bounded = newestFirst.filter { it.isNotBlank() }.take(READ_IDS_LIMIT)
            context.dataStore.edit { prefs ->
                prefs[ReadNewReleaseIdsKey] = bounded.joinToString(",")
            }
        }

        private fun reemitContent() {
            if (lastCatalogue.isEmpty()) return
            val visible = lastCatalogue.filter { it.id !in readIds }
            _uiState.value =
                if (visible.isEmpty()) {
                    NewReleaseUiState.Empty
                } else {
                    NewReleaseUiState.Success(visible.toNewReleaseContent())
                }
        }

        private fun List<AlbumItem>.toNewReleaseContent(): NewReleaseContent =
            NewReleaseContent(
                albums = filter { it.releaseType == AlbumReleaseType.ALBUM },
                singles = filter { it.releaseType == AlbumReleaseType.SINGLE },
                eps = filter { it.releaseType == AlbumReleaseType.EP },
            )

        private companion object {

            const val READ_IDS_LIMIT = 500

            const val MAX_SWEEP_ARTISTS = 30

            const val SWEEP_CONCURRENCY = 4
        }

        private object CachedCatalogue {
            private const val TTL_MS = 5 * 60 * 1000L

            @Volatile private var catalogue: List<AlbumItem>? = null
            @Volatile private var storedAtMs: Long = 0

            fun store(value: List<AlbumItem>) {
                catalogue = value
                storedAtMs = System.currentTimeMillis()
            }

            fun get(): List<AlbumItem>? = catalogue?.takeIf { it.isNotEmpty() }

            fun isFresh(): Boolean =
                catalogue?.let { it.isNotEmpty() } == true &&
                    System.currentTimeMillis() - storedAtMs < TTL_MS
        }
    }
