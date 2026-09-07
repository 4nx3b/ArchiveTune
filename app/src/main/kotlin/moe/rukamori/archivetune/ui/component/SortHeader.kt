/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PlaylistSongSortType
import moe.rukamori.archivetune.constants.PlaylistSortType

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
inline fun <reified T : Enum<T>> SortHeader(
    sortType: T,
    sortDescending: Boolean,
    crossinline onSortTypeChange: (T) -> Unit,
    noinline onSortDescendingChange: (Boolean) -> Unit,
    crossinline sortTypeText: (T) -> Int,
    modifier: Modifier = Modifier,
    showDescending: Boolean? = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val allowDescending =
        when (sortType) {
            is PlaylistSongSortType -> sortType != PlaylistSongSortType.CUSTOM
            is PlaylistSortType -> sortType != PlaylistSortType.CUSTOM
            else -> true
        }
    val showSortDirection = showDescending == true && allowDescending
    val sortDirectionRotation by animateFloatAsState(
        targetValue = if (sortDescending) 0f else 180f,
        label = "SortHeaderDirection",
    )

    val accent = AppleMusicStyleAccentColor
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val containerColor = onBackgroundColor.copy(alpha = 0.06f)
    val pillShape = RoundedCornerShape(percent = 50)

    Box(modifier = modifier.padding(vertical = 8.dp)) {

        Surface(
            shape = pillShape,
            color = containerColor,
            modifier =
                Modifier
                    .clip(pillShape)
                    .height(44.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {

                Icon(
                    painter = painterResource(R.drawable.sort_alt),
                    contentDescription = null,
                    tint = accent,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .padding(end = 0.dp),
                )

                Surface(
                    onClick = { menuExpanded = true },
                    color = Color.Transparent,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = stringResource(sortTypeText(sortType)),
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.widthIn(max = 160.dp).padding(vertical = 6.dp),
                    )
                }

                if (showSortDirection) {
                    Surface(
                        onClick = { onSortDescendingChange(!sortDescending) },
                        color = Color.Transparent,
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_downward),
                            contentDescription =
                                stringResource(
                                    if (sortDescending) {
                                        R.string.sort_order_descending
                                    } else {
                                        R.string.sort_order_ascending
                                    },
                                ),
                            tint = accent,
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .rotate(sortDirectionRotation)
                                    .padding(start = 2.dp),
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 172.dp),
        ) {
            enumValues<T>().forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(sortTypeText(type)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            painter =
                                painterResource(
                                    if (sortType == type) {
                                        R.drawable.radio_button_checked
                                    } else {
                                        R.drawable.radio_button_unchecked
                                    },
                                ),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onSortTypeChange(type)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
