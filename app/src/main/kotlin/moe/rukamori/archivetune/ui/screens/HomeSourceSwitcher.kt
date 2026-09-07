/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HomeSource
import moe.rukamori.archivetune.constants.HomeSourceKey
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
fun rememberHomeSourceAvailable(): Boolean {
    val spDc by rememberPreference(SpotifySpDcKey, defaultValue = "")
    return spDc.isNotBlank()
}

@Composable
fun rememberHomeSource(): HomeSource {
    val stored by rememberEnumPreference(HomeSourceKey, defaultValue = HomeSource.YOUTUBE)
    return if (stored == HomeSource.SPOTIFY && !rememberHomeSourceAvailable()) HomeSource.YOUTUBE else stored
}

private val IconSize = 18.dp

@Composable
fun HomeSourceSwitcher(modifier: Modifier = Modifier) {
    if (!rememberHomeSourceAvailable()) return

    var source by rememberEnumPreference(HomeSourceKey, defaultValue = HomeSource.YOUTUBE)
    val options = HomeSource.entries

    SingleChoiceSegmentedButtonRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(vertical = 8.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = source == option,
                onClick = { source = option },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(option.iconResId()),
                            contentDescription = null,

                            modifier = Modifier.size(IconSize),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(option.labelResId()),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
    }
}

private fun HomeSource.labelResId(): Int =
    when (this) {
        HomeSource.YOUTUBE -> R.string.home_source_youtube
        HomeSource.SPOTIFY -> R.string.home_source_spotify
    }

private fun HomeSource.iconResId(): Int =
    when (this) {
        HomeSource.YOUTUBE -> R.drawable.ic_music
        HomeSource.SPOTIFY -> R.drawable.spotify_icon
    }

@Composable
fun rememberSwitchToYouTube(): () -> Unit {
    var source by rememberEnumPreference(HomeSourceKey, defaultValue = HomeSource.YOUTUBE)
    return { source = HomeSource.YOUTUBE }
}
