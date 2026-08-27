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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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

/**
 * The pink/red accent used by the iOS-inspired music UI redesign.
 *
 * Matches the vibrant magenta-pink of iOS system pink (#FF2D55) used as the
 * accent color throughout the redesigned History / Liked / Cached / Playlist
 * pages: section labels, primary action button text & icons, active tab indicator.
 */
val AppleMusicStyleAccentColor: Color = Color(0xFFFF375C)

/**
 * A compact, iOS-inspired playlist/album hero header that matches the user's
 * reference screenshots:
 *
 *   • Small pink uppercase section label (e.g. "RECENTLY PLAYED")
 *   • Large bold white page title (left-aligned, SF Pro-like)
 *   • Subtitle/metadata line in muted gray
 *   • Rounded pill-shaped "Play" and "Shuffle" controls with pink text/icons
 *   • Optional trailing icon action (e.g. Clear/Overflow)
 *
 * Unlike [MediaDetailHero], this header does NOT render a large artwork
 * backdrop — it sits on the page's plain dark background so the visual rhythm
 * matches the reference (large title + clean controls + song list).
 *
 * Existing callers continue to use [MediaDetailHero] unchanged; this is only
 * used by the redesigned History, Liked Songs, Cached Songs and Playlist
 * pages.
 *
 * @param sectionLabel Small uppercase accent label (e.g. "RECENTLY PLAYED").
 *                     Pass null to omit.
 * @param title Large bold page title.
 * @param subtitle Optional metadata line below the title (e.g. "20 songs • 1h 12m").
 * @param onPlay Play action callback. If null, the Play button is hidden.
 * @param onShuffle Shuffle action callback. If null, the Shuffle button is hidden.
 * @param onPrimaryTrailing Optional icon-button action to the right of Play/Shuffle
 *                          (e.g. Clear history, More options). Pass null to omit.
 * @param primaryTrailingIcon The icon drawable for [onPrimaryTrailing].
 * @param primaryTrailingDescription Content description for [onPrimaryTrailing].
 * @param additionalActions Optional extra actions rendered after the trailing icon
 *                          (e.g. download state indicator for playlists).
 * @param modifier Modifier for the container.
 */
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
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        subtitle?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.6f),
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
                    )
                }
                onShuffle?.let { shuffle ->
                    PillActionButton(
                        text = stringResource(moe.rukamori.archivetune.R.string.shuffle),
                        icon = moe.rukamori.archivetune.R.drawable.shuffle,
                        accent = accent,
                        primary = false,
                        onClick = shuffle,
                    )
                }
                additionalActions?.invoke()
                if (onPrimaryTrailing != null && primaryTrailingIcon != null) {
                    Spacer(modifier = Modifier.weight(1f))
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
) {
    val containerColor = Color.White.copy(alpha = if (primary) 0.10f else 0.06f)
    Surface(
        modifier =
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .height(46.dp),
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 22.dp)
                    .fillMaxWidth(),
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
    val containerColor = Color.White.copy(alpha = 0.06f)
    Surface(
        modifier =
            Modifier
                .size(46.dp)
                .clip(CircleShape),
        shape = CircleShape,
        color = containerColor,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.IconButton(onClick = onClick) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = description?.let { stringResource(it) },
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
