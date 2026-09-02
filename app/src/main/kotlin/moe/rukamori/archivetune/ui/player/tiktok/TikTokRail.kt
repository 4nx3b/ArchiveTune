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
 * in TikTok's order: like, comment (here: lyrics), bookmark (here: add to
 * playlist), download, share, more. Every action operates on THIS page's
 * song — the feed's pages are real queue entries, so the rail acts straight
 * on the same Room rows, download manager and menus the rest of the app uses.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalSyncUtils
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
        // ── Like ──
        // The heart acts on THIS page's song, not on whatever is playing:
        // when the row exists the toggle is the same per-song one the song
        // menu uses (Room update + sync); a song that isn't in the library
        // yet falls back to the service's current-song toggle (which also
        // handles inserting it) when this page is the playing one, and to
        // register-then-like otherwise.
        TikTokRailButton(
            icon = if (librarySong?.song?.liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = "Like",
            tint = if (librarySong?.song?.liked == true) TIKTOK_RED else Color.White,
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
                        val entity =
                            database.song(pageMetadata.id).first()?.song ?: return@launch
                        val s = entity.toggleLike()
                        database.query { update(s) }
                        syncUtils.likeSong(s)
                    }
                }
            }
        }

        // ── Lyrics (TikTok's comment bubble) ──
        TikTokRailButton(
            icon = Icons.Rounded.ChatBubbleOutline,
            contentDescription = "Lyrics",
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onOpenLyrics()
        }

        // ── Add to playlist (TikTok's bookmark) ──
        TikTokRailButton(
            icon = Icons.Rounded.BookmarkBorder,
            contentDescription = "Add to playlist",
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onAddToPlaylist()
        }

        // ── Download / offline ──
        // Hidden for local files: they are already on the device. The icon
        // carries the state TikTok's bookmark does — filled once the song is
        // fully downloaded.
        if (!isLocal) {
            val downloaded = download?.state == Download.STATE_COMPLETED
            TikTokRailButton(
                icon = if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                contentDescription = if (downloaded) "Remove download" else "Download",
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (downloaded) {
                    DownloadService.sendRemoveDownload(
                        context,
                        ExoDownloadService::class.java,
                        pageMetadata.id,
                        false,
                    )
                } else {
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

        // ── Share ──
        TikTokRailButton(
            icon = Icons.Rounded.Share,
            contentDescription = "Share",
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
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = "More",
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
 * One rail action: a plain white glyph with a soft round shadow — TikTok's
 * rail has no pills, no glass, nothing but the icon and its shadow over the
 * media. The 48dp touch target meets accessibility guidance.
 */
@Composable
private fun TikTokRailButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier =
                Modifier
                    .size(30.dp)
                    .shadow(elevation = 4.dp, shape = CircleShape, clip = false),
        )
    }
    Spacer(Modifier.height(2.dp))
}
