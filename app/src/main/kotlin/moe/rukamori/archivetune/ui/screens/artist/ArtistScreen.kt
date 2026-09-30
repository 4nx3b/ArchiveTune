/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.artist

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clipToBounds
import moe.rukamori.archivetune.ui.theme.BackdropTonePalette
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.CONTENT_TYPE_ALBUM
import moe.rukamori.archivetune.constants.CONTENT_TYPE_ARTIST
import moe.rukamori.archivetune.constants.CONTENT_TYPE_HEADER
import moe.rukamori.archivetune.constants.CONTENT_TYPE_LIST
import moe.rukamori.archivetune.constants.CONTENT_TYPE_PLAYLIST
import moe.rukamori.archivetune.constants.CONTENT_TYPE_SONG
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.player.LocalPlayerSheetOverlayActive
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.AlbumReleaseType
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.ArtistSectionLayout
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.AlbumGridItem
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.HideOnScrollFAB
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LiquidGlassIconButton
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.MenuSectionDivider
import moe.rukamori.archivetune.ui.component.NavigationTitle
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.YouTubeGridItem
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.glassSource
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.shimmer.ButtonPlaceholder
import moe.rukamori.archivetune.ui.component.shimmer.ListItemPlaceHolder
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.component.rememberThrottledBackdrop
import moe.rukamori.archivetune.ui.menu.AlbumMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubeArtistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache
import moe.rukamori.archivetune.ui.utils.YtimgResizePolicy
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.formatCompactCount
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.utils.ReleaseRadarRepository
import moe.rukamori.archivetune.utils.UpcomingRelease
import moe.rukamori.archivetune.utils.parsePresavedReleases
import moe.rukamori.archivetune.utils.togglePresavedRelease
import moe.rukamori.archivetune.utils.PresavedRelease
import moe.rukamori.archivetune.utils.ReleasePresaveKey
import moe.rukamori.archivetune.utils.setPresavedReleases
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.constants.PresaveReleaseRadarKey
import moe.rukamori.archivetune.viewmodels.ArtistAction
import moe.rukamori.archivetune.viewmodels.ArtistBlockState
import moe.rukamori.archivetune.viewmodels.ArtistEvent
import moe.rukamori.archivetune.viewmodels.ArtistViewModel
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val loadedArtistPage = viewModel.artistPage
    val libraryArtist by viewModel.libraryArtist.collectAsStateWithLifecycle()
    val loadedLibrarySongs by viewModel.librarySongs.collectAsStateWithLifecycle()
    val loadedLibraryAlbums by viewModel.libraryAlbums.collectAsStateWithLifecycle()
    val blockState by viewModel.blockState.collectAsStateWithLifecycle()
    val canvasArtwork by viewModel.canvasArtwork.collectAsStateWithLifecycle()
    val artistCanvasEnabled by rememberPreference(key = AlbumCanvasEnabledKey, defaultValue = true)
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = true)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current

    val screenSettled = rememberLayerBackdropSettled()

    // The glass recording source stays attached for the whole lifetime of the
    // screen: detaching kyant's LayerBackdrop while the player sheet covers the
    // header nulls its layerCoordinates, and glass never comes back afterwards
    // (the maximise->minimise "pills turn light" bug). The pills themselves fade
    // with the sheet edge in their own draw phase, and the recorder is throttled,
    // so an always-attached source is both correct and cheap.
    val glassHeaderActive = liquidGlassHeaderActive && !lyricsFullScreen && screenSettled
    // Mini-player-bound overlay signal for non-glass gating (canvas decode,
    // hero animations) — keeps the OLD mini-bound semantics.
    val playerSheetOverlayActive = LocalPlayerSheetOverlayActive.current
    val isArtistBlocked = (blockState as? ArtistBlockState.Success)?.isBlocked == true

    BackHandler {
        try {
            if (!navController.popBackStack()) {
                navController.navigate("library") { launchSingleTop = true }
            }
        } catch (_: Exception) {
            try {
                if (!navController.navigateUp()) {
                    navController.navigate("library") { launchSingleTop = true }
                }
            } catch (_: Exception) {
            }
        }
    }

    val artistPage =
        remember(loadedArtistPage, isArtistBlocked) {
            if (isArtistBlocked) {
                loadedArtistPage?.copy(
                    artist =
                        loadedArtistPage.artist.copy(
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        ),
                    sections = emptyList(),
                )
            } else {
                loadedArtistPage
            }
        }
    val librarySongs = remember(loadedLibrarySongs, isArtistBlocked) { loadedLibrarySongs.takeUnless { isArtistBlocked }.orEmpty() }
    val libraryAlbums = remember(loadedLibraryAlbums, isArtistBlocked) { loadedLibraryAlbums.takeUnless { isArtistBlocked }.orEmpty() }

    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLocal by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ArtistEvent.Share -> {
                    val shareIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, event.link)
                        }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                }

                is ArtistEvent.CopyLink -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copy_link), event.link))
                    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                }

                is ArtistEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(context.getString(event.messageRes))
                }
            }
        }
    }

    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDarkTheme = surfaceColor.luminance() <= 0.5f
    val heroContentColor =
        if (surfaceColor.luminance() > 0.5f) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.White
        }
    val thumbnail = artistPage?.artist?.thumbnail ?: libraryArtist?.artist?.thumbnailUrl

    val transparentAppBar by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset < 100
        }
    }

    LaunchedEffect(libraryArtist) {
        showLocal = libraryArtist?.artist?.isLocal == true
    }

    val latestRelease =
        remember(showLocal, artistPage, libraryAlbums) {
            if (showLocal) {
                libraryAlbums
                    .maxByOrNull { it.album.year ?: Int.MIN_VALUE }
                    ?.let { album ->
                        ArtistReleaseUiModel(
                            id = album.id,
                            title = album.album.title,
                            thumbnailUrl = album.album.thumbnailUrl,
                            year = album.album.year,
                            releaseType = AlbumReleaseType.ALBUM,
                        )
                    }
            } else {
                artistPage
                    ?.sections
                    .orEmpty()
                    .asSequence()
                    .flatMap { it.items.asSequence() }
                    .filterIsInstance<AlbumItem>()
                    .maxByOrNull { it.year ?: Int.MIN_VALUE }
                    ?.toArtistReleaseUiModel()
            }
        }

    // ---- Pre-save & Release Countdown -----------------------------------
    // While the toggle is on, the artist's UPCOMING catalogue entries show near
    // the top of the page (below the hero) with a live countdown — the same
    // card design the latest-release pill uses — plus a Listen Later bookmark
    // that persists into the release presave store.
    val presaveRadarEnabled by rememberPreference(PresaveReleaseRadarKey, defaultValue = false)
    var upcomingReleases by remember { mutableStateOf<List<UpcomingRelease>>(emptyList()) }
    val radarArtistName = artistPage?.artist?.title ?: libraryArtist?.artist?.name
    LaunchedEffect(presaveRadarEnabled, radarArtistName) {
        upcomingReleases = emptyList()
        if (presaveRadarEnabled && !radarArtistName.isNullOrBlank() && !showLocal) {
            upcomingReleases =
                ReleaseRadarRepository
                    .upcomingReleasesForArtist(radarArtistName)
                    .take(3)
        }
    }
    val orderedRemoteSections =
        remember(artistPage?.sections) {
            val sections = artistPage?.sections.orEmpty()
            val topSongsSection =
                sections.firstOrNull { section ->
                    section.layout == ArtistSectionLayout.LIST && section.items.all { it is SongItem }
                }
            if (topSongsSection == null) {
                sections
            } else {
                listOf(topSongsSection) + sections.filterNot { it === topSongsSection }
            }
        }
    val showArtistOverflowMenu: () -> Unit = {
        menuState.show {
            ArtistOverflowMenu(
                isBlocked = isArtistBlocked,
                blockActionEnabled =
                    blockState !is ArtistBlockState.Loading &&
                        (
                            artistPage
                                ?.artist
                                ?.title
                                .orEmpty()
                                .isNotBlank() ||
                                libraryArtist
                                    ?.artist
                                    ?.name
                                    .orEmpty()
                                    .isNotBlank()
                        ),
                onAction = { action ->
                    viewModel.onAction(action)
                    menuState.dismiss()
                },
            )
        }
    }

    val artworkBackdrop = rememberThrottledBackdrop(surfaceColor)

    val isManuallyRefreshing = viewModel.isManuallyRefreshing

    // ---- Dynamic artist background --------------------------------------
    // Fallback chain (per spec): 1) the actively rendering Canvas  2) the
    // current track's artwork  3) the artist artwork  4) the plain theme
    // surface. Every hop is palette-extracted off the main thread, cached via
    // PlayerPaletteCache, and the final stops are colour-animated so switching
    // sources (or the canvas content itself changing) never flashes or jumps.

    // (1) Canvas sampling — the hero's own CanvasArtworkPlayer is captured into
    // a small GraphicsLayer while it renders; a throttled coroutine samples that
    // layer, downsamples to a 24px thumbnail and extracts dominant colours. No
    // second video player, no full-resolution processing, all palette work on
    // Dispatchers.Default, and everything stops the moment the page is covered
    // or the canvas stops rendering.
    val canvasSampleLayer: GraphicsLayer = rememberGraphicsLayer()
    val canvasRecordClock = remember { longArrayOf(0L) }
    var canvasSampleLayerSize by remember { mutableStateOf(IntSize.Zero) }
    var canvasAmbientColors by remember { mutableStateOf<List<Color>?>(null) }
    val heroCanvasPrimaryUrl =
        (canvasArtwork?.animated ?: canvasArtwork?.videoUrl)?.takeIf { artistCanvasEnabled }
    val heroCanvasFallbackUrl =
        canvasArtwork?.videoUrl?.takeIf { artistCanvasEnabled }
    val heroCanvasPresent =
        !heroCanvasPrimaryUrl.isNullOrBlank() || !heroCanvasFallbackUrl.isNullOrBlank()
    val heroVisible by remember {
        derivedStateOf { lazyListState.firstVisibleItemIndex == 0 }
    }
    // Sampling pauses while the list is actively scrolling: the full-hero
    // GPU readback (~9MB per sample) was the main source of scroll jank.
    val listScrolling by remember { derivedStateOf { lazyListState.isScrollInProgress } }
    val canvasSamplingActive =
        heroCanvasPresent && heroVisible && !lyricsFullScreen && !playerSheetOverlayActive && !listScrolling

    LaunchedEffect(canvasSamplingActive) {
        if (!canvasSamplingActive) {
            canvasAmbientColors = null
        } else {
            while (true) {
                delay(CANVAS_SAMPLE_INTERVAL_MILLIS)
                val layerSize = canvasSampleLayerSize
                if (layerSize.width < 8 || layerSize.height < 8) continue
                val sampled =
                    try {
                        withContext(Dispatchers.Default) {
                            val bitmap = canvasSampleLayer.toImageBitmap().asAndroidBitmap()
                            if (bitmap.width < 8 || bitmap.height < 8) {
                                null
                            } else {
                                val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
                                val palette =
                                    Palette
                                        .from(small)
                                        .maximumColorCount(8)
                                        .generate()
                                val dominant = palette.dominantSwatch
                                val vivid =
                                    palette.vibrantSwatch
                                        ?: palette.lightVibrantSwatch
                                        ?: palette.darkVibrantSwatch
                                        ?: palette.mutedSwatch
                                when {
                                    dominant != null && vivid != null && vivid.rgb != dominant.rgb ->
                                        listOf(Color(vivid.rgb), Color(dominant.rgb))

                                    dominant != null -> listOf(Color(dominant.rgb))
                                    vivid != null -> listOf(Color(vivid.rgb))
                                    else -> null
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        null
                }
                if (!sampled.isNullOrEmpty()) canvasAmbientColors = sampled
            }
        }
    }

    // (2) + (3) artwork palettes, cached.
    val trackArtworkUrl = mediaMetadata?.thumbnailUrl
    var trackArtworkColors by remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(mediaMetadata?.id, trackArtworkUrl, isDarkTheme) {
        trackArtworkColors =
            extractAmbientArtworkColors(
                context = context,
                mediaId = mediaMetadata?.id.orEmpty().ifBlank { "track" },
                artworkUrl = trackArtworkUrl,
                darkTheme = isDarkTheme,
            )
    }
    var artistArtworkColors by remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(thumbnail, isDarkTheme) {
        artistArtworkColors =
            extractAmbientArtworkColors(
                context = context,
                mediaId = "artist:${viewModel.artistId}",
                artworkUrl = thumbnail,
                darkTheme = isDarkTheme,
            )
    }

    val ambientSource = canvasAmbientColors ?: trackArtworkColors ?: artistArtworkColors
    // The immersive-player (V7) bottom-controls gradient ladder: the dominant
    // colour projected into three value bands (bright top, mid, deep bottom).
    // NO backdrop blur, NO theme-surface mixing — exactly the colour gradience
    // the V7 player paints behind its transport controls.
    val ambientPalette =
        remember(ambientSource, surfaceColor) {
            BackdropTonePalette.fromColors(
                colors = ambientSource.orEmpty(),
                fallbackColor = surfaceColor.toArgb(),
            )
        }
    val animatedAmbientTop by animateColorAsState(
        targetValue = ambientPalette.top,
        animationSpec = tween(durationMillis = 900),
        label = "artistAmbientTop",
    )
    val animatedAmbientMid by animateColorAsState(
        targetValue = ambientPalette.mid,
        animationSpec = tween(durationMillis = 900),
        label = "artistAmbientMid",
    )
    val animatedAmbientBottom by animateColorAsState(
        targetValue = ambientPalette.bottom,
        animationSpec = tween(durationMillis = 900),
        label = "artistAmbientBottom",
    )

    // Hero scroll: collapse + fade + parallax while the hero scrolls away.
    val heroCollapseFraction by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex == 0) {
                (lazyListState.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }
    val heroParallaxOffset by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex == 0) {
                lazyListState.firstVisibleItemScrollOffset.toFloat()
            } else {
                900f
            }
        }
    }

    // Subscribe toggle shared by the hero favourite circle (both glass and
    // classic mode) — identical behaviour to the old primary-actions row.
    val toggleArtistSubscription: () -> Unit = {
        database.transaction {
            val artist = libraryArtist?.artist
            if (artist != null) {
                update(artist.toggleLike())
            } else {
                artistPage?.artist?.let { remoteArtist ->
                    insert(
                        ArtistEntity(
                            id = remoteArtist.id,
                            name = remoteArtist.title,
                            channelId = remoteArtist.channelId,
                            thumbnailUrl = remoteArtist.thumbnail,
                        ).toggleLike(),
                    )
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor),
    ) {
        // Atmospheric background: the animated palette-gradient alone (the
        // immersive-player colour gradience). The old 80dp blurred-artwork
        // layer was removed per request — no backdrop blur on this page.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to animatedAmbientTop,
                            0.5f to animatedAmbientMid,
                            1f to animatedAmbientBottom,
                        ),
                    ),
        )

        ExpressivePullToRefreshBox(
            isRefreshing = isManuallyRefreshing,
            onRefresh = viewModel::manualRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            modifier =
                if (glassHeaderActive) {
                    Modifier.glassSource(artworkBackdrop)
                } else {
                    Modifier
                },
            state = lazyListState,
            contentPadding =
                PaddingValues(
                    bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            if (isManuallyRefreshing && artistPage == null && !showLocal) {
                item(key = "shimmer") {
                    ShimmerHost {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = ArtistHeroMinHeight)
                                    .shimmer()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = ArtistHorizontalPadding, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                TextPlaceholder(height = 36.dp, modifier = Modifier.fillMaxWidth(0.55f))
                                Spacer(modifier = Modifier.height(12.dp))
                                TextPlaceholder(height = 16.dp, modifier = Modifier.fillMaxWidth(0.72f))
                                Spacer(modifier = Modifier.height(20.dp))
                                TextPlaceholder(height = 14.dp, modifier = Modifier.fillMaxWidth(0.82f))
                                Spacer(modifier = Modifier.height(12.dp))
                                ButtonPlaceholder(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(ButtonDefaults.MediumContainerHeight),
                                )
                            }
                        }

                        repeat(5) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            } else {
                item(key = "header") {
                    val artistName = artistPage?.artist?.title ?: libraryArtist?.artist?.name
                    val unknownArtist = stringResource(R.string.unknown_artist)
                    val songsLabel = stringResource(R.string.songs)
                    val albumsLabel = stringResource(R.string.albums)
                    val monthlyListenersLabel = stringResource(R.string.monthly_listeners)
                    val subscribersLabel = stringResource(R.string.subscribers)
                    val artistStats =
                        remember(
                            showLocal,
                            artistPage,
                            librarySongs.size,
                            libraryAlbums.size,
                            songsLabel,
                            albumsLabel,
                            monthlyListenersLabel,
                            subscribersLabel,
                        ) {
                            buildArtistStats(
                                showLocal = showLocal,
                                artistPage = artistPage,
                                librarySongCount = librarySongs.size,
                                libraryAlbumCount = libraryAlbums.size,
                                songsLabel = songsLabel,
                                albumsLabel = albumsLabel,
                                monthlyListenersLabel = monthlyListenersLabel,
                                subscribersLabel = subscribersLabel,
                            )
                        }
                    val isSubscribed = libraryArtist?.artist?.bookmarkedAt != null

                    // The reference hero: immersive artwork with the name and
                    // stats anchored at the bottom, a large circular play
                    // button on the lower right, and (non-glass mode only)
                    // share / favourite circles on the top right. In glass
                    // mode those controls are fixed liquid-glass overlays
                    // rendered above this list instead.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = ArtistHeroMinHeight)
                                // clipToBounds: the parallax translates the
                                // artwork DOWNWARDS at half scroll speed —
                                // without clipping it bled through the sections
                                // below (the profile picture / canvas showed
                                // through the rows while scrolling).
                                .clipToBounds(),
                    ) {
                        // Immersive hero image with a gentle parallax: the
                        // artwork scrolls at half speed while the list moves
                        // over it, so the hero "collapses" smoothly.
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .graphicsLayer {
                                        translationY = heroParallaxOffset * 0.5f
                                    },
                        ) {
                            if (thumbnail != null) {
                                AsyncImage(
                                    model =
                                        thumbnail.resize(
                                            width = ArtistHeroArtworkSizePx,
                                            height = ArtistHeroArtworkSizePx,
                                            sizeBuckets = ArtistHeroArtworkSizeBuckets,
                                            ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                                        ),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                )
                            } else {
                                Box(
                                    modifier =
                                        Modifier
                                            .matchParentSize()
                                            .background(animatedAmbientTop),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.person),
                                        contentDescription = null,
                                        modifier = Modifier.size(96.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // Canvas overlay + ambient sampling recorder. The
                            // draw hook re-records the canvas region into a
                            // small GraphicsLayer on a throttle; the sampler
                            // coroutine (above) reads that layer ~once a
                            // second. Recording pauses whenever sampling is
                            // inactive, so a static hero costs nothing.
                            if (heroCanvasPresent) {
                                Box(
                                    modifier =
                                        Modifier
                                            .matchParentSize()
                                            .onSizeChanged { canvasSampleLayerSize = it }
                                            .drawWithContent {
                                                if (canvasSamplingActive) {
                                                    val now = SystemClock.uptimeMillis()
                                                    if (now - canvasRecordClock[0] >= CANVAS_RECORD_INTERVAL_MILLIS) {
                                                        canvasRecordClock[0] = now
                                                        canvasSampleLayer.record(size.toIntSize()) {
                                                            this@drawWithContent.drawContent()
                                                        }
                                                    }
                                                }
                                                drawContent()
                                            },
                                ) {
                                    moe.rukamori.archivetune.ui.player.CanvasArtworkPlayer(
                                        primaryUrl = heroCanvasPrimaryUrl,
                                        fallbackUrl = heroCanvasFallbackUrl,
                                        isPlaying = true,
                                        visible = !lyricsFullScreen && !playerSheetOverlayActive,
                                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                            }
                        }

                        // Legibility scrim: dark at the very top for the
                        // status/app-bar area, fading out through the middle
                        // and back into the ambient background colour at the
                        // bottom so hero and background read as one surface.
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Black.copy(alpha = 0.34f),
                                            0.16f to Color.Transparent,
                                            0.46f to Color.Transparent,
                                            0.78f to animatedAmbientBottom.copy(alpha = 0.55f),
                                            1f to animatedAmbientBottom,
                                        ),
                                    ),
                        )

                        // Non-glass mode: translucent share / favourite circles
                        // pinned to the hero's top-right, below the app bar.
                        if (!liquidGlassHeaderActive) {
                            Row(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(
                                            end = ArtistHorizontalPadding,
                                            top = systemBarsTopPadding + AppBarHeight + 8.dp,
                                        ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ArtistHeroTranslucentCircle(
                                    iconRes = R.drawable.share,
                                    contentDescription = stringResource(R.string.share),
                                    contentColor = heroContentColor,
                                    enabled = true,
                                    onClick = { viewModel.onAction(ArtistAction.Share) },
                                )
                                ArtistHeroTranslucentCircle(
                                    iconRes = if (isSubscribed) R.drawable.favorite else R.drawable.favorite_border,
                                    contentDescription = stringResource(
                                        if (isSubscribed) R.string.subscribed else R.string.subscribe,
                                    ),
                                    contentColor = heroContentColor,
                                    enabled = true,
                                    tint = if (isSubscribed) MaterialTheme.colorScheme.primary else null,
                                    onClick = toggleArtistSubscription,
                                )
                            }
                        }

                        // Bottom identity block + hero actions.
                        Row(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(
                                        start = ArtistHorizontalPadding,
                                        top = systemBarsTopPadding + AppBarHeight + 96.dp,
                                        end = ArtistHorizontalPadding,
                                        bottom = 24.dp,
                                    )
                                    .graphicsLayer {
                                        alpha = 1f - heroCollapseFraction * 0.9f
                                        translationY = -heroParallaxOffset * 0.10f
                                    },
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artistName ?: unknownArtist,
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = heroContentColor,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                if (artistStats.audience.isNotEmpty()) {
                                    Text(
                                        text = artistStats.audience,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = heroContentColor.copy(alpha = 0.72f),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                }

                                if (artistStats.catalog.isNotEmpty()) {
                                    Text(
                                        text = artistStats.catalog,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = heroContentColor.copy(alpha = 0.55f),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier =
                                            Modifier.padding(
                                                top =
                                                    if (artistStats.audience.isEmpty()) {
                                                        10.dp
                                                    } else {
                                                        4.dp
                                                    },
                                            ),
                                    )
                                }
                            }

                            // Hero actions: shuffle + radio as restrained
                            // translucent circles above the large play button.
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val canShuffle =
                                        if (showLocal) {
                                            librarySongs.isNotEmpty()
                                        } else {
                                            artistPage?.artist?.shuffleEndpoint != null
                                        }
                                    val canRadio =
                                        !showLocal && artistPage?.artist?.radioEndpoint != null
                                    if (canShuffle || canRadio) {
                                        if (canShuffle) {
                                            ArtistHeroTranslucentCircle(
                                                iconRes = R.drawable.ic_shuffle,
                                                contentDescription = stringResource(R.string.shuffle),
                                                contentColor = heroContentColor,
                                                enabled = true,
                                                onClick = {
                                                    if (showLocal) {
                                                        if (librarySongs.isNotEmpty()) {
                                                            playerConnection.playQueue(
                                                                ListQueue(
                                                                    title = artistName ?: unknownArtist,
                                                                    items = librarySongs.shuffled().map { it.toMediaItem() },
                                                                ),
                                                            )
                                                        }
                                                    } else {
                                                        artistPage?.artist?.shuffleEndpoint?.let { endpoint ->
                                                            playerConnection.playQueue(YouTubeQueue(endpoint))
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        if (canRadio) {
                                            ArtistHeroTranslucentCircle(
                                                iconRes = R.drawable.radio,
                                                contentDescription = stringResource(R.string.start_radio),
                                                contentColor = heroContentColor,
                                                enabled = true,
                                                onClick = {
                                                    artistPage?.artist?.radioEndpoint?.let { endpoint ->
                                                        playerConnection.playQueue(
                                                            YouTubeQueue(endpoint),
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                // Large circular play button, accent-coloured
                                // from the ambient palette (theme primary when
                                // no palette exists).
                                val playButtonColor =
                                    ambientSource?.let {
                                        animatedAmbientTop
                                    }?.let { color ->
                                        if (isDarkTheme) {
                                            lerp(color, Color.White, 0.12f)
                                        } else {
                                            lerp(color, Color.Black, 0.08f)
                                        }
                                    } ?: MaterialTheme.colorScheme.primary
                                val playIconColor =
                                    if (playButtonColor.luminance() > 0.5f) Color.Black else Color.White
                                val canPlay =
                                    if (showLocal) {
                                        librarySongs.isNotEmpty()
                                    } else {
                                        artistPage?.artist?.playEndpoint != null
                                    }
                                Box(
                                    modifier =
                                        Modifier
                                            .size(68.dp)
                                            .clip(CircleShape)
                                            .background(playButtonColor)
                                            .let { m ->
                                                if (canPlay) {
                                                    m.clickable(
                                                        onClick = {
                                                            if (showLocal) {
                                                                if (librarySongs.isNotEmpty()) {
                                                                    playerConnection.playQueue(
                                                                        ListQueue(
                                                                            title = artistName ?: unknownArtist,
                                                                            items = librarySongs.map { it.toMediaItem() },
                                                                        ),
                                                                    )
                                                                }
                                                            } else {
                                                                artistPage?.artist?.playEndpoint?.let { endpoint ->
                                                                    playerConnection.playQueue(YouTubeQueue(endpoint))
                                                                }
                                                            }
                                                        },
                                                    )
                                                } else {
                                                    m.alpha(0.45f)
                                                }
                                            },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.play),
                                        contentDescription = stringResource(R.string.play),
                                        tint = playIconColor,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (upcomingReleases.isNotEmpty()) {
                    item(
                        key = "upcoming_releases",
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        ArtistUpcomingReleasesColumn(
                            releases = upcomingReleases,
                        )
                    }
                }

                latestRelease?.let { release ->
                    item(
                        key = "new_release_${release.id}",
                        contentType = CONTENT_TYPE_ALBUM,
                    ) {
                        ArtistNewReleaseSection(
                            release = release,
                            onClick = { navController.navigate("album/${release.id}") },
                        )
                    }
                }

                if (!showLocal && artistPage == null && !isManuallyRefreshing) {
                    item(key = "auto_fetch_shimmer") {
                        ShimmerHost {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                TextPlaceholder(
                                    height = 18.dp,
                                    modifier = Modifier.fillMaxWidth(0.35f),
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                repeat(5) {
                                    ListItemPlaceHolder()
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Spacer(modifier = Modifier.height(16.dp))

                                TextPlaceholder(
                                    height = 18.dp,
                                    modifier = Modifier.fillMaxWidth(0.30f),
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    repeat(3) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(width = 120.dp, height = 144.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .shimmer()
                                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (showLocal) {
                    if (librarySongs.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.songs),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/songs")
                                },
                            )
                        }

                        val filteredLibrarySongs =
                            if (hideExplicit) {
                                librarySongs.filter { !it.song.explicit }
                            } else {
                                librarySongs
                            }

                        itemsIndexed(
                            items = filteredLibrarySongs.take(5),
                            key = { _, item -> "local_song_${item.id}" },
                            contentType = { _, _ -> CONTENT_TYPE_SONG },
                        ) { index, song ->
                            SongListItem(
                                song = song,
                                showInLibraryIcon = true,
                                isActive = song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                // Rows float transparently over the palette
                                // gradient — the old opaque surface islands
                                // clashed with the animated background.
                                swipeContentBackgroundColor = Color.Transparent,
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                        onLongClick = {},
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (song.id == mediaMetadata?.id) {
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = libraryArtist?.artist?.name ?: "Unknown Artist",
                                                            items = librarySongs.map { it.toMediaItem() },
                                                            startIndex = index,
                                                        ),
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                menuState.show {
                                                    SongMenu(
                                                        originalSong = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                        ).animateItem(),
                            )
                        }

                        if (filteredLibrarySongs.size > 5) {
                            item {
                                Surface(
                                    onClick = {
                                        navController.navigate("artist/${viewModel.artistId}/songs")
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.view_all),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    if (libraryAlbums.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.albums),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/albums")
                                },
                            )
                        }

                        item {
                            val filteredLibraryAlbums =
                                if (hideExplicit) {
                                    libraryAlbums.filter { !it.album.explicit }
                                } else {
                                    libraryAlbums
                                }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(
                                    items = filteredLibraryAlbums,
                                    key = { album -> "local_album_${album.id}" },
                                    contentType = { CONTENT_TYPE_ALBUM },
                                ) { album ->
                                    AlbumGridItem(
                                        album = album,
                                        isActive = mediaMetadata?.album?.id == album.id,
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier =
                                            Modifier
                                                .combinedClickable(
                                                    onClick = {
                                                        navController.navigate("album/${album.id}")
                                                    },
                                                    onLongClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        menuState.show {
                                                            AlbumMenu(
                                                                originalAlbum = album,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss,
                                                            )
                                                        }
                                                    },
                                                ).animateItem(),
                                    )
                                }
                            }
                        }
                    }
                } else {

                    orderedRemoteSections.fastForEachIndexed { sectionIndex, section ->
                        if (section.items.isNotEmpty()) {
                            item(
                                key = "youtube_section_header_${sectionIndex}_${section.title}_${section.items.firstOrNull()?.id.orEmpty()}_${section.moreEndpoint?.browseId.orEmpty()}",
                                contentType = CONTENT_TYPE_HEADER,
                            ) {
                                NavigationTitle(
                                    title = section.title,
                                    onClick =
                                        section.moreEndpoint?.let {
                                            {
                                                navController.navigate(
                                                    buildArtistItemsRoute(
                                                        viewModel.artistId,
                                                        it,
                                                        section.title,
                                                    ),
                                                )
                                            }
                                        },
                                )
                            }
                        }

                        if (section.layout == ArtistSectionLayout.LIST && section.items.all { it is SongItem }) {
                            items(
                                items = section.items.distinctBy { it.id },
                                key = { "youtube_song_${sectionIndex}_${it.id}" },
                                contentType = { CONTENT_TYPE_SONG },
                            ) { song ->
                                YouTubeListItem(
                                    item = song as SongItem,
                                    isActive = mediaMetadata?.id == song.id,
                                    isPlaying = isPlaying,
                                    // Transparent over the palette gradient.
                                    swipeContentBackgroundColor = Color.Transparent,
                                    trailingContent = {
                                        IconButton(
                                            onClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                            onLongClick = {},
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.more_vert),
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .combinedClickable(
                                                onClick = {
                                                    if (song.id == mediaMetadata?.id) {
                                                        playerConnection.player.togglePlayPause()
                                                    } else {
                                                        playerConnection.playQueue(
                                                            YouTubeQueue(
                                                                WatchEndpoint(videoId = song.id),
                                                                song.toMediaMetadata(),
                                                            ),
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        YouTubeSongMenu(
                                                            song = song,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss,
                                                        )
                                                    }
                                                },
                                            ).animateItem(),
                                )
                            }
                        } else if (section.items.isNotEmpty() && section.items.all { it is ArtistItem }) {
                            // Related artists ("Fans might also like"): large
                            // circular artwork with the name underneath, per
                            // the reference design.
                            item(
                                key = "youtube_section_artists_${sectionIndex}_${section.title}",
                                contentType = CONTENT_TYPE_LIST,
                            ) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(
                                        items = section.items.distinctBy { it.id },
                                        key = { "youtube_artist_${sectionIndex}_${it.id}" },
                                        contentType = { CONTENT_TYPE_ARTIST },
                                    ) { item ->
                                        val artistItem = item as ArtistItem
                                        ArtistCircleItem(
                                            item = artistItem,
                                            onClick = {
                                                navController.navigate("artist/${artistItem.id}")
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                menuState.show {
                                                    YouTubeArtistMenu(
                                                        artist = artistItem,
                                                        onDismiss = menuState::dismiss,
                                                    )
                                                }
                                            },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                        } else {
                            item(
                                key = "youtube_section_grid_${sectionIndex}_${section.title}_${section.items.firstOrNull()?.id.orEmpty()}_${section.moreEndpoint?.browseId.orEmpty()}",
                                contentType = CONTENT_TYPE_LIST,
                            ) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(
                                        items = section.items.distinctBy { it.id },
                                        key = {
                                            val type =
                                                when (it) {
                                                    is SongItem -> "song"
                                                    is AlbumItem -> "album"
                                                    is ArtistItem -> "artist"
                                                    is PlaylistItem -> "playlist"
                                                    else -> "item"
                                                }
                                            "youtube_${type}_${sectionIndex}_${it.id}"
                                        },
                                        contentType = {
                                            when (it) {
                                                is SongItem -> CONTENT_TYPE_SONG
                                                is AlbumItem -> CONTENT_TYPE_ALBUM
                                                is ArtistItem -> CONTENT_TYPE_ARTIST
                                                is PlaylistItem -> CONTENT_TYPE_PLAYLIST
                                                else -> CONTENT_TYPE_LIST
                                            }
                                        },
                                    ) { item ->
                                        YouTubeGridItem(
                                            item = item,
                                            isActive =
                                                when (item) {
                                                    is SongItem -> mediaMetadata?.id == item.id
                                                    is AlbumItem -> mediaMetadata?.album?.id == item.id
                                                    else -> false
                                                },
                                            isPlaying = isPlaying,
                                            coroutineScope = coroutineScope,
                                            modifier =
                                                Modifier
                                                    .combinedClickable(
                                                        onClick = {
                                                            when (item) {
                                                                is SongItem -> {
                                                                    playerConnection.playQueue(
                                                                        YouTubeQueue(
                                                                            WatchEndpoint(videoId = item.id),
                                                                            item.toMediaMetadata(),
                                                                        ),
                                                                    )
                                                                }

                                                                is AlbumItem -> {
                                                                    navController.navigate("album/${item.id}")
                                                                }

                                                                is ArtistItem -> {
                                                                    navController.navigate("artist/${item.id}")
                                                                }

                                                                is PlaylistItem -> {
                                                                    navController.navigate("online_playlist/${item.id}")
                                                                }

                                                                is PodcastItem -> {
                                                                    navController.navigate("podcast/${android.net.Uri.encode(item.browseId)}")
                                                                }

                                                                is EpisodeItem -> {
                                                                    playerConnection.playQueue(
                                                                        YouTubeQueue(
                                                                            item.endpoint,
                                                                            item.toMediaMetadata(),
                                                                        ),
                                                                    )
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            menuState.show {
                                                                when (item) {
                                                                    is SongItem -> {
                                                                        YouTubeSongMenu(
                                                                            song = item,
                                                                            navController = navController,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is AlbumItem -> {
                                                                        YouTubeAlbumMenu(
                                                                            albumItem = item,
                                                                            navController = navController,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is ArtistItem -> {
                                                                        YouTubeArtistMenu(
                                                                            artist = item,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is PlaylistItem -> {
                                                                        YouTubePlaylistMenu(
                                                                            playlist = item,
                                                                            coroutineScope = coroutineScope,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is PodcastItem, is EpisodeItem -> Unit
                                                                }
                                                            }
                                                        },
                                                    ).animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- About / description, anchored at the bottom ----
                artistPage
                    ?.description
                    ?.takeIf(String::isNotBlank)
                    ?.let { description ->
                        item(
                            key = "artist_about",
                            contentType = CONTENT_TYPE_HEADER,
                        ) {
                            ArtistAboutSection(description = description)
                        }
                    }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
        }
        HideOnScrollFAB(
            visible = librarySongs.isNotEmpty() && libraryArtist?.artist?.isLocal != true,
            lazyListState = lazyListState,
            icon = if (showLocal) R.drawable.language else R.drawable.library_music,
            label = if (showLocal) stringResource(R.string.together_online) else stringResource(R.string.filter_library),
            onClick = {
                showLocal = showLocal.not()
                if (!showLocal && artistPage == null) viewModel.fetchArtistsFromYTM()
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .align(Alignment.BottomCenter),
        )

        // Glass-mode header controls: "< Home" pill top-left, Share /
        // Favourite / overflow glass circles top-right — the reference layout.
        if (glassHeaderActive && (artistPage != null || showLocal)) {
            LiquidGlassActionPill(
                backdrop = artworkBackdrop,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .height(48.dp)
                            .combinedClickable(
                                onClick = { navController.navigateUp() },
                                onLongClick = navController::backToMain,
                            )
                            .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = liquidGlassContentColor(),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.home),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = liquidGlassContentColor(),
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiquidGlassIconButton(
                    backdrop = artworkBackdrop,
                    painter = painterResource(R.drawable.solar_share_linear),
                    contentDescription = stringResource(R.string.share),
                    modifier = Modifier.size(48.dp),
                    onClick = { viewModel.onAction(ArtistAction.Share) },
                )
                LiquidGlassIconButton(
                    backdrop = artworkBackdrop,
                    painter =
                        painterResource(
                            if (libraryArtist?.artist?.bookmarkedAt != null) {
                                R.drawable.solar_heart_bold
                            } else {
                                R.drawable.solar_heart_linear
                            },
                        ),
                    contentDescription =
                        stringResource(
                            if (libraryArtist?.artist?.bookmarkedAt != null) {
                                R.string.subscribed
                            } else {
                                R.string.subscribe
                            },
                        ),
                    tint =
                        if (libraryArtist?.artist?.bookmarkedAt != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Unspecified
                        },
                    modifier = Modifier.size(48.dp),
                    onClick = toggleArtistSubscription,
                )
                LiquidGlassIconButton(
                    backdrop = artworkBackdrop,
                    painter = painterResource(R.drawable.solar_more_circle_linear),
                    contentDescription = stringResource(R.string.more_options),
                    modifier = Modifier.size(48.dp),
                    onClick = showArtistOverflowMenu,
                )
            }
        }
    }

    if (!liquidGlassHeaderActive) {
    TopAppBar(
        windowInsets =
            WindowInsets(top = systemBarsTopPadding)
                .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
        title = {
            val animatedAlpha by animateFloatAsState(
                targetValue = if (!transparentAppBar) 1f else 0f,
                animationSpec = tween(200),
                label = "titleAlpha",
            )
            Text(
                text = artistPage?.artist?.title ?: libraryArtist?.artist?.name ?: "",
                modifier = Modifier.alpha(animatedAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        actions = {
            IconButton(
                onClick = showArtistOverflowMenu,
                onLongClick = {},
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_horiz),
                    contentDescription = stringResource(R.string.more_options),
                )
            }
        },
        colors =
            if (transparentAppBar) {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                )
            } else {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                )
            },
    )
    }
}

@Composable
private fun ArtistOverflowMenu(
    isBlocked: Boolean,
    blockActionEnabled: Boolean,
    onAction: (ArtistAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
    ) {
        // Share / Copy link were removed from the artist overflow menu on
        // request — sharing stays available through the hero Share button.
        ArtistOverflowMenuItem(
            text = stringResource(if (isBlocked) R.string.unblock_artist else R.string.block_artist),
            iconRes = R.drawable.block,
            enabled = blockActionEnabled,
            onClick = { onAction(ArtistAction.ToggleBlock) },
        )
    }
}

@Composable
private fun ArtistOverflowMenuItem(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    ListItem(
        headlineContent = {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
        },
        leadingContent = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        tonalElevation = 0.dp,
    )
}

/** A translucent circular icon button pinned over the hero artwork. */
@Composable
private fun ArtistHeroTranslucentCircle(
    iconRes: Int,
    contentDescription: String?,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.26f))
                .let { m ->
                    if (enabled) {
                        m.clickable(onClick = onClick)
                    } else {
                        m.alpha(0.4f)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint ?: contentColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Related-artist tile: large circular artwork with the name underneath — the
 * reference treatment for "Fans might also like".
 */
@Composable
private fun ArtistCircleItem(
    item: ArtistItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artworkUrl = item.thumbnail
    Column(
        modifier =
            modifier
                .widthIn(max = ArtistCircleItemWidth)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model =
                    artworkUrl.resize(
                        width = ArtistCircleArtworkSizePx,
                        height = ArtistCircleArtworkSizePx,
                        ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                    ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(ArtistCircleArtworkSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(ArtistCircleArtworkSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .fillMaxWidth(),
        )
    }
}

/**
 * The About / artist-description section, anchored at the bottom of the page.
 * Expanding plays a fluid spring animation rather than snapping open.
 */
@Composable
private fun ArtistAboutSection(
    description: String,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable(description) { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = ArtistContentMaxWidth)
                .padding(horizontal = ArtistHorizontalPadding, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.about),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // animateContentSize with a spring: the text block grows and shrinks
        // fluidly when the collapsed/expanded line count flips.
        Box(
            modifier =
                Modifier.animateContentSize(
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                ),
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { isExpanded = !isExpanded },
                            onLongClick = {},
                        ),
            )
        }
        Text(
            text = stringResource(if (isExpanded) R.string.artist_show_less else R.string.more),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .clickable { isExpanded = !isExpanded },
        )
    }
}

@Composable
private fun ArtistNewReleaseSection(
    release: ArtistReleaseUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val releaseTypeLabel =
        stringResource(
            when (release.releaseType) {
                AlbumReleaseType.ALBUM -> R.string.release_type_album
                AlbumReleaseType.SINGLE -> R.string.release_type_single
                AlbumReleaseType.EP -> R.string.ep
            },
        )
    val metadata =
        release.year?.let { year ->
            stringResource(R.string.release_metadata, releaseTypeLabel, year)
        } ?: releaseTypeLabel

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = ArtistContentMaxWidth),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (release.thumbnailUrl != null) {
                    AsyncImage(
                        model =
                            release.thumbnailUrl.resize(
                                width = ArtistReleaseArtworkSizePx,
                                height = ArtistReleaseArtworkSizePx,
                                ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                            ),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(ArtistReleaseArtworkSize)
                                .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(ArtistReleaseArtworkSize)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.album),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.latest_release).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = release.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The Pre-save & Release Countdown column: the artist's upcoming catalogue
 * entries, rendered in the same card design as the latest-release pill but
 * labelled "UPCOMING RELEASE" and carrying a live countdown to the announced
 * release moment — plus a Listen Later bookmark that persists into the
 * release presave store.
 */
@Composable
private fun ArtistUpcomingReleasesColumn(
    releases: List<UpcomingRelease>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val (presaveRaw, _) = rememberPreference(ReleasePresaveKey, "")
    val presaved = remember(presaveRaw) { parsePresavedReleases(presaveRaw) }

    // A minute-resolution tick keeps every countdown honest without waking the
    // CPU more than a music app already does.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(releases) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        releases.forEach { release ->
            val isSaved = presaved.any { it.releaseId == release.releaseId }
            Card(
                shape = MaterialTheme.shapes.large,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = ArtistContentMaxWidth),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (release.thumbnailUrl != null) {
                        AsyncImage(
                            model = release.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(ArtistReleaseArtworkSize)
                                    .clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(ArtistReleaseArtworkSize)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.album),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.upcoming_release).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = release.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The catalogue-reported artist name: with the strict
                        // identity checks most entries now match the page's
                        // artist, and when one does not it is obvious instead
                        // of silently wrong.
                        if (release.artistName.isNotBlank()) {
                            Text(
                                text = release.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = upcomingReleaseCountdownText(release, nowMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Listen Later: persists the release into the presave store
                    // so it is already waiting in the library surfaces when it
                    // officially drops.
                    androidx.compose.material3.IconButton(
                        onClick = {
                            val mapped =
                                PresavedRelease(
                                    releaseId = release.releaseId,
                                    title = release.title,
                                    artistName = release.artistName,
                                    releaseType = release.releaseType,
                                    releaseAtMillis = release.releaseAtMillis,
                                    thumbnailUrl = release.thumbnailUrl.orEmpty(),
                                )
                            coroutineScope.launch {
                                context.setPresavedReleases(
                                    togglePresavedRelease(presaved, mapped),
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (isSaved) {
                                        R.drawable.solar_bookmark_bold
                                    } else {
                                        R.drawable.solar_bookmark_linear
                                    },
                                ),
                            contentDescription =
                                stringResource(
                                    if (isSaved) R.string.presave_saved else R.string.presave_save,
                                ),
                            tint =
                                if (isSaved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "3 d 4 h left" / "5 h 12 m left" / "42 m left" — the moment-to-release. */
@Composable
private fun upcomingReleaseCountdownText(
    release: UpcomingRelease,
    nowMillis: Long,
): String {
    val leftMillis = release.releaseAtMillis - nowMillis
    val resource =
        when {
            leftMillis <= 0L -> R.string.release_countdown_imminent
            leftMillis >= 24L * 60 * 60 * 1000 ->
                R.string.release_countdown_days
            leftMillis >= 60L * 60 * 1000 ->
                R.string.release_countdown_hours
            else -> R.string.release_countdown_minutes
        }
    return when (resource) {
        R.string.release_countdown_days -> {
            val days = (leftMillis / (24L * 60 * 60 * 1000)).toInt()
            val hours = ((leftMillis % (24L * 60 * 60 * 1000)) / (60L * 60 * 1000)).toInt()
            stringResource(resource, days, hours)
        }
        R.string.release_countdown_hours -> {
            val hours = (leftMillis / (60L * 60 * 1000)).toInt()
            val minutes = ((leftMillis % (60L * 60 * 1000)) / 60_000L).toInt()
            stringResource(resource, hours, minutes)
        }
        R.string.release_countdown_minutes -> {
            val minutes = (leftMillis / 60_000L).coerceAtLeast(1L).toInt()
            stringResource(resource, minutes)
        }
        else -> stringResource(resource)
    }
}

// ---- Ambient background helpers ------------------------------------------

/**
 * Extracts a 2-3 stop ambient palette from an artwork URL, off the main
 * thread, cached in [PlayerPaletteCache] so revisiting a page never
 * recomputes.
 */
private suspend fun extractAmbientArtworkColors(
    context: Context,
    mediaId: String,
    artworkUrl: String?,
    darkTheme: Boolean,
): List<Color>? {
    if (artworkUrl.isNullOrBlank()) return null
    val cacheKey =
        PlayerPaletteCacheKey(
            mediaId = mediaId,
            provider = guessArtworkProvider(artworkUrl),
            artworkIdentity = artworkUrl,
            backgroundMode = ARTIST_AMBIENT_BACKGROUND_MODE,
            darkTheme = darkTheme,
        )
    PlayerPaletteCache.get(cacheKey)?.let { return it }

    val request =
        ImageRequest
            .Builder(context)
            .data(artworkUrl)
            .memoryCacheKey(artworkUrl)
            .diskCacheKey(artworkUrl)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .size(AMBIENT_EXTRACT_SIZE_PX, AMBIENT_EXTRACT_SIZE_PX)
            .allowHardware(false)
            .build()
    val result =
        try {
            withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        } ?: return null
    if (result !is SuccessResult) return null
    val bitmap = result.image?.toBitmap() ?: return null

    val palette =
        withContext(Dispatchers.Default) {
            Palette
                .from(bitmap)
                .maximumColorCount(24)
                .resizeBitmapArea(2000)
                .generate()
        }
    val gradient =
        PlayerColorExtractor.extractGradientColors(
            palette = palette,
            fallbackColor = if (darkTheme) 0xFF15151A.toInt() else 0xFFF3F3F6.toInt(),
        )
    val stops =
        listOfNotNull(
            gradient.firstOrNull(),
            gradient.getOrNull(gradient.size / 2),
            gradient.lastOrNull(),
        ).distinct()
            .take(3)
            .ifEmpty { listOf(Color(if (darkTheme) 0xFF15151A.toInt() else 0xFFF3F3F6.toInt())) }
    PlayerPaletteCache.put(cacheKey, stops)
    return stops
}

private const val ARTIST_AMBIENT_BACKGROUND_MODE = "ARTIST_AMBIENT"
private const val AMBIENT_EXTRACT_SIZE_PX = 64
private const val CANVAS_SAMPLE_INTERVAL_MILLIS = 1100L
private const val CANVAS_RECORD_INTERVAL_MILLIS = 220L

private const val ArtistHeroArtworkSizePx = 1200
private const val ArtistReleaseArtworkSizePx = 320
private const val ArtistCircleArtworkSizePx = 288
private val ArtistHeroArtworkSizeBuckets = listOf(ArtistHeroArtworkSizePx)
private val ArtistHeroMinHeight = 560.dp
private val ArtistHorizontalPadding = 24.dp
private val ArtistContentMaxWidth = 720.dp
private val ArtistReleaseArtworkSize = 112.dp
private val ArtistCircleArtworkSize = 124.dp
private val ArtistCircleItemWidth = 136.dp
private const val ArtistStatSeparator = "  •  "

@Immutable
private data class ArtistStatsUi(
    val audience: String,
    val catalog: String,
)

@Immutable
private data class ArtistReleaseUiModel(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val year: Int?,
    val releaseType: AlbumReleaseType,
)

private fun buildArtistStats(
    showLocal: Boolean,
    artistPage: ArtistPage?,
    librarySongCount: Int,
    libraryAlbumCount: Int,
    songsLabel: String,
    albumsLabel: String,
    monthlyListenersLabel: String,
    subscribersLabel: String,
): ArtistStatsUi {
    val songSections =
        artistPage?.sections?.filter { section ->
            section.items.any { it is SongItem }
        }
    val songCount =
        if (showLocal) {
            librarySongCount
        } else {
            songSections
                ?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.filterIsInstance<SongItem>()
                ?.distinctBy { it.id }
                ?.count() ?: librarySongCount
        }
    val hasMoreSongs = !showLocal && songSections?.any { it.moreEndpoint != null } == true

    val albumSections =
        artistPage?.sections?.filter { section ->
            section.items.any { it is AlbumItem }
        }
    val albumCount =
        if (showLocal) {
            libraryAlbumCount
        } else {
            albumSections
                ?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.filterIsInstance<AlbumItem>()
                ?.distinctBy { it.id }
                ?.count() ?: libraryAlbumCount
        }
    val hasMoreAlbums = !showLocal && albumSections?.any { it.moreEndpoint != null } == true

    val audience =
        buildList {
            artistPage?.artist?.monthlyListenerCountText?.toArtistCompactCountText()?.let { value ->
                add("$value $monthlyListenersLabel")
            }

            artistPage?.artist?.subscriberCountText?.toArtistCompactCountText()?.let { value ->
                add("$value $subscribersLabel")
            }
        }.joinToString(ArtistStatSeparator)
    val catalog =
        buildList {
            if (songCount > 0) {
                val value = compactCountText(songCount, hasMoreSongs)
                add("$value $songsLabel")
            }

            if (albumCount > 0) {
                val value = compactCountText(albumCount, hasMoreAlbums)
                add("$value $albumsLabel")
            }
        }.joinToString(ArtistStatSeparator)

    return ArtistStatsUi(
        audience = audience,
        catalog = catalog,
    )
}

private fun AlbumItem.toArtistReleaseUiModel() =
    ArtistReleaseUiModel(
        id = id,
        title = title,
        thumbnailUrl = thumbnail,
        year = year,
        releaseType = releaseType,
    )

private fun compactCountText(
    count: Int,
    hasMore: Boolean,
): String {
    val value = formatCompactCount(count.toLong())
    return if (hasMore) "$value+" else value
}

private val CompactArtistCountPattern = Regex("""\d+(?:[.,]\d+)?[\s\u00A0\u202F\u2007]*[KMB]""", RegexOption.IGNORE_CASE)
private val ArtistCountPattern = Regex("""\d+(?:[.,]\d+)*""")

private fun String.toArtistCompactCountText(): String? {
    val compactText = CompactArtistCountPattern.find(this)?.value
    if (compactText != null) {
        return compactText
            .filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' || it == '\u2007' }
            .replace(',', '.')
            .uppercase(Locale.US)
    }

    val count =
        ArtistCountPattern
            .find(this)
            ?.value
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?: return null

    return formatCompactCount(count)
}

private fun buildArtistItemsRoute(
    artistId: String,
    endpoint: BrowseEndpoint,
    sectionTitle: String? = null,
): String {
    val encodedArtistId = Uri.encode(artistId)
    val encodedBrowseId = Uri.encode(endpoint.browseId)
    val encodedParams =
        endpoint.params
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.encode(it) }
    val encodedTitle =
        sectionTitle
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.encode(it) }

    return buildString {
        append("artist/")
        append(encodedArtistId)
        append("/items?browseId=")
        append(encodedBrowseId)
        if (encodedParams != null) {
            append("&params=")
            append(encodedParams)
        }
        // The section's own title rides the route so the glass header pill
        // shows it immediately — without it the pill rendered back-arrow-only
        // while the network fetch ran (and forever, if it failed), because the
        // screen's title previously existed ONLY inside the fetched page.
        if (encodedTitle != null) {
            append("&title=")
            append(encodedTitle)
        }
    }
}
