/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Ported from Sekai Tune (github.com/rgsekai/sekai-tune) ambient mode — GPLv3,
 * adapted to ArchiveTune's player connection (no canvas/lyrics embeds: this
 * port ships the ambient glow visual core with playback controls).
 */

package moe.rukamori.archivetune.ui.screens.ambient

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.abs

@Composable
fun AmbientModeScreen(navController: NavController) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    val player = playerConnection.player
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player, mediaMetadata?.id) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation =
            activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val window = activity?.window
        var windowInsetsController: WindowInsetsControllerCompat? = null
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = originalOrientation
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler { navController.popBackStack() }

    var swipeAccumX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF050505))
                .pointerInput(playerConnection, enableHapticFeedback) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = 90.dp.toPx()
                            if (abs(swipeAccumX) > threshold) {
                                if (enableHapticFeedback) {
                                    (context as? Activity)?.window?.decorView?.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                    )
                                }
                                if (swipeAccumX > 0) {
                                    playerConnection.player.seekToPrevious()
                                } else {
                                    playerConnection.player.seekToNext()
                                }
                            }
                            swipeAccumX = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        swipeAccumX += dragAmount
                    }
                },
    ) {
        AmbientGlowBackground(
            mediaMetadata = mediaMetadata,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            mediaMetadata?.let { metadata ->
                AsyncImage(
                    model = metadata.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxHeight(0.72f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp)),
                )

                Column(
                    modifier = Modifier.fillMaxHeight(0.72f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = metadata.title,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = metadata.artists.joinToString { it.name },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    Slider(
                        value =
                            if (duration > 0) {
                                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                        onValueChange = { fraction ->
                            if (duration > 0) {
                                player.seekTo((fraction * duration).toLong())
                            }
                        },
                        colors =
                            SliderDefaults.colors(
                                thumbColor = Color.White.copy(alpha = 0.85f),
                                activeTrackColor = Color.White.copy(alpha = 0.8f),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatAmbientTime(position),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 14.sp,
                        )
                        Text(
                            text = formatAmbientTime(duration),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 14.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        IconButton(onClick = { playerConnection.player.seekToPrevious() }) {
                            Icon(
                                painter = painterResource(R.drawable.skip_previous),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        IconButton(onClick = {
                            if (playerConnection.player.isPlaying) {
                                playerConnection.player.pause()
                            } else {
                                playerConnection.player.play()
                            }
                        }) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (isPlaying) R.drawable.pause else R.drawable.play,
                                    ),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                        IconButton(onClick = { playerConnection.player.seekToNext() }) {
                            Icon(
                                painter = painterResource(R.drawable.skip_next),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.ambient_mode_hint),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
        )
    }
}

private fun formatAmbientTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
