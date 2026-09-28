/*
 * LocalMiniPlayerInset shim for the ported Tryptify EQ screens. In Tryptify
 * this carries the mini-player's height so screens can pad above it; inside
 * ArchiveTune's Equalizer dialog there is no mini player, so it stays 0.
 */

package tf.monochrome.android.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalMiniPlayerInset = compositionLocalOf<Dp> { 0.dp }
