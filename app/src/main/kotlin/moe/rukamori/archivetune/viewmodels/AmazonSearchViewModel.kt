/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.amazon.AmazonMusicCatalog
import moe.rukamori.archivetune.applemusic.AppleMusicSearchItem
import moe.rukamori.archivetune.ui.screens.search.OnlineSearchResultArgument
import moe.rukamori.archivetune.ui.screens.search.decodeOnlineSearchQuery
import javax.inject.Inject

@Immutable
data class AmazonSearchUiState(
    val items: List<AppleMusicSearchItem.Track> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AmazonSearchViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val query: String =
            decodeOnlineSearchQuery(
                savedStateHandle.get<String>(OnlineSearchResultArgument).orEmpty(),
            )

        private val _uiState = MutableStateFlow(AmazonSearchUiState())
        val uiState: StateFlow<AmazonSearchUiState> = _uiState.asStateFlow()

        init {
            loadPage(reset = true)
        }

        fun reload() {
            loadPage(reset = true)
        }

        fun loadMore() {
        }

        private fun loadPage(reset: Boolean) {
            if (query.isBlank()) return
            viewModelScope.launch {
                if (reset) {
                    _uiState.value = AmazonSearchUiState(isLoading = true)
                }

                try {
                    val page =
                        AmazonMusicCatalog.searchPage(
                            query = query,
                            limit = PAGE_SIZE,
                            offset = 0,
                        )
                    _uiState.value =
                        AmazonSearchUiState(
                            items = page.items,
                            isLoading = false,
                            hasMore = false,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            hasMore = false,
                            errorMessage = error.message,
                        )
                    }
                }
            }
        }

        private companion object {
            const val PAGE_SIZE = 20
        }
    }
