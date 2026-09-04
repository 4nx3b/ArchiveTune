/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

// ============================================================================
// Muzo-style song-menu components (2026-09-04 redesign).
//
// The shared visual language of the reference song-overflow sheet: a song
// header (square rounded artwork + bold title + muted artist), a row of four
// equal quick-action tiles (icon over label, cyan accent for the active
// liked state), and the tile metrics the two song menus (local SongMenu and
// remote YouTubeSongMenu) both render — so the SAME design appears wherever
// a song overflow menu opens, from any player style or list row.
//
// These are pure presentation components: every action, callback and state
// flag stays in the menus themselves; nothing here owns business logic.
// ============================================================================

/** The reference's active/liked accent (cyan-teal, #32D2CA). */
val MuzoMenuAccent = Color(0xFF32D2CA)

/** Artwork size in the song header (reference: ~56-64pt). */
private val MuzoMenuHeaderArtwork = 64.dp

/** Corner radius of the header artwork (reference: ~12pt). */
private val MuzoMenuHeaderArtworkCorner = 12.dp

/** Corner radius of a quick-action tile (reference: ~12-14pt). */
private val MuzoMenuTileCorner = 14.dp

/** Horizontal padding of the header and tile row. */
private val MuzoMenuHorizontalPadding = 16.dp

/**
 * The sheet's song header: square rounded artwork on the left, the bold
 * title and muted artist stacked to its right — the reference's header
 * block. Purely presentational; the caller feeds real song data.
 */
@Composable
fun MuzoSongMenuHeader(
    artworkUrl: String?,
    title: String,
    artist: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MuzoMenuHorizontalPadding + 4.dp)
                .padding(vertical = 4.dp),
    ) {
        MuzoHeaderArtwork(artworkUrl)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!artist.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = artist,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

/** One quick-action tile definition: icon composable, label and click. */
class MuzoQuickAction(
    val icon: @Composable () -> Unit,
    val label: String,
    val onClick: () -> Unit,
    /** Active state (e.g. liked) — renders icon + label in the cyan accent. */
    val active: Boolean = false,
)

/**
 * The reference's quick-action row: four equal tiles, each a rounded
 * translucent surface with a large icon above a small label. The caller owns
 * the actions and their real state — this only lays them out and colors the
 * active tile.
 */
@Composable
fun MuzoQuickActionRow(
    actions: List<MuzoQuickAction>,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MuzoMenuHorizontalPadding),
    ) {
        actions.forEach { action ->
            MuzoQuickActionTile(
                icon = action.icon,
                label = action.label,
                onClick = action.onClick,
                active = action.active,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MuzoQuickActionTile(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // The reference's tile material (#3A3A3C on the #1C1C1E sheet): a lighter
    // translucent step above the sheet's own surface.
    val tileColor =
        if (dark) {
            Color(0xFF3A3A3C).copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
        }
    val contentColor =
        if (active) {
            MuzoMenuAccent
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clip(RoundedCornerShape(MuzoMenuTileCorner))
                .background(tileColor)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(26.dp),
            ) {
                icon()
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
