/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.smart

import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.constants.AutomixPerformanceMode

/**
 * Process-wide settings surface for the Automix pipeline.
 *
 * The analysis back end ([BeatTracker], [VocalTracker]) needs the inference
 * thread budget at session-construction time, which happens deep inside the
 * analyzer's worker — far from any composable or DataStore scope. The
 * MusicService settings observer keeps [performanceMode] current; the model
 * sessions re-read it the next time they are (re)created.
 */
object SmartFadeSettings {
    val performanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)
}
