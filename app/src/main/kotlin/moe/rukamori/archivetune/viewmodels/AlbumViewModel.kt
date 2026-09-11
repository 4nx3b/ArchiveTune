/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.EnableVideoPlaybackKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.extensions.filterVideo
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject

sealed interface AlbumUiState {
    data object Loading : AlbumUiState

    data object Content : AlbumUiState

    data object Empty : AlbumUiState

    data class Error(
        val isNotFound: Boolean = false,
    ) : AlbumUiState
}

private sealed interface FetchState {
    data object Pending : FetchState

    data object Success : FetchState

    data class Failed(
        val isNotFound: Boolean = false,
    ) : FetchState
}

@HiltViewModel
class AlbumViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val database: MusicDatabase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val albumId = savedStateHandle.get<String>("albumId")!!
        val playlistId = MutableStateFlow("")
        val albumWithSongs =
            combine(
                database.albumWithSongs(albumId),
                context.dataStore.data
                    .map { preferences -> preferences[HideVideoKey] ?: false }
                    .distinctUntilChanged(),
            ) { album, hideVideo ->
                album?.copy(
                    songs =
                        if (album.artists.any { it.blockedAt != null }) {
                            emptyList()
                        } else {
                            album.songs
                                .filterBlockedArtists()
                                .filterVideo(hideVideo)
                        },
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
        var otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

        private val _canvasArtwork = MutableStateFlow<CanvasArtwork?>(null)
        val canvasArtwork: StateFlow<CanvasArtwork?> = _canvasArtwork.asStateFlow()

        private val _fetchState = MutableStateFlow<FetchState>(FetchState.Pending)

        val uiState: StateFlow<AlbumUiState> =
            combine(albumWithSongs, _fetchState) { data, fetch ->
                when {
                    data != null && data.songs.isNotEmpty() -> AlbumUiState.Content
                    fetch is FetchState.Pending -> AlbumUiState.Loading
                    fetch is FetchState.Failed && data == null -> AlbumUiState.Error(fetch.isNotFound)
                    fetch is FetchState.Success && data != null && data.songs.isEmpty() -> AlbumUiState.Empty
                    fetch is FetchState.Failed && data != null && data.songs.isNotEmpty() -> AlbumUiState.Content
                    else -> AlbumUiState.Loading
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumUiState.Loading)

        init {
            retry()
            fetchAlbumCanvas(context)
        }

        fun retry() {
            viewModelScope.launch {
                _fetchState.value = FetchState.Pending
                val album = database.album(albumId).first()
                YouTube
                    .album(albumId)
                    .onSuccess {
                        playlistId.value = it.album.playlistId
                        val blockedArtistIds = database.getBlockedArtistIds().toSet()
                        otherVersions.value =
                            it.otherVersions.filter { version ->
                                version.artists.orEmpty().none { artist -> artist.id in blockedArtistIds }
                            }
                        database.withTransaction {
                            if (album == null) {
                                insert(it)
                            } else {
                                update(album.album, it, album.artists)
                            }
                        }
                        _fetchState.value = FetchState.Success
                    }.onFailure {
                        reportException(it)
                        val isNotFound = it.message?.contains("NOT_FOUND") == true
                        if (isNotFound) {
                            database.query {
                                album?.album?.let(::delete)
                            }
                        }
                        _fetchState.value = FetchState.Failed(isNotFound = isNotFound)
                    }
            }
        }

        private fun fetchAlbumCanvas(context: Context) {
            viewModelScope.launch {

                if (!context.dataStore.get(AlbumCanvasEnabledKey, true)) return@launch

                // Canvas is video: the global video-playback switch is a hard
                // gate here too, so no canvas (cached or not) renders anywhere
                // in the app while video playback is disabled.
                if (!context.dataStore.get(EnableVideoPlaybackKey, true)) return@launch

                if (context.isLowDataModeActive()) return@launch

                val loaded = albumWithSongs.first { it != null } ?: return@launch
                val album = loaded.album
                val firstArtist = loaded.artists.firstOrNull()?.name
                val firstSongTitle = loaded.songs.firstOrNull()?.song?.title

                _canvasArtwork.value = resolveAlbumCanvas(album.id, album.title, firstArtist, firstSongTitle)
            }
        }

        private suspend fun resolveAlbumCanvas(
            albumId: String,
            albumTitle: String,
            artist: String?,
            firstSongTitle: String?,
        ): CanvasArtwork? {
            runCatching { AppleMusicProvider.getByAlbumId(albumId) }
                .getOrNull()
                ?.let { return it }

            if (artist.isNullOrBlank()) return null

            val titleCandidates =
                linkedSetOf(albumTitle, stripAlbumEditionSuffixes(albumTitle))
                    .filter { it.isNotBlank() }
            for (title in titleCandidates) {
                runCatching { AppleMusicProvider.getByAlbumArtist(album = title, artist = artist) }
                    .getOrNull()
                    ?.let { return it }
            }

            if (!firstSongTitle.isNullOrBlank()) {
                runCatching {
                    AppleMusicProvider.getBySongArtist(
                        song = firstSongTitle,
                        artist = artist,
                        album = albumTitle,
                    )
                }.getOrNull()?.let { return it }
            }
            return null
        }

        private fun stripAlbumEditionSuffixes(title: String): String =
            title
                .replace(Regex("\\s*[\\(\\[][^)\\]]*[\\)\\]]\\s*$"), "")
                .replace(Regex("\\s*-\\s*(EP|Single|Deluxe|Remaster(ed)?)\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
    }
