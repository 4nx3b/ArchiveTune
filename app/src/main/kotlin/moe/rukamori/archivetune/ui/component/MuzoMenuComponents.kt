/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

val MuzoMenuAccent = Color(0xFF32D2CA)

private val MuzoMenuHeaderArtwork = 56.dp

private val MuzoMenuHeaderArtworkCorner = 16.dp

private val MuzoMenuHeaderCardCorner = 28.dp

private val MuzoMenuHeaderRowPaddingHorizontal = 14.dp
private val MuzoMenuHeaderRowPaddingVertical = 12.dp

private val MuzoMenuGridPadding = 12.dp

@Composable
fun MuzoSongMenuHeader(
    artworkUrl: String?,
    title: String,
    artist: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(MuzoMenuHeaderCardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier.padding(
                    horizontal = MuzoMenuHeaderRowPaddingHorizontal,
                    vertical = MuzoMenuHeaderRowPaddingVertical,
                ),
        ) {
            MuzoHeaderArtwork(artworkUrl)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist.isNullOrBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MuzoHeaderArtwork(artworkUrl: String?) {
    if (artworkUrl.isNullOrBlank()) {
        Box(
            modifier =
                Modifier
                    .size(MuzoMenuHeaderArtwork)
                    .clip(RoundedCornerShape(MuzoMenuHeaderArtworkCorner))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        )
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val artworkPx =
        with(density) { MuzoMenuHeaderArtwork.roundToPx().coerceAtLeast(1) }
    val request =
        remember(artworkUrl, artworkPx) {
            ImageRequest
                .Builder(context)
                .data(artworkUrl)
                .size(Size(artworkPx, artworkPx))
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build()
        }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .size(MuzoMenuHeaderArtwork)
                .clip(RoundedCornerShape(MuzoMenuHeaderArtworkCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    )
}

class MuzoQuickAction(
    val icon: @Composable () -> Unit,
    val label: String,
    val onClick: () -> Unit,

    val active: Boolean = false,
)

@Composable
fun MuzoQuickActionRow(
    actions: List<MuzoQuickAction>,
    modifier: Modifier = Modifier,
) {
    MenuSurfaceSection(
        modifier =
            modifier.padding(
                horizontal = MuzoMenuGridPadding,
                vertical = MuzoMenuGridPadding,
            ),
    ) {
        NewActionGrid(
            actions =
                actions.map { action ->
                    NewAction(
                        icon = action.icon,
                        text = action.label,
                        onClick = action.onClick,
                        contentColor =
                            if (action.active) {
                                MuzoMenuAccent
                            } else {
                                Color.Unspecified
                            },
                    )
                },
        )
    }
}
