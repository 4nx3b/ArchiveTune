/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.appicon.AppIconCatalog
import moe.rukamori.archivetune.appicon.IconPackRuntimeManager
import moe.rukamori.archivetune.appicon.LoadAppIconsUseCase
import moe.rukamori.archivetune.appicon.SelectAppIconUseCase
import javax.inject.Inject

sealed interface IconScreenState {
    data object Loading : IconScreenState

    data class Success(
        val model: IconScreenUiModel,
    ) : IconScreenState

    data object Empty : IconScreenState

    data class Error(
        @StringRes val messageResId: Int,
    ) : IconScreenState
}

sealed interface IconScreenEffect {
    data class OpenUri(
        val uri: String,
    ) : IconScreenEffect
}

@Immutable
data class IconScreenUiModel(
    val icons: AppIconUiCollection,
    val selectedIcon: AppIconUiModel,
    val selectionInProgressId: String?,
    val hasCommunityIcons: Boolean,
    val searchQuery: String,
    val sortOrder: AppIconSortOrder,
    val isSortMenuExpanded: Boolean,
    val packDownload: IconPackDownloadUi = IconPackDownloadUi.NOT_NEEDED,
    val packDownloadPercent: Int = 0,
    val packDownloadIndeterminate: Boolean = false,
    val iconsAreRuntime: Boolean = false,
)

enum class AppIconSortOrder {
    NEW_ADDED,
    ALPHABETICAL,
}

@Immutable
data class AppIconUiModel(
    val id: String,
    val name: String?,
    @StringRes val nameResId: Int?,
    val author: String?,
    val githubAuthorUrl: String?,
    @DrawableRes val previewDrawableResId: Int,
    val previewFilePath: String? = null,
    val isSelected: Boolean,
    val isDefault: Boolean,
)

/** Whether the runtime icon pack needs a download before icons can be listed. */
enum class IconPackDownloadUi {
    /** Bundled build — the pack ships inside the APK. */
    NOT_NEEDED,

    /** Slim build and the pack has not been downloaded yet. */
    NEEDED,

    DOWNLOADING,

    FAILED,
}

@Immutable
data class AppIconUiCollection private constructor(
    private val values: List<AppIconUiModel>,
) {
    val size: Int get() = values.size

    operator fun get(index: Int): AppIconUiModel = values[index]

    companion object {
        fun from(values: List<AppIconUiModel>): AppIconUiCollection = AppIconUiCollection(values.toList())
    }
}

@HiltViewModel
class IconViewModel
    @Inject
    constructor(
        private val loadAppIcons: LoadAppIconsUseCase,
        private val selectAppIcon: SelectAppIconUseCase,
        @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    ) : ViewModel() {
        private val _state = MutableStateFlow<IconScreenState>(IconScreenState.Loading)
        val state: StateFlow<IconScreenState> = _state.asStateFlow()

        private val _effects = MutableSharedFlow<IconScreenEffect>(extraBufferCapacity = 1)
        val effects = _effects.asSharedFlow()

        private var loadJob: Job? = null
        private var selectionJob: Job? = null
        private var downloadJob: Job? = null
        private var catalogIcons: List<AppIconUiModel> = emptyList()
        private var searchQuery: String = ""
        private var sortOrder: AppIconSortOrder = AppIconSortOrder.NEW_ADDED
        private var isSortMenuExpanded: Boolean = false

        init {
            load()
        }

        fun retry() {
            load()
        }

        /** Downloads the runtime icon pack (slim builds) and reloads the catalog. */
        fun downloadPack() {
            if (downloadJob?.isActive == true) return
            if (IconPackRuntimeManager.isBundled()) return
            if (IconPackRuntimeManager.isInstalled(appContext)) {
                load()
                return
            }
            publishSuccess(
                packDownload = IconPackDownloadUi.DOWNLOADING,
                packDownloadPercent = 0,
                packDownloadIndeterminate = true,
            )
            downloadJob =
                viewModelScope.launch {
                    val installed =
                        IconPackRuntimeManager.install(appContext) { fraction ->
                            publishSuccess(
                                packDownload = IconPackDownloadUi.DOWNLOADING,
                                packDownloadPercent = if (fraction >= 0f) (fraction * 100).toInt() else 0,
                                packDownloadIndeterminate = fraction < 0f,
                            )
                        }
                    if (installed) {
                        _state.value = IconScreenState.Loading
                        load()
                    } else {
                        publishSuccess(
                            packDownload = IconPackDownloadUi.FAILED,
                            packDownloadPercent = 0,
                            packDownloadIndeterminate = false,
                        )
                    }
                }
        }

        fun selectIcon(iconId: String) {
            val currentModel = (_state.value as? IconScreenState.Success)?.model ?: return
            if (selectionJob?.isActive == true ||
                currentModel.selectionInProgressId != null ||
                catalogIcons.findById(iconId)?.isSelected != false
            ) {
                return
            }

            publishSuccess(selectionInProgressId = iconId)
            selectionJob =
                viewModelScope.launch {
                    try {
                        _state.value = selectAppIcon(iconId).toScreenState()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        _state.value = IconScreenState.Error(R.string.app_icon_change_failed)
                    }
                }
        }

        fun openAuthorProfile(iconId: String) {
            val icon =
                catalogIcons.findById(iconId) ?: return
            val githubAuthorUrl = icon.githubAuthorUrl?.takeIf(String::isNotBlank) ?: return
            _effects.tryEmit(IconScreenEffect.OpenUri(githubAuthorUrl))
        }

        fun updateSearchQuery(query: String) {
            if (_state.value !is IconScreenState.Success) return
            val sanitizedQuery = query.take(MaxSearchQueryLength)
            if (searchQuery == sanitizedQuery) return
            searchQuery = sanitizedQuery
            publishSuccess()
        }

        fun showSortMenu() {
            if (_state.value !is IconScreenState.Success || isSortMenuExpanded) return
            isSortMenuExpanded = true
            publishSuccess()
        }

        fun dismissSortMenu() {
            if (_state.value !is IconScreenState.Success || !isSortMenuExpanded) return
            isSortMenuExpanded = false
            publishSuccess()
        }

        fun updateSortOrder(order: AppIconSortOrder) {
            if (_state.value !is IconScreenState.Success) return
            sortOrder = order
            isSortMenuExpanded = false
            publishSuccess()
        }

        private fun load() {
            if (loadJob?.isActive == true) return
            _state.value = IconScreenState.Loading
            loadJob =
                viewModelScope.launch {
                    try {
                        _state.value = loadAppIcons().toScreenState()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        _state.value = IconScreenState.Error(R.string.app_icon_load_failed)
                    }
                }
        }

        private fun AppIconCatalog.toScreenState(): IconScreenState {
            if (icons.isEmpty()) return IconScreenState.Empty
            catalogIcons =
                icons.map { icon ->
                    AppIconUiModel(
                        id = icon.id,
                        name = icon.name,
                        nameResId = if (icon.isDefault) R.string.app_icon_default else null,
                        author = icon.author,
                        githubAuthorUrl = icon.githubAuthorUrl,
                        previewDrawableResId = icon.previewDrawableResId,
                        previewFilePath = icon.previewFilePath,
                        isSelected = icon.id == selectedIconId,
                        isDefault = icon.isDefault,
                    )
                }
            return createSuccessState()
        }

        private fun publishSuccess(
            selectionInProgressId: String? =
                (_state.value as? IconScreenState.Success)?.model?.selectionInProgressId,
            packDownload: IconPackDownloadUi? = null,
            packDownloadPercent: Int = 0,
            packDownloadIndeterminate: Boolean = false,
        ) {
            _state.value =
                createSuccessState(
                    selectionInProgressId = selectionInProgressId,
                    packDownload = packDownload,
                    packDownloadPercent = packDownloadPercent,
                    packDownloadIndeterminate = packDownloadIndeterminate,
                )
        }

        private fun createSuccessState(
            selectionInProgressId: String? = null,
            packDownload: IconPackDownloadUi? = null,
            packDownloadPercent: Int = 0,
            packDownloadIndeterminate: Boolean = false,
        ): IconScreenState.Success {
            val selectedIcon =
                catalogIcons.firstOrNull(AppIconUiModel::isSelected)
                    ?: catalogIcons.first()
            val effectivePackDownload =
                packDownload ?: computePackDownloadUi()
            return IconScreenState.Success(
                IconScreenUiModel(
                    icons = AppIconUiCollection.from(visibleIcons()),
                    selectedIcon = selectedIcon,
                    selectionInProgressId = selectionInProgressId,
                    hasCommunityIcons = catalogIcons.any { icon -> !icon.isDefault },
                    searchQuery = searchQuery,
                    sortOrder = sortOrder,
                    isSortMenuExpanded = isSortMenuExpanded,
                    packDownload = effectivePackDownload,
                    packDownloadPercent = packDownloadPercent,
                    packDownloadIndeterminate = packDownloadIndeterminate,
                    iconsAreRuntime = catalogIcons.any { it.previewFilePath != null },
                ),
            )
        }

        private fun computePackDownloadUi(): IconPackDownloadUi =
            when {
                IconPackRuntimeManager.isBundled() -> IconPackDownloadUi.NOT_NEEDED
                IconPackRuntimeManager.isInstalled(appContext) -> IconPackDownloadUi.NOT_NEEDED
                else -> IconPackDownloadUi.NEEDED
            }

        private fun visibleIcons(): List<AppIconUiModel> {
            val defaultIcon = catalogIcons.firstOrNull(AppIconUiModel::isDefault)
            val normalizedQuery = searchQuery.trim()
            val communityIcons =
                catalogIcons
                    .asSequence()
                    .filterNot(AppIconUiModel::isDefault)
                    .filter { icon -> normalizedQuery.isEmpty() || icon.matches(normalizedQuery) }
                    .toList()
                    .let { icons ->
                        when (sortOrder) {
                            AppIconSortOrder.NEW_ADDED -> {
                                icons.asReversed()
                            }

                            AppIconSortOrder.ALPHABETICAL -> {
                                icons.sortedWith(
                                    compareBy<AppIconUiModel, String>(
                                        String.CASE_INSENSITIVE_ORDER,
                                    ) { icon ->
                                        icon.name.orEmpty()
                                    }.thenBy { icon -> icon.id },
                                )
                            }
                        }
                    }
            return buildList(communityIcons.size + 1) {
                defaultIcon?.let(::add)
                addAll(communityIcons)
            }
        }

        private fun AppIconUiModel.matches(query: String): Boolean =
            name?.contains(query, ignoreCase = true) == true ||
                author?.contains(query, ignoreCase = true) == true ||
                id.contains(query, ignoreCase = true)

        private fun List<AppIconUiModel>.findById(iconId: String): AppIconUiModel? = firstOrNull { icon -> icon.id == iconId }

        private companion object {
            const val MaxSearchQueryLength = 100
        }
    }
