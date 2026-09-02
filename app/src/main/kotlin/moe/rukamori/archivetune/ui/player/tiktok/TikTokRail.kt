/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — the action rail.
 *
 * The vertical stack of circular actions riding the right edge of the media,
 * in TikTok's order: the artist's avatar (with the small follow button the
 * app's existing subscribe feature backs), like, comment (here: lyrics),
 * bookmark (here: add to playlist), download, share, more. Every action
 * operates on THIS page's song — the feed's pages are real queue entries, so
 * the rail acts straight on the same Room rows, download manager and menus
 * the rest of the app uses.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalSyncUtils
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.utils.shareLocalAudio

/** TikTok's brand red, the same one the reference feed uses for its active heart. */
internal val TIKTOK_RED = Color(0xFFFE2C55)

@Composable
internal fun TikTokRail(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    playerConnection: PlayerConnection,
    sheetState: BottomSheetState,
    onOpenLyrics: () -> Unit,
    onAddToPlaylist: () -> Unit,
    navController: NavController,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val syncUtils = LocalSyncUtils.current
    val scope = rememberCoroutineScope()

    // This page's song row — liked state and format come from the same Room
    // row the rest of the app reads; there is no separate feed-side state.
    val librarySong by database.song(pageMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val isLocal = librarySong?.song?.isLocal == true
    val download by downloadUtil.getDownload(pageMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .padding(end = 10.dp)
                .padding(vertical = 6.dp),
    ) {
        // ── The artist's avatar (TikTok's profile picture) ──
        // Tap opens the artist page (the app's real destination, the player
        // collapsing first exactly like other in-player links); the small
        // badge on its rim is the app's existing subscribe feature.
        TikTokArtistAvatar(
            pageMetadata = pageMetadata,
            sheetState = sheetState,
            navController = navController,
        )

        Spacer(Modifier.height(18.dp))

        // ── Like ──
        // The heart acts on THIS page's song, not on whatever is playing:
        // when the row exists the toggle is the same per-song one the song
        // menu uses (Room update + sync); a song that isn't in the library
        // yet falls back to the service's current-song toggle (which also
        // handles inserting it) when this page is the playing one, and to
        // register-then-like otherwise.
        val liked = librarySong?.song?.liked == true
        TikTokRailButton(
            iconRes = if (liked) R.drawable.solar_heart_bold else R.drawable.solar_heart_linear,
            contentDescription = stringResource(R.string.action_like),
            tint = if (liked) TIKTOK_RED else Color.White,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val row = librarySong?.song
            when {
                row != null -> {
                    val s = row.toggleLike()
                    database.query { update(s) }
                    syncUtils.likeSong(s)
                }

                isCurrentPage -> playerConnection.toggleLike()

                else -> {
                    database.transaction { insert(pageMetadata) }
                    scope.launch {
                        val entity = database.song(pageMetadata.id).first()?.song ?: return@launch
                        val s = entity.toggleLike()
                        database.query { update(s) }
                        syncUtils.likeSong(s)
                    }
                }
            }
        }

        // ── Lyrics (TikTok's comment bubble) ──
        TikTokRailButton(
            iconRes = R.drawable.solar_chat_round_linear,
            contentDescription = stringResource(R.string.lyrics),
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenLyrics()
        }

        // ── Add to playlist (TikTok's bookmark) ──
        TikTokRailButton(
            iconRes = R.drawable.solar_bookmark_linear,
            contentDescription = stringResource(R.string.add_to_playlist),
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onAddToPlaylist()
        }

        // ── Download / offline ──
        // Hidden for local files: they are already on the device. The icon
        // carries the state TikTok's bookmark does — a spinner while the
        // download runs, the check mark once the song is fully downloaded
        // (tap then removes it, same as the player menu's row).
        if (!isLocal) {
            when (download?.state) {
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                    val downloadingLabel = stringResource(R.string.action_download)
                    TikTokRailActionButton(
                        onClick = {
                            // A tap while a download is in flight cancels it,
                            // the same removal the player menu's row performs.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                pageMetadata.id,
                                false,
                            )
                        },
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier =
                                Modifier
                                    .size(26.dp)
                                    .semantics { contentDescription = downloadingLabel },
                        )
                    }
                }

                Download.STATE_COMPLETED -> {
                    TikTokRailButton(
                        iconRes = R.drawable.solar_check_circle_linear,
                        contentDescription = stringResource(R.string.filter_downloaded),
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        DownloadService.sendRemoveDownload(
                            context,
                            ExoDownloadService::class.java,
                            pageMetadata.id,
                            false,
                        )
                    }
                }

                else -> {
                    TikTokRailButton(
                        iconRes = R.drawable.solar_download_linear,
                        contentDescription = stringResource(R.string.action_download),
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // Same cache-first flow as the player menu: register the
                        // song, prewarm the resolved stream into the player cache,
                        // then hand the request to the download service.
                        database.transaction { insert(pageMetadata) }
                        scope.launch {
                            runCatching { downloadUtil.prewarmSongForDownload(pageMetadata.id) }
                            val request =
                                DownloadRequest
                                    .Builder(pageMetadata.id, pageMetadata.id.toUri())
                                    .setCustomCacheKey(pageMetadata.id)
                                    .setData(pageMetadata.title.toByteArray())
                                    .build()
                            DownloadService.sendAddDownload(
                                context,
                                ExoDownloadService::class.java,
                                request,
                                false,
                            )
                        }
                    }
                }
            }
        }

        // ── Share ──
        TikTokRailButton(
            iconRes = R.drawable.solar_share_linear,
            contentDescription = stringResource(R.string.share),
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (isLocal) {
                val mimeType = librarySong?.format?.mimeType
                shareLocalAudio(context, pageMetadata.id, mimeType)
            } else {
                val url = "https://music.youtube.com/watch?v=${pageMetadata.id}"
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                        putExtra(Intent.EXTRA_TITLE, pageMetadata.title)
                    }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }

        // ── More ──
        TikTokRailButton(
            iconRes = R.drawable.solar_more_vert_linear,
            contentDescription = stringResource(R.string.more),
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            menuState.show {
                PlayerMenu(
                    mediaMetadata = pageMetadata,
                    navController = navController,
                    playerBottomSheetState = sheetState,
                    onShowDetailsDialog = {
                        bottomSheetPageState.show { ShowMediaInfo(pageMetadata.id) }
                    },
                    onDismiss = menuState::dismiss,
                )
            }
        }
    }
}

/**
 * The artist's avatar at the top of the rail, TikTok-style: a circular photo
 * with a light rim, and the small follow badge at its lower-right corner.
 * The badge is backed by the app's existing subscribe feature — the same
 * ArtistEntity toggle (and YouTube channel subscription) the artist page
 * uses; the avatar itself opens the artist page.
 */
@Composable
private fun TikTokArtistAvatar(
    pageMetadata: MediaMetadata,
    sheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val haptics = LocalHapticFeedback.current

    val artist = remember(pageMetadata.id) { pageMetadata.artists.firstOrNull() }
    val artistId = artist?.id
    // The artist's own photo when the metadata carries one, else the song
    // artwork — a feed page should never show an empty circle.
    val avatarUrl = artist?.thumbnailUrl ?: pageMetadata.thumbnailUrl

    // Subscribe state from the same Room row the artist page reads.
    val artistFlow =
        remember(artistId, database) {
            if (artistId != null) {
                database.artist(artistId)
            } else {
                flowOf<moe.rukamori.archivetune.db.entities.Artist?>(null)
            }
        }
    val libraryArtist by artistFlow.collectAsStateWithLifecycle(initialValue = null)
    val isSubscribed = libraryArtist?.artist?.bookmarkedAt != null

    Box(
        modifier =
            modifier
                .size(50.dp)
                .tiktokNoRippleClickable(
                    enabled = artistId != null,
                ) {
                    if (artistId == null) return@tiktokNoRippleClickable
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    sheetState.collapseSoft()
                    navController.navigate("artist/$artistId") {
                        launchSingleTop = true
                    }
                },
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = artist?.name,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.95f), CircleShape)
                    .shadow(elevation = 4.dp, shape = CircleShape, clip = false),
        )

        // The follow badge — only while NOT subscribed, like the reference's
        // red "+" that disappears once the follow lands. Tapping it subscribes
        // through the artist page's own toggle (Room + YouTube channel).
        if (!isSubscribed) {
            val subscribeLabel = stringResource(R.string.subscribe)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(20.dp)
                        .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(TIKTOK_RED)
                        .semantics { contentDescription = subscribeLabel }
                        .tiktokNoRippleClickable(enabled = artistId != null) {
                            if (artistId == null) return@tiktokNoRippleClickable
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val existing = libraryArtist?.artist
                            if (existing != null) {
                                database.query { update(existing.toggleLike()) }
                            } else if (artist != null) {
                                database.transaction {
                                    insert(
                                        ArtistEntity(
                                            id = artistId,
                                            name = artist.name,
                                            thumbnailUrl = artist.thumbnailUrl,
                                        ).toggleLike(),
                                    )
                                }
                            }
                        },
            ) {
                // The white plus, drawn rather than tinted so the badge reads
                // as one solid TikTok-red dot.
                Canvas(modifier = Modifier.size(10.dp)) {
                    val stroke = 1.8.dp.toPx()
                    val half = stroke / 2f
                    drawLine(
                        color = Color.White,
                        start = Offset(half, size.height / 2f),
                        end = Offset(size.width - half, size.height / 2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width / 2f, half),
                        end = Offset(size.width / 2f, size.height - half),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * One rail action: a plain white glyph with a soft round shadow — TikTok's
 * rail has no pills, no glass, nothing but the icon and its shadow over the
 * media. The 48dp touch target meets accessibility guidance.
 */
@Composable
private fun TikTokRailButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    TikTokRailActionButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier =
                Modifier
                    .size(30.dp)
                    .shadow(elevation = 4.dp, shape = CircleShape, clip = false),
        )
    }
}

/** A rail action with arbitrary content (the download spinner). */
@Composable
private fun TikTokRailActionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .tiktokNoRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
    Spacer(Modifier.height(2.dp))
}
