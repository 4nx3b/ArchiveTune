/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.CanvasSourceDiagnosis
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.utils.CanvasResolverEndpoints

/**
 * ── Canvas Check (2026-09-04) ────────────────────────────────────────────────
 *
 * User request: "Add an option under artwork header in playback settings named
 * Canvas Check which tells me all the mirrors, my own accounts, APIs or
 * endpoints for canvas are working or not".
 *
 * One dialog that live-checks every canvas source the player actually uses:
 *  * **Spotify Canvas — your account**: the official `canvaz-cache` endpoint
 *    through YOUR Spotify session (the same tokenProvider/trackUriResolver
 *    hooks playback uses) — a real protobuf canvaz request for the current
 *    song (or a fixed famous probe when nothing plays).
 *  * **Apple Music canvas API**: the AMP catalog search the Apple-Music
 *    canvas path performs, including token refresh.
 *  * **Every configured mirror**: each user resolver endpoint gets the exact
 *    `GET <base>?id=<video id>` the fallback chain issues, validated with the
 *    same JSON content-type rule (HTML = dead endpoint).
 *
 * All checks run in parallel on Dispatchers.IO through the canvas module's
 * real network stacks — no mock pings; the statuses shown are what playback
 * would experience right now. Results stream in as each source answers.
 */
private const val SPOTIFY_ROW_KEY = "spotify-account"
private const val APPLE_MUSIC_ROW_KEY = "apple-music"

/**
 * Fallback probe when nothing is playing: a permanently-online, extremely
 * well-known music video id ("Never Gonna Give You Up"). Mirrors answer JSON
 * for any id — the probe only needs to be a real video so the "reachable"
 * signal is meaningful.
 */
private const val FALLBACK_PROBE_VIDEO_ID = "dQw4w9WgXcQ"
private const val FALLBACK_PROBE_TITLE = "Blinding Lights"
private const val FALLBACK_PROBE_ARTIST = "The Weeknd"

private data class CanvasCheckRow(
    val key: String,
    val title: String,
    val status: CanvasSourceDiagnosis? = null,
)

@Composable
fun CanvasCheckDialog(
    resolverEndpointsRaw: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    val mirrors =
        remember(resolverEndpointsRaw) {
            CanvasResolverEndpoints.parse(resolverEndpointsRaw)
        }

    // Probe context — the CURRENTLY PLAYING song when there is one, so the
    // results reflect what the user is actually listening to; a fixed famous
    // track otherwise.
    val currentMetadata = remember { playerConnection?.mediaMetadata?.value }
    val usingCurrentSong = !currentMetadata?.title.isNullOrBlank()
    val probeTitle = currentMetadata?.title?.takeIf { it.isNotBlank() } ?: FALLBACK_PROBE_TITLE
    val probeArtist =
        currentMetadata?.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: FALLBACK_PROBE_ARTIST
    val probeSpotifyTrackUri =
        currentMetadata?.spotifyTrackId?.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" }
    val probeVideoId = currentMetadata?.id?.takeIf { it.isNotBlank() } ?: FALLBACK_PROBE_VIDEO_ID

    fun initialRows(): List<CanvasCheckRow> =
        buildList {
            add(CanvasCheckRow(SPOTIFY_ROW_KEY, stringResourceSafe(context, R.string.canvas_check_source_spotify)))
            add(CanvasCheckRow(APPLE_MUSIC_ROW_KEY, stringResourceSafe(context, R.string.canvas_check_source_apple_music)))
            mirrors.forEach { endpoint ->
                add(CanvasCheckRow("mirror:$endpoint", stringResourceSafe(context, R.string.canvas_check_source_mirror, endpoint)))
            }
        }

    var rows by remember(mirrors) { mutableStateOf(initialRows()) }
    // Running == any row still awaiting its result. Derived from the rows
    // themselves so a cancelled/restarted check can never leave a stale
    // "running" flag behind.
    val checkRunning = rows.any { it.status == null }
    var checkJob by remember { mutableStateOf<Job?>(null) }

    fun updateRow(
        key: String,
        status: CanvasSourceDiagnosis,
    ) {
        rows = rows.map { if (it.key == key) it.copy(status = status) else it }
    }

    fun startCheck() {
        checkJob?.cancel()
        rows = initialRows()
        checkJob =
            scope.launch(Dispatchers.IO) {
                coroutineScope {
                    launch {
                        val diagnosis =
                            SpotifyCanvasProvider.diagnoseOfficialEndpoint(
                                songTitle = probeTitle,
                                artistName = probeArtist,
                                spotifyTrackUri = probeSpotifyTrackUri,
                            )
                        updateRow(SPOTIFY_ROW_KEY, diagnosis)
                    }
                    launch {
                        val diagnosis = AppleMusicProvider.diagnose(probeTitle, probeArtist)
                        updateRow(APPLE_MUSIC_ROW_KEY, diagnosis)
                    }
                    mirrors.forEach { endpoint ->
                        launch {
                            val diagnosis =
                                SpotifyCanvasProvider.diagnoseResolverEndpoint(
                                    endpoint = endpoint,
                                    probeVideoId = probeVideoId,
                                )
                            updateRow("mirror:$endpoint", diagnosis)
                        }
                    }
                }
            }
    }

    // Run the checks as soon as the dialog opens.
    LaunchedEffect(mirrors) {
        startCheck()
    }

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(
                onClick = { startCheck() },
                enabled = !checkRunning,
            ) {
                Text(stringResource(R.string.canvas_check_recheck))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text =
                    if (usingCurrentSong) {
                        stringResource(
                            R.string.canvas_check_probe_playing,
                            probeTitle,
                            probeArtist,
                        )
                    } else {
                        stringResource(R.string.canvas_check_probe_fallback)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
            ) {
                items(rows.size) { index ->
                    val row = rows[index]
                    CanvasCheckRow(row)
                }
            }
            if (checkRunning) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.canvas_check_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasCheckRow(row: CanvasCheckRow) {
    val (tint, iconRes, supporting) =
        when (val status = row.status) {
            null -> Triple(Color.Unspecified, 0, stringResource(R.string.canvas_check_running))
            is CanvasSourceDiagnosis.Ok ->
                Triple(
                    CanvasCheckSuccessColor,
                    R.drawable.solar_check_circle_linear,
                    status.detail,
                )
            is CanvasSourceDiagnosis.Rejected ->
                Triple(MaterialTheme.colorScheme.error, R.drawable.solar_danger_circle_linear, status.detail)
            is CanvasSourceDiagnosis.Unreachable ->
                Triple(MaterialTheme.colorScheme.error, R.drawable.solar_danger_circle_linear, status.detail)
            is CanvasSourceDiagnosis.Skipped ->
                Triple(MaterialTheme.colorScheme.onSurfaceVariant, 0, status.detail)
        }
    ListItem(
        headlineContent = {
            Text(
                text = row.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (row.status == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else if (iconRes != 0) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
    )
}

/** iOS-style system green for the success rows (matches the iOS-red error rows). */
private val CanvasCheckSuccessColor = Color(0xFF30D158)

/** stringResource outside of composable context (initialRows runs in a click handler). */
private fun stringResourceSafe(
    context: android.content.Context,
    resId: Int,
    vararg formatArgs: Any,
): String = context.getString(resId, *formatArgs)
