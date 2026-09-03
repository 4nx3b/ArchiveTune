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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    internal var dialogContent by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    @OptIn(ExperimentalMaterial3Api::class)
    fun show(content: @Composable ColumnScope.() -> Unit) {
        dialogContent = null
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        isVisible = false
    }

    fun showDialog(content: @Composable () -> Unit) {
        isVisible = false
        dialogContent = content
    }

    fun dismissDialog() {
        dialogContent = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = Color.Unspecified,
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    if (state.isVisible) {
        // ── Muzo sheet material (2026-09-04 redesign) ──
        // The reference's floating dark-glass sheet: inset from the screen
        // edges, strongly rounded on ALL corners (it floats above the bottom
        // edge rather than being docked to it), a dark charcoal translucent
        // container, and a deeper scrim so the underlying screen survives as
        // a heavily darkened ghost — the reference's "background visible
        // through the material" without any per-frame blur cost across the
        // dialog window boundary. The ModalBottomSheet itself, its gesture
        // handling and its opening/closing animation are untouched: only the
        // shape, the colors and the outer padding changed.
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val sheetColor =
            if (background.isUnspecified) {
                if (dark) {
                    // Reference #1C1C1E at ~94% — the scrim beneath supplies
                    // the darkened-through-glass look.
                    Color(0xF01C1C1E)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)
                }
            } else {
                background
            }
        ModalBottomSheet(
            onDismissRequest = {
                focusManager.clearFocus()
                state.isVisible = false
            },
            containerColor = sheetColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            scrimColor = Color.Black.copy(alpha = 0.60f),
            shape = RoundedCornerShape(28.dp),
            dragHandle = {
                Box(
                    modifier =
                        Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            },
            modifier =
                modifier
                    .fillMaxHeight()
                    // Floating sheet margins (reference: ~16px sides, visible
                    // gap above the bottom edge).
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        ) {
            // Status bar must NEVER be visible — even while this bottom popup
            // is showing (2026-09-01). The sheet creates its own OS window; when
            // it takes focus, the system re-shows the status bar that the app
            // window had hidden, and the inset change shifts the app behind it.
            // Mirroring the hidden state onto the sheet's own window fixes both.
            KeepStatusBarHiddenInDialog()

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(),
            ) {
                state.content(this)
            }
        }
    }
}
