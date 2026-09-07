/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import javax.inject.Inject

@HiltViewModel
class SpotifyLibraryViewModel
    @Inject
    constructor(
        private val repository: SpotifyLibraryRepository,
    ) : ViewModel() {
        val playlists: StateFlow<List<SpotifyPlaylist>> =
            repository.playlists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

        val isRefreshing: StateFlow<Boolean> =
            repository.isRefreshing.stateIn(viewModelScope, SharingStarted.Lazily, false)

        val errorMessage: StateFlow<String?> =
            repository.errorMessage.stateIn(viewModelScope, SharingStarted.Lazily, null)

        val hiddenPlaylistIds: StateFlow<Set<String>> =
            repository.hiddenPlaylistIds.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

        init {
            viewModelScope.launch(Dispatchers.IO) {
                repository.restoreCachedPlaylists()

                repository.restoreHiddenPlaylistIds()
            }
        }

        fun refreshPlaylists() {
            viewModelScope.launch(Dispatchers.IO) {
                repository.refreshPlaylists()
            }
        }

        fun toggleHiddenPlaylist(playlistId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.toggleHiddenPlaylist(playlistId)
            }
        }

        fun hiddenSpotifyPlaylistsSnapshot(): List<SpotifyPlaylist> = repository.hiddenSpotifyPlaylists()

        suspend fun ensureAccessToken(): String? = repository.ensureAccessToken()
    }
