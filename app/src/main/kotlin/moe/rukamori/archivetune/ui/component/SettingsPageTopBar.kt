/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R

@Composable
fun SettingsPageTopBar(
    titleText: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = systemBarsTopPadding)
                .height(64.dp),
    ) {
        Text(
            text = titleText,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(start = 64.dp, end = 20.dp)
                    .basicMarquee(iterations = Int.MAX_VALUE),
        )

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
        ) {
            IconButton(
                onClick = onBack,
                onLongClick = onBackLongClick,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
