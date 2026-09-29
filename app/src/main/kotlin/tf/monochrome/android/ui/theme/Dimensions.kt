package tf.monochrome.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

object MonoDimens {

    val spacingXs = 4.dp

    val spacingSm = 8.dp

    val spacingMd = 12.dp

    val spacingLg = 16.dp

    val spacingXl = 24.dp

    val radiusSm = 6.dp

    val radiusMd = 12.dp

    val radiusLg = 16.dp

    val radiusPill = 24.dp

    val shapeSm = RoundedCornerShape(radiusSm)
    val shapeMd = RoundedCornerShape(radiusMd)
    val shapeLg = RoundedCornerShape(radiusLg)
    val shapePill = RoundedCornerShape(radiusPill)
    val shapeCircle = CircleShape

    val iconSm = 24.dp

    val iconMd = 32.dp

    val iconLg = 48.dp

    val iconXl = 64.dp

    val coverMini = 40.dp

    val coverList = 48.dp

    val coverCard = 160.dp

    val coverHero = 240.dp

    val coverPlayer = 300.dp

    val listItemPaddingH = 16.dp

    val listItemPaddingV = 10.dp

    val listBottomPadding = 80.dp

    val linkHitBoxV = 4.dp

    val badgePaddingV = 4.dp

    val listRowHeight: Dp
        @Composable get() {
            val typography = MaterialTheme.typography
            return with(LocalDensity.current) {
                listRowHeightOf(
                    titleLineHeight = lineHeightDp(typography.bodyLarge),
                    subtitleLineHeight = lineHeightDp(typography.bodySmall),
                )
            }
        }

    val searchRowHeight: Dp
        @Composable get() {
            val typography = MaterialTheme.typography
            return with(LocalDensity.current) {
                searchRowHeightOf(
                    titleLineHeight = lineHeightDp(typography.bodyLarge),
                    subtitleLineHeight = lineHeightDp(typography.bodySmall),
                    badgeLineHeight = lineHeightDp(typography.labelSmall),
                )
            }
        }

    const val cardAlpha = 0.85f

    const val glassAlpha = 0.25f

    const val glassBorderAlpha = 0.15f

    val glassBorderWidth = 0.5.dp

    val glassBlurRadius = 80.dp

    val glassElevation = 4.dp
}

private fun Density.lineHeightDp(style: TextStyle): Dp {
    val unit = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    return if (unit.isSpecified) unit.toDp() else 0.dp
}

internal fun listRowHeightOf(
    titleLineHeight: Dp,
    subtitleLineHeight: Dp,
    coverSize: Dp = MonoDimens.coverList,
    linkInset: Dp = MonoDimens.linkHitBoxV,
    verticalPadding: Dp = MonoDimens.spacingSm,
): Dp = maxOf(coverSize, titleLineHeight + subtitleLineHeight + linkInset * 2) + verticalPadding * 2

internal fun searchRowHeightOf(
    titleLineHeight: Dp,
    subtitleLineHeight: Dp,
    badgeLineHeight: Dp,
    coverSize: Dp = MonoDimens.coverList,
    linkInset: Dp = MonoDimens.linkHitBoxV,
    badgePadding: Dp = MonoDimens.badgePaddingV,
    verticalPadding: Dp = MonoDimens.spacingSm,
    lineGap: Dp = MonoDimens.spacingXs,
): Dp = maxOf(
    coverSize,
    titleLineHeight + lineGap +
        subtitleLineHeight + linkInset * 2 + lineGap +
        badgeLineHeight + badgePadding * 2,
) + verticalPadding * 2
