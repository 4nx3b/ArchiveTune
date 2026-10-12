/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecommendationUiState(
    val recsState: RecsState = RecsState(loading = true),
)

sealed interface RecommendationAction {
    data class ContextChanged(
        val songId: String?,
        val artistIds: List<String>,
        val albumId: String?,
    ) : RecommendationAction

    data object Refresh : RecommendationAction

    data class Feedback(
        val songId: String?,
        val artistId: String?,
        val kind: RecFeedbackKind,
    ) : RecommendationAction

    data object ClearHistory : RecommendationAction
}

@HiltViewModel
class RecommendationViewModel
    @Inject
    constructor(
        private val repository: RecommendationRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(RecommendationUiState())
        val uiState: StateFlow<RecommendationUiState> = _uiState.asStateFlow()

        private val _feedbackEvents = MutableSharedFlow<RecFeedbackKind>(extraBufferCapacity = 8)
        val feedbackEvents: SharedFlow<RecFeedbackKind> = _feedbackEvents.asSharedFlow()

        private var loadJob: Job? = null
        private var currentContext = RecContext()

        val enabled: StateFlow<Boolean> =
            repository.recommendationsEnabled
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)

        init {
            refresh()
        }

        fun onAction(action: RecommendationAction) {
            when (action) {
                is RecommendationAction.ContextChanged -> {
                    val next =
                        RecContext(
                            currentSongId = action.songId,
                            currentArtistIds = action.artistIds,
                            currentAlbumId = action.albumId,
                        )
                    if (next != currentContext) {
                        currentContext = next
                        refresh()
                    }
                }

                RecommendationAction.Refresh -> refresh()

                is RecommendationAction.Feedback -> {
                    viewModelScope.launch {
                        repository.recordFeedback(action.songId, action.artistId, action.kind)
                        _feedbackEvents.tryEmit(action.kind)
                        refresh()
                    }
                }

                RecommendationAction.ClearHistory -> {
                    viewModelScope.launch {
                        repository.clearAllSignals()
                        refresh()
                    }
                }
            }
        }

        private fun refresh() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    runCatching { repository.buildState(currentContext) }
                        .onSuccess { state ->
                            _uiState.value = _uiState.value.copy(recsState = state)
                        }
                        .onFailure {
                            _uiState.value =
                                _uiState.value.copy(
                                    recsState = RecsState(loading = false, error = true),
                                )
                        }
                }
        }
    }
