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

        // ── Read-marker state (2026-09-05) ─────────────────────────────────
        // Releases the user marked as read on the page (header button or
        // long-press). The screen hides them; the writes go to
        // ReadNewReleaseIdsKey (kept separate from the notification worker's
        // SeenNewReleaseIdsKey baseline on purpose — see PreferenceKeys.kt).
        private var readIds: Set<String> = emptySet()

        /** The full filtered catalogue of the last successful load. */
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
                    val albums = YouTube.newReleaseAlbums().getOrThrow()

                    // ── Catalogue enrichment (2026-09-05, user report:
                    // "new releases are still capped at max 200 entries") ──
                    // A live probe of FEmusic_new_releases_albums shows the
                    // browse page serves ONE grid (~177-200 items) with no
                    // continuation tokens, so the continuation sweep in core
                    // has nothing to follow — the endpoint itself is the cap.
                    // Two more real sources are merged on top:
                    //  1) the Explore page's "New albums & singles" carousel,
                    //  2) every SUBSCRIBED artist's own album/singles/EP
                    //     sections (their newest releases first) — the exact
                    //     tail the capped grid was missing, and the releases
                    //     the notification feature exists for.
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
                    reemitContent()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    reportException(t)
                    _uiState.value = NewReleaseUiState.Error
                }
            }
        }

        /**
         * Merges the extra sources into the browse-grid catalogue, deduped by
         * release id (the browse grid's items win — they carry the richest
         * releaseType/artist metadata).
         */
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

        /**
         * One artist page per subscribed artist (4 at a time, capped at
         * [MAX_SWEEP_ARTISTS]): their sections' AlbumItems, newest-first as
         * the page orders them, kept to the current/previous year so the
         * "New releases" page stays a feed of NEW releases rather than the
         * artists' whole discographies. The section title carries the
         * release type ("Albums"/"Singles"/"EPs") the artist page parse
         * itself doesn't populate, and the sweeping artist is credited when
         * the parsed item has no artist list (the ArtistPage parse leaves
         * artists null).
         */
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
                    // Keep it NEW: the current or previous year (or unknown —
                    // some parse paths don't populate year; the artist sweep
                    // itself is the "new from an artist you follow" signal).
                    val year = album.year
                    year == null || year >= currentYear - 1
                }
        }

        // ── Read-marker API (2026-09-05) ───────────────────────────────────
        //
        // "in new releases page add a button on the right side of the header
        // in liquid glass. When I click on it all the new album, single, eds
        // notifications should get cleared and a toast should appear that
        // says marked as read. This should also appear when I long press any
        // album, ed or a single songs thumbnail. That should individually
        // clear that selected item and mark it as read." — the screen calls
        // these; the DataStore collector re-emits the page content without
        // the newly-read ids, and the matching system notifications are
        // cancelled.

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

        /** Marks every currently-visible release as read. */
        fun markAllRead() {
            val visible = lastCatalogue.filter { it.id !in readIds }.map { it.id }
            if (visible.isEmpty()) return
            NewReleaseNotificationManager.cancelNotifications(context, visible)
            viewModelScope.launch(Dispatchers.IO) {
                writeReadIds(visible + readIds.toList())
            }
        }

        /** Marks a single release as read (long-press on its thumbnail). */
        fun markRead(releaseId: String) {
            if (releaseId in readIds) return
            NewReleaseNotificationManager.cancelNotifications(context, listOf(releaseId))
            viewModelScope.launch(Dispatchers.IO) {
                writeReadIds(listOf(releaseId) + readIds.toList())
            }
        }

        private suspend fun writeReadIds(newestFirst: List<String>) {
            val bounded = newestFirst.filter { it.isNotBlank() }.take(READ_IDS_LIMIT)
            context.dataStore.edit { prefs ->
                prefs[ReadNewReleaseIdsKey] = bounded.joinToString(",")
            }
        }

        /**
         * Re-emits the UI state from the last catalogue minus the read ids.
         * No-op while the initial load is still in flight (Loading) or the
         * catalogue is empty — the load path owns those transitions.
         */
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
            /** Bounded size of the read-id CSV (newest first, like the seen set). */
            const val READ_IDS_LIMIT = 500

            /** Subscribed artists swept per load — 2 requests' worth each is plenty. */
            const val MAX_SWEEP_ARTISTS = 30

            /** Concurrent artist-page requests during the sweep. */
            const val SWEEP_CONCURRENCY = 4
        }
    }
