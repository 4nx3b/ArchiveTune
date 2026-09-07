/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AppleMusicStyleAccentColor: Color = Color(0xFFFF375C)

@Composable
fun AppleMusicPlaylistHero(
    sectionLabel: String?,
    title: String,
    subtitle: String?,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onPrimaryTrailing: (() -> Unit)? = null,
    @DrawableRes primaryTrailingIcon: Int? = null,
    @StringRes primaryTrailingDescription: Int? = null,
    additionalActions: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accent = AppleMusicStyleAccentColor

    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        sectionLabel?.let { label ->
            Text(
                text = label.uppercase(),
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.08.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Text(
            text = title,
            color = onBackgroundColor,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        subtitle?.let {
            Text(
                text = it,
                color = subtitleColor,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        val hasActions = onPlay != null || onShuffle != null ||
            onPrimaryTrailing != null || additionalActions != null
        if (hasActions) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                onPlay?.let { play ->
                    PillActionButton(
                        text = stringResource(moe.rukamori.archivetune.R.string.play),
                        icon = moe.rukamori.archivetune.R.drawable.play,
                        accent = accent,
                        primary = true,
                        onClick = play,
                        modifier = Modifier.weight(1f),
                    )
                }
                onShuffle?.let { shuffle ->
                    PillActionButton(
                        text = stringResource(moe.rukamori.archivetune.R.string.shuffle),
                        icon = moe.rukamori.archivetune.R.drawable.shuffle,
                        accent = accent,
                        primary = false,
                        onClick = shuffle,
                        modifier = Modifier.weight(1f),
                    )
                }
                additionalActions?.invoke()
                if (onPrimaryTrailing != null && primaryTrailingIcon != null) {
                    TrailingIconButton(
                        icon = primaryTrailingIcon,
                        description = primaryTrailingDescription,
                        onClick = onPrimaryTrailing,
                        accent = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun PillActionButton(
    text: String,
    @DrawableRes icon: Int,
    accent: Color,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {

    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val containerColor = onBackgroundColor.copy(alpha = if (primary) 0.10f else 0.06f)
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .height(46.dp),
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxHeight()

                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrailingIconButton(
    @DrawableRes icon: Int,
    @StringRes description: Int?,
    onClick: () -> Unit,
    accent: Color,
) {
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val containerColor = onBackgroundColor.copy(alpha = 0.06f)
    Surface(
        modifier =
            Modifier
                .size(46.dp)
                .clip(CircleShape),
        shape = CircleShape,
        color = containerColor,
    ) {

        Box(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.IconButton(onClick = onClick) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = description?.let { stringResource(it) },
                    tint = onBackgroundColor.copy(alpha = 0.78f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
