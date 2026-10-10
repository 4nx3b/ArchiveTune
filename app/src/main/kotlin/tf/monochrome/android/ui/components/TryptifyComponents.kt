

package tf.monochrome.android.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val PressSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = 900f,
)

@Composable
fun reduceMotion(): Boolean = false

fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = PressSpring,
        label = "bounceScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceCombinedClick(
    scaleDown: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = PressSpring,
        label = "bounceCombinedScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

fun Modifier.liquidGlass(
    shape: Shape = CircleShape,
    tintAlpha: Float = 0.15f,
): Modifier = composed {
    val scheme = MaterialTheme.colorScheme
    val tint = scheme.primary.copy(alpha = tintAlpha.coerceIn(0f, 1f))
    val rim = scheme.onSurface.copy(alpha = 0.12f)
    this
        .clip(shape)
        .drawBehind {
            drawRect(tint)
            drawRect(
                color = rim,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
}

class GlassPress {
    internal val interactions = MutableInteractionSource()
    internal var boxSize: androidx.compose.ui.unit.IntSize =
        androidx.compose.ui.unit.IntSize(0, 0)
}

object GlassPressDefaults {
    const val SQUEEZE = 0.96f
}

@Composable
fun rememberGlassPress(): GlassPress = remember { GlassPress() }

fun Modifier.glassSqueeze(
    press: GlassPress,
    enabled: Boolean = true,
    squeeze: Float = GlassPressDefaults.SQUEEZE,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val isPressed by press.interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) squeeze else 1f,
        animationSpec = PressSpring,
        label = "glassSqueeze",
    )
    this
        .onSizeChanged { press.boxSize = it }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = press.interactions,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

@Composable
fun SearchOverlay(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    onSubmit: () -> Unit = {},
    barContent: @Composable ColumnScope.() -> Unit = {},
    content: @Composable (topInset: Dp) -> Unit,
) {
    var barHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(open, autoFocus) {
        if (open && autoFocus) focusRequester.requestFocus()
    }

    Column(modifier = modifier) {
        if (open) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                },
                trailingIcon = {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.merge(
                    TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { barHeightPx = it.height }
                    .focusRequester(focusRequester),
            )
            barContent()
        }
        val inset = with(density) { barHeightPx.toDp() }
        content(inset)
    }
}
