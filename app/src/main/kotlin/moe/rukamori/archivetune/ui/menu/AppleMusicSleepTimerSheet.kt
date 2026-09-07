/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.SleepTimer
import moe.rukamori.archivetune.utils.makeTimeString
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AppleMusicSleepTimerSheet(
    sleepTimer: SleepTimer,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {

    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepTimer, sleepTimer.isActive) {
        while (isActive) {
            remainingMs =
                when {
                    sleepTimer.pauseWhenSongEnd -> -1L
                    sleepTimer.triggerTime > 0 -> sleepTimer.triggerTime - System.currentTimeMillis()
                    else -> 0L
                }
            delay(500L)
        }
    }

    val isEndOfSong = sleepTimer.pauseWhenSongEnd
    val isTimed = sleepTimer.triggerTime > 0 && !isEndOfSong
    val isActive = sleepTimer.isActive

    val activeTimerMinutes =
        if (isTimed && remainingMs > 0) {
            ((remainingMs + 30_000L) / 60_000L).toInt().coerceIn(1, 120)
        } else {
            0
        }
    var sliderMinutes by remember { mutableFloatStateOf(activeTimerMinutes.toFloat()) }

    LaunchedEffect(activeTimerMinutes, isActive) {
        if (isActive) {
            sliderMinutes = activeTimerMinutes.toFloat()
        } else if (sliderMinutes > 0 && !isActive) {

            sliderMinutes = 0f
        }
    }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.bedtime),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sleep_timer),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val statusText =
                        when {
                            isEndOfSong -> stringResource(R.string.sleep_timer_end_of_song)
                            isTimed && remainingMs > 0 ->
                                stringResource(
                                    R.string.sleep_timer_remaining,
                                    makeTimeString(remainingMs),
                                )
                            else -> stringResource(R.string.sleep_timer_pick_a_time)
                        }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val presets =
                remember {
                    listOf(5, 10, 15, 20, 30, 45, 60, 90)
                }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                presets.forEach { minutes ->
                    val selected =
                        isTimed &&
                            abs(
                                (sleepTimer.triggerTime - System.currentTimeMillis()) -
                                    minutes.toLong() * 60_000L,
                            ) < 30_000L
                    FilterChip(
                        selected = selected,
                        onClick = {
                            sleepTimer.start(minutes)
                            onDismiss()
                        },
                        label = { Text("${minutes}m") },
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    )
                }

                FilterChip(
                    selected = isEndOfSong,
                    onClick = {
                        sleepTimer.start(-1)
                        onDismiss()
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(stringResource(R.string.sleep_timer_end_of_song)) },
                    shape = RoundedCornerShape(20.dp),
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }

            Spacer(Modifier.height(20.dp))

            val sliderValue = sliderMinutes.coerceIn(0f, 120f)
            val sliderEnabled = true
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.sleep_timer_custom),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = formatMinutes(sliderValue.roundToInt().coerceAtLeast(0)),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderMinutes = it },
                    onValueChangeFinished = {
                        val minutes = sliderValue.roundToInt()
                        if (minutes > 0) {
                            sleepTimer.start(minutes)
                            onDismiss()
                        }
                    },
                    valueRange = 0f..120f,
                    steps = 119,
                    enabled = sliderEnabled,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "0m",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "120m",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Spacer(Modifier.height(12.dp))
            }
            AnimatedVisibility(visible = isActive) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable {
                                sleepTimer.clear()
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sleep_timer_cancel_timer),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String =
    when {
        minutes <= 0 -> "Off"
        minutes < 60 -> "${minutes}m"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "${h}h" else "${h}h ${m}m"
        }
    }
