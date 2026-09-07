/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

@Composable
fun LibraryBackPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    FrostedHeaderPill(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {

            IconButton(
                onClick = onClick,
                onLongClick = onLongClick ?: {},
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.library),
                )
            }

            Text(
                text = stringResource(R.string.library),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
fun LibraryHomeDockButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.home_filled,
    backdrop: PlatformBackdrop? = null,
) {
    if (backdrop != null) {

        Box(
            modifier =
                modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = CircleShape,
                        interactive = false,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClick,
                onLongClick = {},
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = stringResource(R.string.home),
                    tint = AppleMusicStyleAccentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    } else {

        val baseColor = MaterialTheme.colorScheme.surfaceContainer
        Surface(
            modifier =
                modifier
                    .size(48.dp)
                    .clip(CircleShape),
            shape = CircleShape,
            color = baseColor.copy(alpha = 0.55f),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {

                IconButton(
                    onClick = onClick,
                    onLongClick = {},
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = stringResource(R.string.home),
                        tint = AppleMusicStyleAccentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun BottomFadeOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    fadeColor: Color = MaterialTheme.colorScheme.surface,
    height: Dp = 96.dp,
) {
    if (!visible) return
    Box(
        modifier =
            modifier
                .height(height)
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    fadeColor.copy(alpha = 0f),
                                    fadeColor.copy(alpha = 0.6f),
                                    fadeColor,
                                ),
                        ),
                ),
    )
}
