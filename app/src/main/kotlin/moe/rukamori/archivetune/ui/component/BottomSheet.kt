/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import moe.rukamori.archivetune.ui.player.LocalRootOverlayActive
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.constants.BottomSheetAnimationSpec
import moe.rukamori.archivetune.constants.BottomSheetSoftAnimationSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)? = null,
    keepContentAlive: Boolean = false,
    morphMode: Boolean = false,
    backHandlerEnabled: Boolean = true,
    opaqueBackground: Boolean = false,
    onCollapsedContentClick: (() -> Unit)? = null,
    navbarHiddenOffset: (() -> Float)? = null,
    sharedLayer: (@Composable BoxScope.() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // Morph mode (the SpatialFlow recipe): the sheet corner radius and both
    // crossfade layers animate continuously with the sheet progress — no
    // discrete corner pop at the transition edges, and the shared layer (the
    // floating artwork) bridges the mini and full content across the whole
    // 0..1 travel instead of both sides fading out mid-flight.
    val morphShape =
        if (morphMode) {
            remember(state) {
                PlayerSheetDynamicShape(
                    // Reads state.progress inside createOutline (the draw
                    // phase) so the corner rebuilds every frame without any
                    // recomposition.
                    progressProvider = { state.progress.coerceIn(0f, 1f) },
                )
            }
        } else {
            null
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()

                .graphicsLayer {
                    val y =
                        (state.expandedBound - state.value)
                            .roundToPx()
                            .coerceAtLeast(0)
                    // Scroll-to-hide / route-change navbar: while the sheet sits
                    // collapsed, let the mini player drift down into the space
                    // the navigation bar vacated. Fades out with sheet progress
                    // so the expanded player is never double-shifted.
                    val takeOver =
                        navbarHiddenOffset?.invoke()?.coerceAtLeast(0f) ?: 0f
                    translationY = y + takeOver * (1f - state.progress.coerceIn(0f, 1f))
                }.bottomSheetDraggable(state, onDismiss)
                .then(
                    if (morphShape != null) {
                        Modifier.clip(morphShape)
                    } else {
                        Modifier.clip(
                            RoundedCornerShape(
                                topStart = if (!state.isExpanded) 16.dp else 0.dp,
                                topEnd = if (!state.isExpanded) 16.dp else 0.dp,
                            ),
                        )
                    },
                ).then(

                    if (opaqueBackground) {
                        Modifier.drawBehind {
                            if (state.progress > 0f) {
                                drawRect(color = backgroundColor)
                            }

                        }
                    } else {
                        Modifier.drawBehind {
                            val alpha = backgroundColor.alpha * state.progress.coerceIn(0f, 1f)
                            if (alpha > 0f) {
                                drawRect(color = backgroundColor, alpha = alpha)
                            }
                        }
                    },
                ),
    ) {

        if (state.isExpandedOrExpanding && backHandlerEnabled && !LocalRootOverlayActive.current) {
            BackHandler(onBack = state::collapseSoft)
        }

        // The shared layer rides above both crossfade containers: its content
        // (the floating artwork morph) stays visible across the whole
        // mini <-> full travel.
        if (sharedLayer != null) {
            val sharedZIndex by remember(state) {
                derivedStateOf { if (state.progress > 0.5f) 3f else 2.5f }
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { zIndex = sharedZIndex },
                content = sharedLayer,
            )
        }

        val fullContentZIndex by remember(state) {
            derivedStateOf { if (state.progress > 0.5f) 2f else 1f }
        }
        if (keepContentAlive) {

            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            zIndex = fullContentZIndex
                            if (morphMode) {
                                // SpatialFlow curves: the full player only
                                // starts fading in past halfway — the shared
                                // artwork layer covers the first half of the
                                // travel. No settle translation: the floating
                                // artwork's target IS this layer's layout slot.
                                val p = state.progress.coerceIn(0f, 1f)
                                alpha = ((p - 0.5f) * 2).coerceIn(0f, 1f)
                                if (p <= 0.01f) translationY = 10_000f
                            } else {
                                alpha = if (state.isCollapsed) 0f else ((state.progress - 0.25f) * 4).coerceIn(0f, 1f)
                            }
                        },
                content = content,
            )
        } else if (!state.isCollapsed) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            zIndex = fullContentZIndex
                            if (morphMode) {
                                val p = state.progress.coerceIn(0f, 1f)
                                alpha = ((p - 0.5f) * 2).coerceIn(0f, 1f)
                            } else {
                                alpha = ((state.progress - 0.25f) * 4).coerceIn(0f, 1f)
                            }
                        },
                content = content,
            )
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            val miniZIndex by remember(state) {
                derivedStateOf { if (state.progress > 0.5f) 1f else 2f }
            }
            Box(
                modifier =
                    Modifier
                        .graphicsLayer {
                            zIndex = miniZIndex
                            alpha =
                                if (morphMode) {
                                    // Gone exactly at halfway, when the full
                                    // layer starts taking over.
                                    (1f - 2f * state.progress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                                } else {
                                    1f - (state.progress * 4).coerceAtMost(1f)
                                }
                        }.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCollapsedContentClick ?: state::expandSoft,
                        ).fillMaxWidth()
                        .height(state.collapsedBound),
                content = collapsedContent,
            )
        }
    }
}

/**
 * A shape whose top corner radius is read at draw time from [topCornerProvider]
 * (fed the live sheet progress), so the sheet corners morph continuously with
 * the mini <-> full transition instead of flipping between two fixed radii.
 * Draw-phase only: no recomposition per frame.
 */
private class PlayerSheetDynamicShape(
    private val progressProvider: () -> Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: Density,
    ): Outline {
        val progress = progressProvider()
        val cornerPx = with(density) { androidx.compose.ui.unit.lerp(28.dp, 0.dp, progress).toPx() }
        return Outline.Rounded(
            corner = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
            size = size,
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    private val animationsDisabled: Boolean,
    private val density: Density,
    val collapsedBound: Dp,
    initialAnchor: Int = DISMISSED_ANCHOR,
) : DraggableState by draggableState {
    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    var targetAnchor by mutableIntStateOf(initialAnchor)
        private set

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        value >= animatable.upperBound!! - 0.5.dp
    }

    val isExpandedOrExpanding: Boolean
        get() = targetAnchor == EXPANDED_ANCHOR

    val progress by derivedStateOf {
        1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
    }

    private fun updateAnchor(anchor: Int) {
        targetAnchor = anchor
        onAnchorChanged(anchor)
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        updateAnchor(COLLAPSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        updateAnchor(EXPANDED_ANCHOR)
        sheetScrollConnection.resetLatch()
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.upperBound!!, animationSpec)
        }
    }

    /**
     * Velocity-carrying settle (the SpatialFlow trick): the fling velocity is
     * handed into the spring as its initial velocity, so the sheet continues
     * at the finger's speed instead of stopping dead on release and
     * re-accelerating toward the anchor.
     */
    fun collapse(animationSpec: AnimationSpec<Dp>, velocityPx: Float) {
        updateAnchor(COLLAPSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(
                collapsedBound,
                animationSpec,
                initialVelocity = velocityPx.toAnimatableVelocity(),
            )
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>, velocityPx: Float) {
        updateAnchor(EXPANDED_ANCHOR)
        sheetScrollConnection.resetLatch()
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(
                animatable.upperBound!!,
                animationSpec,
                initialVelocity = velocityPx.toAnimatableVelocity(),
            )
        }
    }

    private fun Float.toAnimatableVelocity(): AnimationVector1D =
        // px/s -> dp/s (the animatable's unit), wrapped for animateTo.
        AnimationVector1D(with(density) { toDp() }.value)

    private fun collapse(velocityPx: Float = 0f) {
        collapse(if (animationsDisabled) snap() else BottomSheetAnimationSpec, velocityPx)
    }

    private fun expand(velocityPx: Float = 0f) {
        expand(if (animationsDisabled) snap() else BottomSheetAnimationSpec, velocityPx)
    }

    fun collapseSoft() {
        collapse(if (animationsDisabled) snap() else BottomSheetSoftAnimationSpec)
    }

    fun expandSoft() {
        expand(if (animationsDisabled) snap() else BottomSheetSoftAnimationSpec)
    }

    fun dismiss() {
        updateAnchor(DISMISSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.lowerBound!!, if (animationsDisabled) snap() else BottomSheetAnimationSpec)
        }
    }

    fun snapTo(value: Dp) {
        updateAnchor(
            when (value) {
                expandedBound -> EXPANDED_ANCHOR
                collapsedBound -> COLLAPSED_ANCHOR
                dismissedBound -> DISMISSED_ANCHOR
                else -> COLLAPSED_ANCHOR
            },
        )
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.snapTo(value)
        }
    }

    fun performFling(
        velocity: Float,
        onDismiss: (() -> Unit)?,
    ) {
        if (velocity > 250) {
            expand(velocity)
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse(velocity)
            }
        } else {
            val l0 = dismissedBound
            val l1 = (collapsedBound - dismissedBound) / 2
            val l2 = (expandedBound - collapsedBound) / 2
            val l3 = expandedBound

            when (value) {
                in l0..l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse(velocity)
                    }
                }

                in l1..l2 -> {
                    collapse(velocity)
                }

                in l2..l3 -> {
                    expand(velocity)
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private val sheetScrollConnection = PreUpPostDownNestedScrollConnection(this)

    val preUpPostDownNestedScrollConnection: NestedScrollConnection get() = sheetScrollConnection
}

private class PreUpPostDownNestedScrollConnection(
    private val sheet: BottomSheetState,
) : NestedScrollConnection {
    var isTopReached = false

    fun resetLatch() {
        isTopReached = false
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (sheet.isExpanded && available.y < 0) {
            isTopReached = false
        }

        return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
            sheet.dispatchRawDelta(available.y)
            available
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (!isTopReached) {
            isTopReached = consumed.y == 0f && available.y > 0
        }

        return if (isTopReached && source == NestedScrollSource.UserInput) {
            sheet.dispatchRawDelta(available.y)
            available
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (isTopReached) {
            val velocity = -available.y
            sheet.performFling(velocity, null)

            available
        } else {
            Velocity.Zero
        }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity {
        isTopReached = false
        return Velocity.Zero
    }
}

const val EXPANDED_ANCHOR = 2
const val COLLAPSED_ANCHOR = 1
const val DISMISSED_ANCHOR = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = DISMISSED_ANCHOR,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val animationsDisabled = LocalAnimationsDisabled.current

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable =
        remember {
            Animatable(0.dp, Dp.VectorConverter)
        }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope, animationsDisabled) {
        val initialValue =
            when (previousAnchor) {
                EXPANDED_ANCHOR -> expandedBound
                COLLAPSED_ANCHOR -> collapsedBound
                DISMISSED_ANCHOR -> dismissedBound
                else -> error("Unknown BottomSheet anchor")
            }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(initialValue, if (animationsDisabled) snap() else BottomSheetAnimationSpec)
        }

        BottomSheetState(
            draggableState =
                DraggableState { delta ->
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                    }
                },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            animationsDisabled = animationsDisabled,
            density = density,
            collapsedBound = collapsedBound,
            initialAnchor = previousAnchor,
        )
    }
}

@Composable
fun Modifier.bottomSheetDraggable(
    state: BottomSheetState,
    onDismiss: (() -> Unit)? = null,
): Modifier =
    this.pointerInput(state) {
        val velocityTracker = VelocityTracker()

        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                velocityTracker.addPointerInputChange(change)
                state.dispatchRawDelta(dragAmount)
            },
            onDragCancel = {
                val velocity = -velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                state.performFling(velocity, onDismiss)
            },
            onDragEnd = {
                val velocity = -velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                state.performFling(velocity, onDismiss)
            },
        )
    }
