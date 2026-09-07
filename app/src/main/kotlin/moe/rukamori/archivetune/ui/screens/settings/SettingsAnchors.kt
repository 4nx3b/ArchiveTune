/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

object SettingsAnchorRequest {

    private const val CLAIM_WINDOW_MS = 1_500L

    private var pendingScreen: String? = null
    private var pendingAnchor: String? = null

    private var claimedAtMs: Long? = null

    internal var elapsedMs: () -> Long = { SystemClock.uptimeMillis() }

    internal fun reset() {
        pendingScreen = null
        pendingAnchor = null
        claimedAtMs = null
    }

    fun request(screen: String, anchor: String) {
        pendingScreen = screen
        pendingAnchor = anchor
        claimedAtMs = null
    }

    fun consume(screen: String): String? {
        if (pendingScreen != screen) return null
        val now = elapsedMs()
        val claimedAt = claimedAtMs
        if (claimedAt == null) {
            claimedAtMs = now
            return pendingAnchor
        }
        if (now - claimedAt > CLAIM_WINDOW_MS) {
            reset()
            return null
        }
        return pendingAnchor
    }
}

object SettingsAnchorScreens {
    const val PLAYER = "settings/player"
    const val APPEARANCE = "settings/appearance"
    const val STORAGE = "settings/storage"
    const val CONTENT = "settings/content"
    const val PRIVACY = "settings/privacy"
    const val INTERNET = "settings/internet"
    const val LYRICS = "settings/lyrics"
    const val BACKUP = "settings/backup_restore"
    const val INTEGRATION = "settings/integration"
}

object SettingsAnchors {

    const val CROSSFADE = "crossfade"
    const val GAPLESS = "gapless"
    const val SKIP_SILENCE = "skip_silence"
    const val AUDIO_NORMALIZATION = "audio_normalization"
    const val PERSISTENT_QUEUE = "persistent_queue"
    const val EXTERNAL_DOWNLOADER = "external_downloader"

    const val DYNAMIC_THEME = "dynamic_theme"
    const val DARK_THEME = "dark_theme"
    const val PURE_BLACK = "pure_black"
    const val APP_ICON = "app_icon"
    const val FONT = "font"
    const val HIGH_REFRESH_RATE = "high_refresh_rate"

    const val EXPORT_DOWNLOADS = "export_downloads"
    const val EXPORT_DOWNLOADS_PICK = "export_downloads_pick"
    const val CLEAR_DOWNLOADS = "clear_downloads"
    const val SONG_CACHE_SIZE = "song_cache_size"
    const val CLEAR_SONG_CACHE = "clear_song_cache"
    const val IMAGE_CACHE_SIZE = "image_cache_size"
    const val SMART_TRIMMER = "smart_trimmer"

    const val HIDE_EXPLICIT = "hide_explicit"
    const val HIDE_VIDEO = "hide_video"
    const val ALLOW_AGE_RESTRICTED = "allow_age_restricted"
    const val APP_LANGUAGE = "app_language"

    const val PAUSE_LISTEN_HISTORY = "pause_listen_history"
    const val PAUSE_SEARCH_HISTORY = "pause_search_history"
    const val HAPTICS = "haptics"
    const val DISABLE_SCREENSHOT = "disable_screenshot"

    const val DNS_OVER_HTTPS = "dns_over_https"
    const val PROXY = "proxy"

    const val LYRICS_MODE = "lyrics_mode"
    const val LYRICS_ANIMATION = "lyrics_animation"
    const val LYRICS_AUTO_SCROLL = "lyrics_auto_scroll"
    const val LYRICS_LINE_BLUR = "lyrics_line_blur"

    const val BACKUP = "backup"
    const val RESTORE = "restore"

    const val CROSS_SERVICE_IMPORT = "cross_service_import"
}

private val HighlightCornerRadius = 18.dp
private val HighlightInset = 1.dp
private const val HighlightFadeInMs = 220
private const val HighlightHoldMs = 1100L
private const val HighlightFadeOutMs = 450

@Stable
class SettingsAnchorState internal constructor(
    internal val target: String?,
    highlightColor: Color,
    val scrollState: ScrollState,
) {

    internal var highlightColor: Color by mutableStateOf(highlightColor)
    private var containerTop: Float? by mutableStateOf(null)
    private var anchorTop: Float? by mutableStateOf(null)

    private var highlightAlpha: Float by mutableFloatStateOf(0f)

    internal val scrollTarget: Int?
        get() {
            if (target == null) return null
            val container = containerTop ?: return null
            val anchor = anchorTop ?: return null
            return (scrollState.value + (anchor - container)).roundToInt().coerceAtLeast(0)
        }

    val containerModifier: Modifier
        get() =
            Modifier.onGloballyPositioned { coordinates ->
                if (target != null && containerTop == null) {
                    containerTop = coordinates.positionInRoot().y
                }
            }

    internal suspend fun runHighlight() {
        animate(0f, 1f, animationSpec = tween(HighlightFadeInMs)) { value, _ -> highlightAlpha = value }
        delay(HighlightHoldMs)
        animate(1f, 0f, animationSpec = tween(HighlightFadeOutMs)) { value, _ -> highlightAlpha = value }
    }

    fun anchor(key: String): Modifier =
        Modifier
            .onGloballyPositioned { coordinates ->
                if (key == target && anchorTop == null) {
                    anchorTop = coordinates.positionInRoot().y
                }
            }.drawWithContent {
                drawContent()
                val progress = if (key == target) highlightAlpha else 0f
                if (progress <= 0f) return@drawWithContent
                val inset = HighlightInset.toPx()
                drawRoundRect(
                    color = highlightColor.copy(alpha = highlightColor.alpha * progress),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(HighlightCornerRadius.toPx()),
                )
            }
}

@Composable
fun rememberSettingsAnchorState(screen: String): SettingsAnchorState {
    val scrollState = rememberScrollState()
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val state =
        remember(screen) {
            SettingsAnchorState(
                target = SettingsAnchorRequest.consume(screen),
                highlightColor = highlightColor,
                scrollState = scrollState,
            )
        }
    state.highlightColor = highlightColor

    val scrollTarget = state.scrollTarget
    LaunchedEffect(state, scrollTarget) {
        if (scrollTarget == null) return@LaunchedEffect
        state.scrollState.animateScrollTo(scrollTarget)
        state.runHighlight()
    }
    return state
}
