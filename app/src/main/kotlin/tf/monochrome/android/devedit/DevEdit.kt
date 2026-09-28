/*
 * DevEditable shim for the ported Tryptify EQ screens. Upstream this wrapper
 * powers an internal dev-tools overlay (long-press to tweak padding/etc.);
 * inside ArchiveTune it is a pure pass-through so the ported screens keep
 * their structure without porting the whole dev-tools subsystem.
 */

package tf.monochrome.android.devedit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DevEditable(
    elementId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) { content() }
}
