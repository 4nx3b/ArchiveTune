/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Attachment entry for the Listen Together chat composer: the "+" button's
 * liquid-glass morph popup (Song / GIF) and the Giphy picker sheet. GIFs are
 * shared as links only — the server relays the URL and every client animates
 * the GIF locally.
 */

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package moe.rukamori.archivetune.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import coil3.compose.AsyncImage
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.listentogether.GifShareApi
import moe.rukamori.archivetune.listentogether.GiphyApi
import moe.rukamori.archivetune.ui.component.PlatformBackdrop

/** Debounce for the GIF search field. */
private const val GIF_SEARCH_DEBOUNCE_MS = 400L

/**
 * Liquid-glass attachment menu anchored to the composer's paperclip button:
 * opens with the same morph (spring scale + fade from the anchor) the message
 * actions popup uses, over a locally-recorded chat backdrop, with divider
 * rules between the options — Song, GIF and the chat wallpaper (set/remove).
 */
@Composable
internal fun AttachmentMenuPopup(
    anchor: Rect,
    backdrop: PlatformBackdrop?,
    wallpaperSet: Boolean,
    scrimAlpha: Float = 0.30f,
    onPickSong: () -> Unit,
    onPickGif: () -> Unit,
    onPickWallpaper: () -> Unit,
    onRemoveWallpaper: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dismissed by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.4f) }
    val alphaAnim = remember { Animatable(0f) }

    var popupWidthPx by remember { mutableStateOf(0) }
    var popupHeightPx by remember { mutableStateOf(0) }
    var overlayWidthPx by remember { mutableStateOf(0) }
    var overlayHeightPx by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(1f, tween(180)) }
        scaleJob.join()
        alphaJob.join()
    }

    LaunchedEffect(dismissed) {
        if (!dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(0.4f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(0f, tween(160)) }
        scaleJob.join()
        alphaJob.join()
        onDismiss()
    }

    fun placement(): IntOffset {
        val marginPx = with(density) { 12.dp.toPx() }.toInt()
        val width = if (popupWidthPx > 0) popupWidthPx else with(density) { 200.dp.toPx() }.toInt()
        val height = if (popupHeightPx > 0) popupHeightPx else with(density) { 132.dp.toPx() }.toInt()
        val screenW = if (overlayWidthPx > 0) overlayWidthPx else width + 2 * marginPx
        val screenH = if (overlayHeightPx > 0) overlayHeightPx else 2000
        // Sits above the composer's anchor (bottom-start), clamped on screen.
        val x = anchor.left.toInt().coerceIn(marginPx, (screenW - width - marginPx).coerceAtLeast(marginPx))
        val y = (anchor.top.toInt() - height - with(density) { 8.dp.toPx() }.toInt())
            .coerceAtLeast(marginPx)
            .coerceAtMost((screenH - height - marginPx).coerceAtLeast(marginPx))
        return IntOffset(x, y)
    }

    val popupShape = RoundedCornerShape(18.dp)
    val overlayScrimAlpha = 0.18f * alphaAnim.value

    val frostedModifier =
        remember(backdrop, scrimAlpha) {
            if (backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    effects = {
                        colorControls(saturation = 1.7f)
                        blur(20f.dp.toPx())
                        lens(
                            refractionHeight = 16f.dp.toPx(),
                            refractionAmount = 40f.dp.toPx(),
                        )
                    },
                    onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                    onDrawSurface = {
                        drawRect(Color.Black.copy(alpha = scrimAlpha))
                    },
                    shape = { popupShape },
                )
            } else {
                Modifier
                    .background(Color(0xF226262B), popupShape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), popupShape)
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    overlayWidthPx = size.width
                    overlayHeightPx = size.height
                }
                .background(Color.Black.copy(alpha = overlayScrimAlpha))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (!dismissed) dismissed = true },
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .offset { placement() }
                    .onSizeChanged { size ->
                        popupWidthPx = size.width
                        popupHeightPx = size.height
                    }
                    .widthIn(min = 180.dp, max = 240.dp)
                    .graphicsLayer {
                        this.alpha = alphaAnim.value
                        this.scaleX = scaleAnim.value
                        this.scaleY = scaleAnim.value
                        this.transformOrigin = TransformOrigin(0.08f, 1f)
                        this.shadowElevation = 18.dp.toPx()
                        this.shape = popupShape
                        this.clip = false
                    }
                    .clip(popupShape)
                    .then(frostedModifier)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            AttachmentOptionRow(
                icon = R.drawable.music_note,
                label = stringResource(R.string.listen_together_chat_attachment_song),
                description = stringResource(R.string.listen_together_chat_pick_song_title),
            ) {
                onPickSong()
                if (!dismissed) dismissed = true
            }

            Spacer(
                modifier =
                    Modifier
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.14f)),
            )

            AttachmentOptionRow(
                icon = R.drawable.solar_play_linear,
                label = stringResource(R.string.listen_together_chat_attachment_gif),
                description = stringResource(R.string.listen_together_chat_gif_picker_hint),
            ) {
                onPickGif()
                if (!dismissed) dismissed = true
            }

            Spacer(
                modifier =
                    Modifier
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.14f)),
            )

            AttachmentOptionRow(
                icon = R.drawable.image,
                label = stringResource(R.string.listen_together_chat_set_wallpaper),
                description = stringResource(R.string.listen_together_chat_wallpaper_hint),
            ) {
                onPickWallpaper()
                if (!dismissed) dismissed = true
            }

            if (wallpaperSet) {
                Spacer(
                    modifier =
                        Modifier
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.14f)),
                )

                AttachmentOptionRow(
                    icon = R.drawable.hide_image,
                    label = stringResource(R.string.listen_together_chat_remove_wallpaper),
                    description = stringResource(R.string.listen_together_chat_wallpaper_hint),
                ) {
                    onRemoveWallpaper()
                    if (!dismissed) dismissed = true
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptionRow(
    icon: Int,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bottom sheet with the GIF catalog: trending Giphy GIFs by default, a search
 * field, endless scroll pagination, and tap-to-send — plus a "My device" tab
 * that uploads any GIF the user picked (the keyboard's integrated GIF page
 * saves into the gallery exactly like any other share) through the anonymous
 * file host so it reaches the room exactly like a Giphy result: as a plain
 * HTTPS link on the [LTG:] envelope, original aspect ratio included. Sending
 * shares only the URL — the chat relay never processes the media.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GifPickerSheet(
    onPickGif: (url: String, width: Int, height: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        var tab by remember { mutableStateOf(0) }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 480.dp)
                    .padding(bottom = 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.listen_together_chat_gif_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TabPill(
                    label = "Giphy",
                    selected = tab == 0,
                    onClick = { tab = 0 },
                )
                TabPill(
                    label = stringResource(R.string.listen_together_chat_gif_my_device),
                    selected = tab == 1,
                    onClick = { tab = 1 },
                )
            }

            when (tab) {
                0 -> GiphyCatalogTab(onPickGif)
                else -> CustomGifTab(onPickGif, onDismiss)
            }
        }
    }
}

/** Small rounded tab selector for the GIF sheet header. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** The Giphy catalog: search + trending + endless scroll. */
@Composable
private fun GiphyCatalogTab(
    onPickGif: (url: String, width: Int, height: Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<GiphyApi.GifItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var nextOffset by remember { mutableStateOf<Int?>(0) }
    var requestKey by remember { mutableStateOf(0) }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        val offset = if (reset) 0 else nextOffset ?: return
        loading = true
        val result =
            if (query.isBlank()) {
                GiphyApi.trending(offset)
            } else {
                GiphyApi.search(query, offset)
            }
        when (result) {
            is GiphyApi.Result.Success -> {
                failed = false
                items = if (reset) result.items else items + result.items
                nextOffset = result.nextOffset
            }

            GiphyApi.Result.Failure -> {
                if (reset) {
                    failed = true
                    items = emptyList()
                }
            }
        }
        loading = false
    }

    // Trending on open; debounced search as the query changes.
    LaunchedEffect(query) {
        delay(GIF_SEARCH_DEBOUNCE_MS)
        load(reset = true)
    }

    // Endless scroll: fetch the next page as the end approaches.
    val closeToEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(closeToEnd, nextOffset) {
        if (closeToEnd && nextOffset != null && !loading) {
            load(reset = false)
        }
    }
    LaunchedEffect(requestKey) {
        if (requestKey > 0) load(reset = true)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.listen_together_chat_gif_picker_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        scope.launch { load(reset = true) }
                    },
                ),
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = stringResource(R.string.search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 380.dp),
        ) {
            when {
                failed && items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.listen_together_chat_gif_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { requestKey++ },
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp),
                    ) {
                        items(items = items, key = { it.id }) { gif ->
                            val url = gif.url ?: return@items
                            Box(
                                modifier =
                                    Modifier
                                        .aspectRatio(
                                            (gif.width.coerceAtLeast(1)).toFloat() /
                                                (gif.height.coerceAtLeast(1)).toFloat(),
                                        )
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onPickGif(url, gif.width, gif.height) }
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = gif.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        if (loading) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(26.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom GIFs: anything the user picked with the system picker — including GIFs
 * saved by the keyboard's integrated GIF page — uploads to the anonymous host
 * and then sends exactly like a Giphy result (link + intrinsic dimensions, so
 * receivers render the ORIGINAL aspect ratio, never a fixed cell).
 */
@Composable
private fun CustomGifTab(
    onPickGif: (url: String, width: Int, height: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uploading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                uploading = true
                failed = false
                val uploaded = GifShareApi.upload(context, uri)
                uploading = false
                if (uploaded != null) {
                    onPickGif(uploaded.url, uploaded.width, uploaded.height)
                    onDismiss()
                } else {
                    failed = true
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp)
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (uploading) {
            CircularProgressIndicator(modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.listen_together_chat_gif_uploading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable {
                            picker.launch(arrayOf("image/gif"))
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.listen_together_chat_gif_pick_custom),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.listen_together_chat_gif_pick_custom_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (failed) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.listen_together_chat_gif_upload_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
