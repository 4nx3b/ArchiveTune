/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.LocalPlayerConnection
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LyricsV2
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.utils.rememberEnumPreference

val LocalLyricsScrollListener = compositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun NumberedPlayerInlineLyrics(
    visible: Boolean,
    onClose: () -> Unit,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    mediaMetadata: MediaMetadata,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val lyricsMode by rememberEnumPreference(LyricsModeKey, defaultValue = LyricsMode.ENHANCED)
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    var showOverflowMenu by remember { mutableStateOf(false) }
    var overflowAnchor by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(visible) {
        if (!visible) showOverflowMenu = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (lyricsMode) {
                LyricsMode.V2 ->
                    LyricsV2(
                        sliderPositionProvider = sliderPositionProvider,
                        lyricsSyncOffset = lyricsSyncOffset,
                        textColorOverride = textColor,
                        modifier = Modifier.fillMaxSize(),
                    )

                LyricsMode.ENHANCED ->
                    LyricsEnhanced(
                        sliderPositionProvider = sliderPositionProvider,
                        lyricsSyncOffset = lyricsSyncOffset,
                        textColorOverride = textColor,
                        modifier = Modifier.fillMaxSize(),
                    )

                LyricsMode.SPOTIFY ->
                    LyricsV2(
                        sliderPositionProvider = sliderPositionProvider,
                        lyricsSyncOffset = lyricsSyncOffset,
                        textColorOverride = textColor,
                        spotifyStyle = true,
                        modifier = Modifier.fillMaxSize(),
                    )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.player_close),
                        contentDescription = stringResource(R.string.close),
                        tint = textColor ?: Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = { showOverflowMenu = true },
                    modifier =
                        Modifier.onGloballyPositioned { overflowAnchor = it.boundsInRoot() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_horiz),
                        contentDescription = stringResource(R.string.more),
                        tint = textColor ?: Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

    if (visible && showOverflowMenu) {
        AnchoredLyricsOverflowMenu(
            iconBoundsInRoot = overflowAnchor,
            lyricsProvider = { currentLyrics },
            mediaMetadataProvider = { mediaMetadata },
            lyricsSyncOffset = lyricsSyncOffset,
            onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
            onDismiss = { showOverflowMenu = false },
        )
    }
}
