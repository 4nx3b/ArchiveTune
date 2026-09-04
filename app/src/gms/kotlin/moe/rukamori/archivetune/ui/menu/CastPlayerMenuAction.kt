/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.cast.CastScreenState
import moe.rukamori.archivetune.cast.CastUiState
import moe.rukamori.archivetune.cast.CastViewModel
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.PlatformBackdrop

/**
 * The player's Cast action ("Cast" row / AirPlay-style output button).
 *
 * @param renderSheet Whether THIS instance also renders the route-picker
 *   sheet. The PlayerMenu instance keeps the default `true` — it owns the
 *   Material ModalBottomSheet path used when Cast is triggered from the
 *   more-menu (any player style, including the collapsed mini player).
 *   AppleMusicPlayer passes `false`: its player-level instance renders the
 *   real-time liquid-glass picker via [CastRoutePickerGlassOverlay] instead
 *   (user request 2026-09-04: "use the same kind of real time liquid glass
 *   blur for cast popup"), and rendering both would stack two popups over
 *   the shared CastViewModel state.
 */
@Composable
fun rememberCastPlayerMenuAction(renderSheet: Boolean = true): NewAction? {
    val context = LocalContext.current
    val viewModel: CastViewModel = viewModel()
    val routePickerViewModel: CastRoutePickerViewModel = viewModel()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val isRoutePickerVisible by viewModel.isRoutePickerVisible.collectAsStateWithLifecycle()
    val routePickerState by routePickerViewModel.screenState.collectAsStateWithLifecycle()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.showRoutePicker()
            }
        }
    val castState = (screenState as? CastScreenState.Success)?.uiState ?: return null
    if (!castState.isAvailable) return null

    if (renderSheet && isRoutePickerVisible) {
        CastRoutePickerBottomSheet(
            castState = castState,
            screenState = routePickerState,
            onDismissRequest = viewModel::hideRoutePicker,
            onStartDiscovery = routePickerViewModel::startDiscovery,
            onStopDiscovery = routePickerViewModel::stopDiscovery,
            onRouteClick =
                remember(routePickerViewModel, viewModel) {
                    { routeId: String ->
                        if (routePickerViewModel.selectRoute(routeId)) {
                            viewModel.hideRoutePicker()
                        }
                    }
                },
            onDisconnect =
                remember(viewModel) {
                    {
                        viewModel.disconnect()
                        viewModel.hideRoutePicker()
                    }
                },
        )
    }

    val text = stringResource(R.string.cast)
    val castIconRes = if (castState.isConnected) R.drawable.cast_connected else R.drawable.cast
    val onCastClick =
        remember(context, permissionLauncher, viewModel) {
            {
                val permission = castDiscoveryPermission()
                if (
                    permission == null ||
                    ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.showRoutePicker()
                } else {
                    permissionLauncher.launch(permission)
                }
            }
        }
    return NewAction(
        icon = {
            Icon(
                painter = painterResource(castIconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        text = text,
        onClick = onCastClick,
    )
}

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private fun castDiscoveryPermission(): String? =
    when {
        Build.VERSION.SDK_INT >= 37 -> ACCESS_LOCAL_NETWORK_PERMISSION
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }

@Composable
private fun CastRoutePickerBottomSheet(
    castState: CastUiState,
    screenState: CastRoutePickerScreenState,
    onDismissRequest: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onRouteClick: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    DisposableEffect(onStartDiscovery, onStopDiscovery) {
        onStartDiscovery()
        onDispose(onStopDiscovery)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CastRoutePickerHeader(onDismissRequest = onDismissRequest)

                CastRoutePickerStatus(
                    castState = castState,
                    screenState = screenState,
                    onDisconnect = onDisconnect,
                )

                CastRoutePickerContent(
                    screenState = screenState,
                    onRouteClick = onRouteClick,
                )
            }
        }
    }
}

@Composable
private fun CastRoutePickerHeader(onDismissRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                painter = painterResource(R.drawable.cast),
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(12.dp)
                        .size(28.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.cast_devices),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.cast_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalIconButton(onClick = onDismissRequest) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = stringResource(R.string.close_dialog),
            )
        }
    }
}

@Composable
private fun CastRoutePickerStatus(
    castState: CastUiState,
    screenState: CastRoutePickerScreenState,
    onDisconnect: () -> Unit,
) {
    val connectedDeviceName = castState.device?.name

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (castState.isConnected) R.drawable.cast_connected else R.drawable.cast),
                contentDescription = null,
                tint =
                    if (castState.isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text =
                        if (castState.isConnected && connectedDeviceName != null) {
                            stringResource(R.string.cast_connected_to, connectedDeviceName)
                        } else {
                            screenState.statusText()
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (castState.isConnected) {
                TextButton(onClick = onDisconnect) {
                    Text(text = stringResource(R.string.cast_disconnect))
                }
            }
        }
    }
}

@Composable
private fun CastRoutePickerContent(
    screenState: CastRoutePickerScreenState,
    onRouteClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.cast_available_devices),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        when (screenState) {
            CastRoutePickerScreenState.Loading -> CastRoutePickerLoading()
            CastRoutePickerScreenState.Empty -> CastRoutePickerEmpty()
            is CastRoutePickerScreenState.Error -> CastRoutePickerError(messageResId = screenState.messageResId)
            is CastRoutePickerScreenState.Success -> CastRoutePickerRouteList(screenState.routes, onRouteClick)
        }
    }
}

@Composable
private fun CastRoutePickerRouteList(
    routes: List<CastRouteUiModel>,
    onRouteClick: (String) -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(
            items = routes,
            key = { _, route -> route.id },
            contentType = { _, _ -> "cast_route_device" },
        ) { index, route ->
            val routeClick = remember(route.id, onRouteClick) { { onRouteClick(route.id) } }
            CastRouteRow(
                route = route,
                index = index,
                count = routes.size,
                onClick = routeClick,
            )
        }
    }
}

@Composable
private fun CastRoutePickerError(messageResId: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.cast),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(messageResId),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CastRoutePickerLoading() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 156.dp)
                    .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LoadingIndicator(modifier = Modifier.size(44.dp))
            Text(
                text = stringResource(R.string.cast_searching_devices),
                modifier = Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.cast_no_devices_desc),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CastRoutePickerEmpty() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 156.dp)
                    .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.cast),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = stringResource(R.string.cast_no_devices),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.cast_no_devices_desc),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CastRouteRow(
    route: CastRouteUiModel,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        selected = route.selected,
        onClick = onClick,
        enabled = route.enabled && !route.connecting,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors =
            ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color =
                    if (route.selected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (route.selected) {
                        MaterialTheme.colorScheme.onSecondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.cast),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(10.dp)
                            .size(22.dp),
                )
            }
        },
        trailingContent = {
            if (route.connecting) {
                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (route.selected) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        supportingContent = {
            Text(
                text = route.supportingText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(
            text = route.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CastRouteUiModel.supportingText(): String =
    when {
        selected -> stringResource(R.string.together_connected)
        connecting -> stringResource(R.string.connecting)
        description != null -> description
        else -> stringResource(R.string.cast_available_device)
    }

@Composable
private fun CastRoutePickerScreenState.statusText(): String =
    when (this) {
        CastRoutePickerScreenState.Loading -> stringResource(R.string.cast_searching_devices)
        CastRoutePickerScreenState.Empty -> stringResource(R.string.cast_no_devices)
        is CastRoutePickerScreenState.Error -> stringResource(messageResId)
        is CastRoutePickerScreenState.Success -> stringResource(R.string.cast_available_device_count, routes.size)
    }

/**
 * ── Real-time liquid-glass Cast route picker (2026-09-04) ───────────────────
 *
 * User request: "use the same kind of real time liquid glass blur for cast
 * popup".
 *
 * Rendered from the Apple Music-style player, next to the anchored lyrics
 * overflow popup (a SIBLING of the player's layer-capturing inner Box), so
 * the glass samples [backdrop] — the SAME live kyant layer the lyrics popup
 * samples. Because that layer records the player's drifting artwork every
 * frame, the frost is genuinely real-time, exactly like the lyrics popup —
 * a ModalBottomSheet could never do this (its dialog window can't sample
 * another window's layer).
 *
 * Only shown while [eligible] — the player sheet is expanded AND no overflow
 * menu is open (the menu would sit on top of this inline overlay, and the
 * route picker would be unreachable behind its scrim). Every other path —
 * the "Cast" row inside the player's more-menu on ANY player style, or the
 * collapsed mini player — keeps the existing ModalBottomSheet rendered by
 * [rememberCastPlayerMenuAction]'s PlayerMenu instance, unchanged.
 *
 * The picker is driven by the SAME shared CastViewModel /
 * CastRoutePickerViewModel instances (activity-scoped) the menu path uses,
 * so discovery, route selection, connection and disconnect all run the exact
 * existing code paths — only the container's look changed.
 */
@Composable
fun CastRoutePickerGlassOverlay(
    backdrop: PlatformBackdrop?,
    eligible: Boolean,
) {
    // No glass without a backdrop (pre-Android-12 / Liquid Glass off) — the
    // ModalBottomSheet path covers those cases instead.
    if (backdrop == null) return
    val viewModel: CastViewModel = viewModel()
    val routePickerViewModel: CastRoutePickerViewModel = viewModel()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val isRoutePickerVisible by viewModel.isRoutePickerVisible.collectAsStateWithLifecycle()
    val routePickerState by routePickerViewModel.screenState.collectAsStateWithLifecycle()
    val castState = (screenState as? CastScreenState.Success)?.uiState ?: return

    CastRoutePickerGlassSheet(
        visible = isRoutePickerVisible && eligible,
        backdrop = backdrop,
        castState = castState,
        screenState = routePickerState,
        onDismissRequest = viewModel::hideRoutePicker,
        onStartDiscovery = routePickerViewModel::startDiscovery,
        onStopDiscovery = routePickerViewModel::stopDiscovery,
        onRouteClick =
            remember(routePickerViewModel, viewModel) {
                { routeId: String ->
                    if (routePickerViewModel.selectRoute(routeId)) {
                        viewModel.hideRoutePicker()
                    }
                }
            },
        onDisconnect =
            remember(viewModel) {
                {
                    viewModel.disconnect()
                    viewModel.hideRoutePicker()
                }
            },
    )
}

private val CastGlassSheetShape = RoundedCornerShape(28.dp)

/**
 * The floating glass card + scrim container for the Cast route picker — the
 * same material as the song-overflow popup ([BottomSheetMenu]): detached
 * card anchored above the navigation-bar inset with 16dp side margins, 28dp
 * corners, kyant drawBackdrop (vibrancy + 32dp blur) under a dark charcoal
 * tint, a plain dim scrim OUTSIDE the card, no-bounce spring slide-up enter
 * and a reversed 200ms exit, back-button dismissal, and taps inside the card
 * consumed so they never dismiss accidentally.
 *
 * All the CONTENT (header, connection status, route list, error / empty /
 * loading states) is the exact existing composables the ModalBottomSheet
 * path uses — re-themed via a [MaterialTheme] overlay so their Material color
 * reads render the lyrics-popup glass material (white ink, translucent
 * containers, iOS System Red for the error card).
 */
@Composable
private fun CastRoutePickerGlassSheet(
    visible: Boolean,
    backdrop: PlatformBackdrop,
    castState: CastUiState,
    screenState: CastRoutePickerScreenState,
    onDismissRequest: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onRouteClick: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    // Render state: true from entering composition until the exit animation
    // completes — `visible` flipping false only REQUESTS dismissal.
    var renderState by remember { mutableStateOf(false) }
    val enterProgress = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            renderState = true
            enterProgress.snapTo(0f)
            enterProgress.animateTo(
                targetValue = 1f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
            )
        } else if (renderState) {
            enterProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200),
            )
            focusManager.clearFocus()
            renderState = false
        }
    }

    BackHandler(enabled = renderState) {
        onDismissRequest()
    }

    if (!renderState) return

    val alpha = enterProgress.value
    val density = LocalDensity.current

    // Memoized drawBackdrop chain — the same recipe the lyrics overflow
    // popup and the song-overflow popup use (vibrancy + 32dp strong blur).
    val glassModifier =
        remember(backdrop) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                effects = {
                    vibrancy()
                    blur(32f.dp.toPx())
                },
                onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                shape = { CastGlassSheetShape },
            )
        }

    // Glass theme overlay: remap the Material roles the picker's content
    // composables read so they render the dark-glass material on the frost.
    val glassColorScheme =
        MaterialTheme.colorScheme.copy(
            onSurface = Color.White,
            onBackground = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = 0.72f),
            surfaceContainerLow = Color.Transparent,
            surfaceContainer = Color.Transparent,
            surfaceContainerHigh = Color.White.copy(alpha = 0.08f),
            surfaceContainerHighest = Color.White.copy(alpha = 0.14f),
            primaryContainer = Color.White.copy(alpha = 0.14f),
            onPrimaryContainer = Color.White,
            secondaryContainer = Color.White.copy(alpha = 0.18f),
            onSecondaryContainer = Color.White,
            primary = Color.White,
            errorContainer = Color(0xFFFF453A).copy(alpha = 0.28f),
            onErrorContainer = Color.White,
            outlineVariant = Color.White.copy(alpha = 0.12f),
        )

    val scrimInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }

    // Discovery runs while the sheet is actually rendered (enter through
    // exit-complete), mirroring the ModalBottomSheet path's DisposableEffect.
    DisposableEffect(onStartDiscovery, onStopDiscovery) {
        onStartDiscovery()
        onDispose(onStopDiscovery)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Plain dim scrim — the blur lives on the card itself.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.Black.copy(alpha = 0.50f))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                    ) {
                        onDismissRequest()
                    },
        )

        // The floating glass card.
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .navigationBarsPadding()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = with(density) { (1f - alpha) * 48.dp.toPx() }
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = CastGlassSheetShape,
                        clip = false,
                    )
                    .then(glassModifier)
                    .background(Color(0x8C1C1C1E))
                    .clip(CastGlassSheetShape)
                    .clickable(
                        interactionSource = cardInteractionSource,
                        indication = null,
                    ) {
                        // Consume taps inside the card.
                    },
        ) {
            MaterialTheme(colorScheme = glassColorScheme) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .padding(horizontal = 20.dp)
                            .padding(top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    CastRoutePickerHeader(onDismissRequest = onDismissRequest)
                    CastRoutePickerStatus(
                        castState = castState,
                        screenState = screenState,
                        onDisconnect = onDisconnect,
                    )
                    CastRoutePickerContent(
                        screenState = screenState,
                        onRouteClick = onRouteClick,
                    )
                }
            }
        }
    }
}
