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

        // Per user report (2026-08-29): "If I hide a Spotify playlist it should be
        // available in the hidden playlists section of the account page." The set
        // is sourced from the @Singleton repository's StateFlow so it's shared
        // across screens (LibrarySpotifyPlaylistsScreen + HiddenPlaylistsScreen)
        // and survives navigation/process death (backed by DataStore).
        val hiddenPlaylistIds: StateFlow<Set<String>> =
            repository.hiddenPlaylistIds.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

        init {
            viewModelScope.launch(Dispatchers.IO) {
                repository.restoreCachedPlaylists()
                // Restore the persisted hidden-id set so the Library page's
                // `visiblePlaylists` filter hides the right playlists on cold
                // launch AND the account-page "Hidden playlists" section shows
                // them from the very first frame.
                repository.restoreHiddenPlaylistIds()
            }
        }

        fun refreshPlaylists() {
            viewModelScope.launch(Dispatchers.IO) {
                repository.refreshPlaylists()
            }
        }

        /**
         * Toggles a Spotify playlist's hidden state. Called from the per-row
         * "more" menu's "Hide playlist" action on [LibrarySpotifyPlaylistsScreen]
         * AND from the "Unhide" button on the account-page Hidden playlists
         * section (so both screens share the same persistence path).
         */
        fun toggleHiddenPlaylist(playlistId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.toggleHiddenPlaylist(playlistId)
            }
        }

        /**
         * Returns the subset of currently-loaded Spotify playlists whose id is in
         * [hiddenPlaylistIds]. Used by the account-page "Hidden playlists" section
         * to render hidden Spotify playlists with metadata (name, cover, track
         * count) that the persisted id-set alone doesn't carry.
         */
        fun hiddenSpotifyPlaylistsSnapshot(): List<SpotifyPlaylist> = repository.hiddenSpotifyPlaylists()

        /**
         * Ensures the Spotify access token is set on the [Spotify] singleton before
         * a queue / resolve call that uses [Spotify.playlistTracks] directly
         * (without going through the repository's `spotifyCallWithTokenRetry`
         * path). Per user report (2026-08-29): "Play, Shuffle, Play next, Add to
         * queue button in Spotify's playlists overflow menu doesn't do anything"
         * — the [SpotifyPlaylistQueue] fetches tracks via
         * `Spotify.playlistTracks(...)` directly, which requires
         * `Spotify.accessToken` to be set, but the LibrarySpotifyPlaylistsScreen
         * doesn't auto-refresh the token on screen open (it only restores the
         * cached playlist list from DataStore). Calling this first ensures the
         * token is minted/refreshed before the queue tries to use it.
         */
        suspend fun ensureAccessToken(): String? = repository.ensureAccessToken()
    }
