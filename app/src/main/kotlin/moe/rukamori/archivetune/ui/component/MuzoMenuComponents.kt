/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

// ── Metric parity with the player's inner overflow menu (2026-09-04) ──
// User report: "The overflow menu which can be accessed outside of the song's
// full player screen has wierd spacing. Make the dimensions and spacing as
// same as The inner overflow menu of the song which is accessed from the full
// screen player." The inner menu (PlayerMenu) renders: a 28dp-corner
// surfaceContainerLow header card with a 14/12-padded row and a 56dp artwork
// (16dp corners), then a MenuSurfaceSection wrapping NewActionGrid tiles
// (96dp min height, 28dp icon box, labelLarge SemiBold label, 12dp gaps).
// These constants mirror those exact values so both menus read identically.

/** Artwork size in the song header (inner menu: 56dp). */
private val MuzoMenuHeaderArtwork = 56.dp

/** Corner radius of the header artwork (inner menu: 16dp). */
private val MuzoMenuHeaderArtworkCorner = 16.dp

/** Corner radius of the header card (inner menu: 28dp). */
private val MuzoMenuHeaderCardCorner = 28.dp

/** Row padding inside the header card (inner menu: h=14, v=12). */
private val MuzoMenuHeaderRowPaddingHorizontal = 14.dp
private val MuzoMenuHeaderRowPaddingVertical = 12.dp

/** Horizontal padding around the action-grid section (inner menu: 12/12). */
private val MuzoMenuGridPadding = 12.dp

/**
 * The sheet's song header, rebuilt with the inner (full-screen-player) menu's
 * exact geometry (2026-09-04): a 28dp-corner `surfaceContainerLow` card with a
 * 14/12-padded row — 56dp artwork at 16dp corners, 14dp gap, then the bold
 * titleMedium title and muted bodyMedium artist stacked to its right. Purely
 * presentational; the caller feeds real song data.
 */
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

/** One quick-action tile definition: icon composable, label and click. */
class MuzoQuickAction(
    val icon: @Composable () -> Unit,
    val label: String,
    val onClick: () -> Unit,
    /** Active state (e.g. liked) — renders icon + label in the cyan accent. */
    val active: Boolean = false,
)

/**
 * The quick-action row, rebuilt as the inner menu's action grid (2026-09-04):
 * the tiles now render inside a [MenuSurfaceSection] card with the inner
 * menu's 12/12 section padding, using [NewActionButton] geometry — 96dp min
 * height, 28dp icon box, labelLarge SemiBold label, 12dp inter-tile gaps —
 * instead of the flat 4-up tile strip the outer menus used. Every action,
 * callback and state flag stays with the caller; this only lays them out and
 * colors the active tile with the cyan accent.
 */
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
