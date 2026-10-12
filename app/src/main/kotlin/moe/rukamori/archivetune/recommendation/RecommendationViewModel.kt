/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiCustomModelKey
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.DeeplApiKeyKey
import moe.rukamori.archivetune.constants.DeeplFormalityKey
import moe.rukamori.archivetune.constants.OpenRouterApiKeyKey
import moe.rukamori.archivetune.constants.OpenRouterBaseUrlKey
import moe.rukamori.archivetune.constants.OpenRouterModelKey
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.utils.dataStore

data class RecommendationUiState(
    val recsState: RecsState = RecsState(loading = true),
    val discoveryQuery: String = "",
    val discoveryResults: List<RankedRecommendation> = emptyList(),
    val discoveryLoading: Boolean = false,
    val discoveryError: Boolean = false,
    val usedAiParser: Boolean = false,
)

sealed interface RecommendationAction {
    data class ContextChanged(
        val songId: String?,
        val artistIds: List<String>,
        val albumId: String?,
    ) : RecommendationAction

    data object Refresh : RecommendationAction

    data class Discover(val query: String) : RecommendationAction

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
        @ApplicationContext private val context: Context,
        private val repository: RecommendationRepository,
        private val discoveryParser: AiDiscoveryParser,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(RecommendationUiState())
        val uiState: StateFlow<RecommendationUiState> = _uiState.asStateFlow()

        private val _feedbackEvents = MutableSharedFlow<RecFeedbackKind>(extraBufferCapacity = 8)
        val feedbackEvents: SharedFlow<RecFeedbackKind> = _feedbackEvents.asSharedFlow()

        private var loadJob: Job? = null
        private var discoveryJob: Job? = null
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

                is RecommendationAction.Discover -> runDiscovery(action.query)

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

        private fun runDiscovery(query: String) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                _uiState.value =
                    _uiState.value.copy(
                        discoveryResults = emptyList(),
                        discoveryQuery = "",
                        discoveryLoading = false,
                        discoveryError = false,
                    )
                return
            }
            discoveryJob?.cancel()
            _uiState.value =
                _uiState.value.copy(discoveryQuery = trimmed, discoveryLoading = true, discoveryError = false)
            discoveryJob =
                viewModelScope.launch {
                    val aiConfig = readAiConfig()
                    runCatching { discoveryParser.parse(trimmed, aiConfig) }
                        .fold(
                            onSuccess = { filters ->
                                val usedAi = aiConfig?.canCallApi == true
                                val results = repository.discoverWithFilters(filters)
                                _uiState.value =
                                    _uiState.value.copy(
                                        discoveryResults = results,
                                        discoveryLoading = false,
                                        usedAiParser = usedAi,
                                    )
                            },
                            onFailure = {
                                _uiState.value =
                                    _uiState.value.copy(
                                        discoveryLoading = false,
                                        discoveryError = true,
                                    )
                            },
                        )
                }
        }

        private suspend fun readAiConfig(): AiServiceConfig? {
            return runCatching {
                val prefs = context.dataStore.data.first()
                val provider = prefs[AiProviderKey].toEnum(AiProvider.NONE)
                if (provider == AiProvider.NONE) return@runCatching null
                var apiKey = prefs[AiApiKeyKey].orEmpty()
                var customEndpoint = prefs[AiCustomEndpointKey].orEmpty()
                var model =
                    if (provider == AiProvider.CUSTOM) {
                        prefs[AiCustomModelKey].orEmpty()
                    } else {
                        prefs[AiSelectedModelKey].orEmpty()
                    }
                var deepLFormality = "default"
                when (provider) {
                    AiProvider.DEEPL -> {
                        apiKey = apiKey.ifBlank { prefs[DeeplApiKeyKey].orEmpty() }
                        deepLFormality = prefs[DeeplFormalityKey].orEmpty().ifBlank { "default" }
                    }

                    AiProvider.OPENROUTER -> {
                        apiKey = apiKey.ifBlank { prefs[OpenRouterApiKeyKey].orEmpty() }
                        customEndpoint = customEndpoint.ifBlank { prefs[OpenRouterBaseUrlKey].orEmpty() }
                        model = model.ifBlank { prefs[OpenRouterModelKey].orEmpty() }
                    }

                    else -> {}
                }
                AiServiceConfig(
                    provider = provider,
                    apiKey = apiKey,
                    customEndpoint = customEndpoint,
                    model = model,
                    deepLFormality = deepLFormality,
                )
            }.getOrNull()
        }
    }
