/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Ported from vivi-music (beta branch) utils/Utils.kt listItemShape (GPL-3.0).
 */

package moe.rukamori.archivetune.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun listItemShape(index: Int, count: Int, radius: Dp = 16.dp): Shape {
    return when {
        count == 1 -> RoundedCornerShape(radius)
        index == 0 -> RoundedCornerShape(
            topStart = radius,
            topEnd = radius,
        )
        index == count - 1 -> RoundedCornerShape(
            bottomStart = radius,
            bottomEnd = radius,
        )
        else -> RectangleShape
    }
}
