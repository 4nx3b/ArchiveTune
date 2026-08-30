/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.AiContentFilterRefreshResult
import moe.rukamori.archivetune.aicontentfilter.ObserveAiContentFilterUseCase
import moe.rukamori.archivetune.aicontentfilter.RefreshAiContentFilterUseCase
import moe.rukamori.archivetune.aicontentfilter.UpdateAiContentFilterSettingsUseCase
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.lyrics.LyricsProviderTestResult
import javax.inject.Inject

// PaxsenixStatsState and PaxsenixEndpointCheckState removed (2026-08-30):
// the PaxsenixLyrics backend was deleted along with the BiniLyrics provider
// that was its only consumer. The fetchPaxsenixStats / checkPaxsenixEndpoints
// functions and their state holders have been removed from this ViewModel, and
// the PaxsenixStatsDialog / PaxsenixStatusBar / PaxsenixProviderRow / PaxsenixServerStatus
// UI has been removed from LyricsSettings.kt.

/**
 * State for the "Lyrics test" sweep in the Lyrics Providers settings page.
 *
 * - [Loading]: the sweep is running, the dialog shows a spinner per provider.
 * - [Done]: the sweep finished (every provider has either returned or
 *   timed out); the dialog shows the per-provider outcome list.
 *
 * Re-tapping the entry while the sweep is running restarts it rather than
 * queueing a second sweep.
 */
sealed interface LyricsTestState {
    data object Idle : LyricsTestState

    data object Loading : LyricsTestState

    data class Done(
        val results: List<LyricsProviderTestResult>,
    ) : LyricsTestState
}

sealed interface AiContentFilterSettingsState {
    data object Loading : AiContentFilterSettingsState

    data class Success(
        val model: AiContentFilterSettingsUiModel,
    ) : AiContentFilterSettingsState

    data object Empty : AiContentFilterSettingsState

    data class Error(
        val messageResId: Int,
    ) : AiContentFilterSettingsState
}

@Immutable
data class AiContentFilterSettingsUiModel(
    val enabled: Boolean,
    val includeModerateConfidence: Boolean,
    val blocklistCount: Int,
    val warnlistCount: Int,
    val refreshing: Boolean,
)

@Immutable
sealed interface AiContentFilterSettingsEffect {
    data class ShowMessage(
        val messageResId: Int,
    ) : AiContentFilterSettingsEffect

    data class OpenUrl(
        val url: String,
    ) : AiContentFilterSettingsEffect
}

@HiltViewModel
class ContentSettingsViewModel
    @Inject
    constructor(
        private val lyricsHelper: LyricsHelper,
        private val database: MusicDatabase,
        observeAiContentFilter: ObserveAiContentFilterUseCase,
        private val updateAiContentFilterSettings: UpdateAiContentFilterSettingsUseCase,
        private val refreshAiContentFilterLists: RefreshAiContentFilterUseCase,
    ) : ViewModel() {
        private val _lyricsTestState = MutableStateFlow<LyricsTestState>(LyricsTestState.Idle)
        val lyricsTestState = _lyricsTestState.asStateFlow()
        private var lyricsTestJob: Job? = null
        private val refreshingAiContentFilter = MutableStateFlow(false)
        private val _aiContentFilterEffects = MutableSharedFlow<AiContentFilterSettingsEffect>(extraBufferCapacity = 1)
        val aiContentFilterEffects = _aiContentFilterEffects.asSharedFlow()
        private var aiContentFilterRefreshJob: Job? = null
        private var aiContentFilterEnabledJob: Job? = null
        private var aiContentFilterModerateJob: Job? = null

        val aiContentFilterState: StateFlow<AiContentFilterSettingsState> =
            combine(
                observeAiContentFilter(),
                refreshingAiContentFilter,
            ) { (settings, status), refreshing ->
                AiContentFilterSettingsUiModel(
                    enabled = settings.enabled,
                    includeModerateConfidence = settings.includeModerateConfidence,
                    blocklistCount = status.blocklistCount,
                    warnlistCount = status.warnlistCount,
                    refreshing = refreshing,
                )
            }.map<AiContentFilterSettingsUiModel, AiContentFilterSettingsState> { model ->
                AiContentFilterSettingsState.Success(model)
            }.catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(AiContentFilterSettingsState.Error(R.string.error_unknown))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AiContentFilterSettingsState.Loading,
            )

        init {
            startAiContentFilterRefresh(force = false, showSuccess = false)
        }

        /**
         * Runs the "Lyrics test" sweep — calls every enabled provider with a fixed
         * well-known test case and reports per-provider outcomes. Re-tapping while a
         * sweep is running restarts it rather than queueing a second sweep. Used by the
         * "Lyrics test" entry in the Lyrics Providers settings page.
         */
        fun runLyricsTest() {
            lyricsTestJob?.cancel()
            _lyricsTestState.value = LyricsTestState.Loading
            lyricsTestJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val results = lyricsHelper.testAllProviders()
                    _lyricsTestState.value = LyricsTestState.Done(results)
                }
        }

        fun clearLyricsCache() {
            viewModelScope.launch(Dispatchers.IO) {
                lyricsHelper.clearCache()
                database.query {
                    clearAllLyrics()
                }
            }
        }

        fun setAiContentFilterEnabled(enabled: Boolean) {
            if (!enabled) aiContentFilterRefreshJob?.cancel()
            aiContentFilterEnabledJob?.cancel()
            aiContentFilterEnabledJob =
                viewModelScope.launch(Dispatchers.IO) {
                    updateAiContentFilterSettings.setEnabled(enabled)
                    if (enabled) {
                        startAiContentFilterRefresh(force = false, showSuccess = false)
                    }
                }
        }

        fun setAiContentFilterIncludeModerate(enabled: Boolean) {
            aiContentFilterModerateJob?.cancel()
            aiContentFilterModerateJob =
                viewModelScope.launch(Dispatchers.IO) {
                    updateAiContentFilterSettings.setIncludeModerateConfidence(enabled)
                }
        }

        fun refreshAiContentFilter() {
            startAiContentFilterRefresh(force = true, showSuccess = true)
        }

        fun openAiContentFilterSource() {
            _aiContentFilterEffects.tryEmit(AiContentFilterSettingsEffect.OpenUrl(AISLIST_URL))
        }

        private fun startAiContentFilterRefresh(
            force: Boolean,
            showSuccess: Boolean,
        ) {
            aiContentFilterRefreshJob?.cancel()
            aiContentFilterRefreshJob =
                viewModelScope.launch(Dispatchers.IO) {
                    refreshingAiContentFilter.value = true
                    try {
                        when (refreshAiContentFilterLists(force)) {
                            is AiContentFilterRefreshResult.Success -> {
                                if (showSuccess) {
                                    _aiContentFilterEffects.emit(
                                        AiContentFilterSettingsEffect.ShowMessage(R.string.ai_content_filter_updated),
                                    )
                                }
                            }

                            AiContentFilterRefreshResult.Unavailable -> {
                                _aiContentFilterEffects.emit(
                                    AiContentFilterSettingsEffect.ShowMessage(R.string.ai_content_filter_update_failed),
                                )
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        _aiContentFilterEffects.emit(
                            AiContentFilterSettingsEffect.ShowMessage(R.string.ai_content_filter_update_failed),
                        )
                    } finally {
                        refreshingAiContentFilter.value = false
                    }
                }
        }

        private companion object {
            const val AISLIST_URL = "https://aisloplist.com"
        }
    }
