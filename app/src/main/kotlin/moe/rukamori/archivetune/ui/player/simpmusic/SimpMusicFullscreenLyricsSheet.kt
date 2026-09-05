/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * SimpMusic's fullscreen lyrics page.
 *
 * A port of SimpMusic's `FullscreenLyricsSheet` (its ui/component/LyricsView.kt,
 * https://github.com/maxrave-dev/SimpMusic, GPL-3.0): a full-height black sheet whose
 * background is the artwork palette colour bleeding into black through a slowly wandering
 * five-stop linear gradient (angle ±45° over 24 s, offsets ±1500/±1000 over 32 s — the
 * original 6 s / 8 s sweeps read as a fast strobe on a phone screen and were slowed 4x
 * 2026-09-05, user report: "the background changes at extremely fast speed" — the stops
 * easing toward new palette colours over 1200 ms), an Apple-Music-style header (45 dp sleeve,
 * marquee'd title, artist row that navigates to the artist page, like / share-lyrics /
 * more-vert), SimpMusic's own Classic lyrics renderer filling the middle, and a bottom
 * control block — slider with the 8×18 dp thumb, time row, transport, info/queue buttons —
 * that AUTO-HIDES after four seconds and comes back on any tap, exactly like the original.
 *
 * Only the SimpMusic player style reaches this: the lyrics card's "Show" affordance opens it
 * (user request 2026-09-05 — it previously opened the app's shared LyricsScreen instead of
 * SimpMusic's own lyrics page). The lyric DATA is the same store every other renderer reads
 * (playerConnection.currentLyrics through SimpMusicLyrics) — real providers, no second
 * implementation.
 */

package moe.rukamori.archivetune.ui.player.simpmusic

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.hilt.navigation.compose.hiltViewModel
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetMenu
import moe.rukamori.archivetune.ui.component.BottomSheetPage
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import android.os.Build
import androidx.media3.common.Player
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/** SimpMusic's fullscreen-lyrics side gutter (its FULLSCREEN_LYRICS_GUTTER). */
private val LyricsGutter = 50.dp

/** How long the bottom controls stay up before auto-hiding (SimpMusic: 4 s). */
private const val CONTROLS_AUTO_HIDE_MS = 4_000L

@Composable
internal fun SimpMusicFullscreenLyricsSheet(
    mediaMetadata: MediaMetadata,
    playerConnection: PlayerConnection,
    navController: NavController,
    bottomSheetPageState: BottomSheetPageState,
    color: Color,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val menuState = LocalMenuState.current

    // ── Anchored Apple-Music-style overflow popup (2026-09-05) ──
    // Per user request: "i don't want the bottomsheet lyrics overflow menu.
    // I want the one used in apple music style" — the header's more button now
    // opens the same anchored popup the Apple Music player style uses
    // (AnchoredLyricsOverflowMenu: scales up from the more icon with a
    // frosted-glass blur), rendered INSIDE this sheet's dialog window so it
    // is always above the lyrics. It replaces the shared menuState/
    // BottomSheetMenu slide-up card that previously hosted PlayerMenu here.
    var showAnchoredLyricsMenu by remember { mutableStateOf(false) }
    var moreIconBounds by remember {
        mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    }

    // Backdrop that records THIS sheet's content (the wandering gradient +
    // lyrics) so the popup's drawBackdrop sampler blurs what is actually
    // behind the menu inside this dialog window. Android 12+ only (kyant
    // RuntimeShader); below that the popup falls back to its dark tint.
    // `Modifier.layerBackdrop(popupBackdrop)` is applied to the inner
    // content Box below ONLY while the popup is open — zero steady-state
    // recording cost for the lyrics scroll while the menu is closed. The
    // popup renders as a SIBLING of that Box (never nested inside it) to
    // avoid the kyant render-feedback loop.
    val popupBackdrop: PlatformBackdrop? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
        } else {
            null
        }

    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val liked = currentSong?.song?.liked == true
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    // ── Automatic AI translation (2026-09-05) ──────────────────────────────
    // Mirrors the LaunchedEffect in AppleMusicPlayer.kt / LyricsScreen.kt.
    // The SimpMusic lyrics screen previously had NO auto-translate trigger
    // at all (user report: "Auto translation and auto romanisation doesn't
    // work in simpmusic"), so the setting silently did nothing here.
    val (autoTranslateLyrics) = rememberPreference(AutoTranslateLyricsKey, defaultValue = false)
    val (translatorTargetLang) = rememberPreference(TranslatorTargetLangKey, defaultValue = "")
    // "Don't auto translate these languages" — read here and passed
    // explicitly, exactly like the Apple Music player does.
    val (autoTranslateExcludedLanguages) =
        rememberPreference(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
    val lyricsMenuViewModel: LyricsMenuViewModel = hiltViewModel()
    val translationDismissedMediaIds by lyricsMenuViewModel.translationDismissedMediaIds
        .collectAsStateWithLifecycle()
    LaunchedEffect(
        mediaMetadata.id,
        currentLyricsEntity?.lyrics,
        currentLyricsEntity?.source,
        autoTranslateLyrics,
        translatorTargetLang,
        autoTranslateExcludedLanguages,
        translationDismissedMediaIds,
    ) {
        if (!autoTranslateLyrics) return@LaunchedEffect
        val snapshot = currentLyricsEntity ?: return@LaunchedEffect
        val text = snapshot.lyrics ?: return@LaunchedEffect
        if (text.isBlank() || text == LYRICS_NOT_FOUND) return@LaunchedEffect
        // Skip when these lyrics were already AI-translated AND actually
        // carry translation content (same retry guard as the other screens).
        if (snapshot.source == LyricsEntity.Source.AI_TRANSLATION.value &&
            LyricsUtils.hasTranslation(text)
        ) return@LaunchedEffect
        // Respect an "Undo Translation" dismissal for this song.
        if (mediaMetadata.id in translationDismissedMediaIds) return@LaunchedEffect
        if (!LyricsUtils.shouldAutoTranslate(
                lyrics = text,
                targetLanguage = translatorTargetLang,
                excludedLanguageCodes = autoTranslateExcludedLanguages,
            )
        ) {
            return@LaunchedEffect
        }
        lyricsMenuViewModel.translateLyricsWithAi(
            mediaMetadata = mediaMetadata,
            lyrics = text,
            targetLanguage = translatorTargetLang,
        )
    }

    val hasLyrics = currentLyricsEntity?.lyrics
        ?.let { it.isNotBlank() && it != LYRICS_NOT_FOUND } == true

    // Keep the screen awake while a lyrics page is on (SimpMusic's KeepScreenOn()).
    DisposableEffect(view, hasLyrics) {
        if (hasLyrics) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // ── Auto-hide controls state (SimpMusic's showControlButtons) ────────────────────
    var showControlButtons by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(showControlButtons) {
        if (showControlButtons) {
            delay(CONTROLS_AUTO_HIDE_MS)
            showControlButtons = false
        }
    }

    // ── Position polling for the slider ───────────────────────────────────────────────
    // 2026-09-05 fix (user report: "the lyrics lines don't automatically
    // proceed to the next line in simpmusic player style"): the poll used to
    // latch `sliderPosition` to the playhead ONCE (first tick) and then stop
    // updating it (the `sliderPosition < 0` guard flipped false forever), so
    // the lyrics' sliderPositionProvider kept returning that one STALE
    // position and the lyrics froze on the line that was current when the
    // sheet opened. Scrubbing is now tracked by its own flag: the playhead
    // refreshes the slider + labels every tick while the user is NOT
    // dragging, and the lyrics provider only returns a value WHILE dragging
    // (a live seek preview) — the rest of the time the lyrics self-poll the
    // player and advance line by line.
    var sliderPosition by remember { mutableLongStateOf(-1L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(mediaMetadata.id, isPlaying) {
        while (isActive) {
            val d = playerConnection.player.duration
            if (d > 0) duration = d
            if (!isScrubbing) {
                sliderPosition = playerConnection.player.currentPosition.coerceAtLeast(0L)
            }
            delay(200L)
        }
    }

    // ── Animated gradient background (SimpMusic's five-stop wander) ───────────────────
    // animateColorAsState instead of Animatable<Color>: the single-argument Animatable
    // factory only exists for Float, and the colours here only ever ease toward the
    // current palette anyway.
    val startColor by animateColorAsState(color, tween(1200, easing = FastOutSlowInEasing))
    val midColor1 by animateColorAsState(color.copy(alpha = 0.95f), tween(1200, easing = FastOutSlowInEasing))
    val midColor2 by animateColorAsState(color.copy(alpha = 0.85f), tween(1200, easing = FastOutSlowInEasing))
    val endColor by animateColorAsState(Color.Black, tween(1200, easing = FastOutSlowInEasing))
    val gradientTransition = rememberInfiniteTransition(label = "lyricsGradient")
    val animatedAngle by gradientTransition.animateFloat(
        initialValue = -45f,
        targetValue = 45f,
        animationSpec =
            infiniteRepeatable(
                // 2026-09-05: 6 s read as a fast strobing wander on a phone
                // screen (user report: "the background changes at extremely
                // fast speed"); 24 s keeps the same travel but drifts at a
                // quarter of the speed.
                animation = tween(durationMillis = 24_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "lyricsGradientAngle",
    )
    val animatedOffsetX by gradientTransition.animateFloat(
        initialValue = -1500f,
        targetValue = 1500f,
        animationSpec =
            infiniteRepeatable(
                // Same 4x slowdown as the angle: 8 s -> 32 s per sweep.
                animation = tween(durationMillis = 32_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "lyricsGradientOffsetX",
    )
    val animatedOffsetY by gradientTransition.animateFloat(
        initialValue = -1000f,
        targetValue = 1000f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 32_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "lyricsGradientOffsetY",
    )

    // Nested sheets the header's buttons can open (SimpMusic opens its own queue / info
    // sheets from here the same way).
    var queueOpen by rememberSaveable { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val shareLyrics: () -> Unit = {
        val text = currentLyricsEntity?.lyrics
        if (!text.isNullOrBlank()) {
            val body = text.lineSequence().joinToString("\n") { it.substringAfter("]") }
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, body)
                    },
                    null,
                ),
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            // Leaving the lyrics page must not strand overlays opened from inside it: the
            // menu / details-page hosts below render in THIS sheet's dialog window, but the
            // state they share is app-wide — without dismissing it here, MainActivity's
            // app-window hosts would pick the still-open state up the moment this dialog
            // closes and the menu would materialise over the player (the exact "when I exit
            // the lyrics screen it's there" report).
            menuState.dismiss()
            bottomSheetPageState.dismiss()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color.Black,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RectangleShape,
        modifier =
            Modifier
                .fillMaxHeight()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    // Show controls on tap — SimpMusic's tap-anywhere reveal.
                    showControlButtons = true
                },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Inner content Box — records the sheet's gradient + lyrics +
            // controls into `popupBackdrop` via `Modifier.layerBackdrop(...)`
            // WHILE the anchored overflow popup is open, so the popup's
            // drawBackdrop sampler blurs the actual content behind the menu
            // (real frosted glass, same as the Apple Music player style).
            // The popup renders as a SIBLING of this Box (after the overlay
            // hosts below) — nesting it inside this layer-capturing Box would
            // create the kyant render-feedback loop. While the popup is
            // closed the modifier is a no-op, so the lyrics scroll pays zero
            // steady-state GPU recording cost.
            Box(
                modifier =
                    Modifier.fillMaxSize().let { base ->
                        if (popupBackdrop != null && showAnchoredLyricsMenu) {
                            base.layerBackdrop(popupBackdrop)
                        } else {
                            base
                        }
                    },
            ) {
            // Animated gradient background.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors =
                                    listOf(
                                        startColor,
                                        midColor1,
                                        midColor2,
                                        endColor.copy(alpha = 0.9f),
                                        endColor,
                                    ),
                                start =
                                    Offset(
                                        x = animatedOffsetX + (cos(animatedAngle * Math.PI.toFloat() / 180f) * 800f),
                                        y = animatedOffsetY + (sin(animatedAngle * Math.PI.toFloat() / 180f) * 800f),
                                    ),
                                end =
                                    Offset(
                                        x = animatedOffsetX + 2500f + (cos((animatedAngle + 180f) * Math.PI.toFloat() / 180f) * 800f),
                                        y = animatedOffsetY + 2500f + (sin((animatedAngle + 180f) * Math.PI.toFloat() / 180f) * 800f),
                                    ),
                            ),
                        ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // Notch-safe insets: the top floors with the cached status-bar inset
                        // (LocalStableSystemBarsTopPadding) so a hidden status bar can't drop
                        // the header under the cutout; the bottom uses the nav bar.
                        .padding(
                            top = LocalStableSystemBarsTopPadding.current,
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        ),
            ) {
                // ── Header: sleeve + title/artist + like + share + more ────────────────
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = mediaMetadata.thumbnailUrl?.highRes(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(45.dp)
                                .clip(RoundedCornerShape(8.dp)),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = mediaMetadata.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            modifier =
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                ),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.clickable {
                                    mediaMetadata.artists
                                        .firstOrNull { !it.id.isNullOrBlank() }
                                        ?.id?.let { id -> navController.navigate("artist/$id") }
                                },
                        ) {
                            Text(
                                text = mediaMetadata.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        animationMode = MarqueeAnimationMode.Immediately,
                                    ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(onClick = playerConnection::toggleLike, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painter =
                                painterResource(
                                    if (liked) R.drawable.simpmusic_favorite else R.drawable.simpmusic_favorite_border,
                                ),
                            contentDescription = stringResource(R.string.action_like),
                            tint = if (liked) MaterialTheme.colorScheme.error else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    if (hasLyrics) {
                        IconButton(onClick = shareLyrics) {
                            Icon(
                                painter = painterResource(R.drawable.simpmusic_share),
                                contentDescription = stringResource(R.string.share),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = { showAnchoredLyricsMenu = true },
                        modifier =
                            Modifier
                                .onGloballyPositioned { coords ->
                                    // Report the icon's bounds in this dialog
                                    // window's root coordinates so the anchored
                                    // popup can align its top-right corner with
                                    // the icon. boundsInRoot() isn't available on
                                    // this Compose version — compute the Rect from
                                    // positionInRoot() + size.
                                    val pos = coords.positionInRoot()
                                    val sz = coords.size
                                    moreIconBounds =
                                        androidx.compose.ui.geometry.Rect(
                                            offset = pos,
                                            size =
                                                androidx.compose.ui.geometry.Size(
                                                    width = sz.width.toFloat(),
                                                    height = sz.height.toFloat(),
                                                ),
                                        )
                                },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.simpmusic_more_vert),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // ── Lyrics — expands across the remaining height ─────────────────────
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = LyricsGutter),
                ) {
                    if (hasLyrics) {
                        // The provider is null unless the user is scrubbing the sheet's own
                        // slider, so SimpMusicLyrics self-polls the player — the same contract
                        // the lyrics card uses.
                        SimpMusicLyrics(
                            // Only report a position while the user is actually
                            // dragging the sheet's slider (a live seek preview);
                            // null the rest of the time so SimpMusicLyrics
                            // self-polls the player and the lines follow the
                            // song (see the polling fix above).
                            sliderPositionProvider = { if (isScrubbing) sliderPosition else null },
                            lyricsSyncOffset = 0,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.save_canvas_variant_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // ── Slider + time row — always visible ───────────────────────────────
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                    val safeDuration = if (duration > 0) duration else 1L
                    val shown = sliderPosition.coerceIn(0L, safeDuration)
                    Slider(
                        value = shown.toFloat() / safeDuration.toFloat(),
                        onValueChange = {
                            isScrubbing = true
                            sliderPosition = (it * safeDuration).toLong()
                        },
                        onValueChangeFinished = {
                            playerConnection.player.seekTo(sliderPosition)
                            isScrubbing = false
                        },
                        track = { sliderState ->
                            SliderDefaults.Track(
                                modifier = Modifier.height(5.dp),
                                enabled = true,
                                sliderState = sliderState,
                                colors =
                                    SliderDefaults.colors().copy(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White,
                                        inactiveTrackColor = Color.Transparent,
                                    ),
                                thumbTrackGapSize = 0.dp,
                                drawTick = { _, _ -> },
                                drawStopIndicator = null,
                            )
                        },
                        thumb = {
                            SliderDefaults.Thumb(
                                modifier = Modifier.height(18.dp).width(8.dp).padding(vertical = 4.dp),
                                thumbSize = DpSize(8.dp, 8.dp),
                                interactionSource = remember { MutableInteractionSource() },
                                colors =
                                    SliderDefaults.colors().copy(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color.White,
                                    ),
                                enabled = true,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = clockTime(shown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Left,
                        )
                        Text(
                            text = if (duration > 0) clockTime(duration) else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Right,
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                }

                // ── Transport + bottom buttons — auto-hide after 4 s ─────────────────
                AnimatedVisibility(
                    visible = showControlButtons,
                    enter = expandVertically(tween(300)),
                    exit = shrinkVertically(tween(300)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LyricsTransportIcon(
                            painter = painterResource(R.drawable.simpmusic_shuffle),
                            contentDescription = stringResource(R.string.shuffle),
                            tint = if (shuffleEnabled) MaterialTheme.colorScheme.error else Color.White,
                            iconSize = 32.dp,
                            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
                        )
                        LyricsTransportIcon(
                            painter = painterResource(R.drawable.simpmusic_skip_previous),
                            contentDescription = stringResource(R.string.widget_previous),
                            tint = Color.White.copy(alpha = if (canSkipPrevious) 1f else 0.4f),
                            enabled = canSkipPrevious,
                            iconSize = 42.dp,
                            onClick = playerConnection::seekToPrevious,
                        )
                        LyricsTransportIcon(
                            painter =
                                painterResource(
                                    if (isPlaying) R.drawable.simpmusic_pause_circle else R.drawable.simpmusic_play_circle,
                                ),
                            contentDescription = stringResource(if (isPlaying) R.string.widget_pause else R.string.play),
                            tint = Color.White,
                            iconSize = 72.dp,
                            onClick = { playerConnection.player.togglePlayPause() },
                        )
                        LyricsTransportIcon(
                            painter = painterResource(R.drawable.simpmusic_skip_next),
                            contentDescription = stringResource(R.string.next),
                            tint = Color.White.copy(alpha = if (canSkipNext) 1f else 0.4f),
                            enabled = canSkipNext,
                            iconSize = 42.dp,
                            onClick = playerConnection::seekToNext,
                        )
                        LyricsTransportIcon(
                            painter =
                                painterResource(
                                    if (repeatMode == Player.REPEAT_MODE_ONE) {
                                        R.drawable.simpmusic_repeat_one
                                    } else {
                                        R.drawable.simpmusic_repeat
                                    },
                                ),
                            contentDescription =
                                stringResource(
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                        Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                        else -> R.string.repeat_mode_off
                                    },
                                ),
                            tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White else MaterialTheme.colorScheme.error,
                            iconSize = 32.dp,
                            onClick = {
                                playerConnection.player.repeatMode =
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showControlButtons,
                    enter = expandVertically(tween(300)),
                    exit = shrinkVertically(tween(300)),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .height(32.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 40.dp),
                    ) {
                        IconButton(
                            modifier = Modifier.size(24.dp).align(Alignment.CenterStart),
                            onClick = { bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) } },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.simpmusic_info),
                                tint = Color.White,
                                contentDescription = stringResource(R.string.details),
                            )
                        }
                        IconButton(
                            modifier = Modifier.size(24.dp).align(Alignment.CenterEnd),
                            onClick = { queueOpen = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.simpmusic_queue_music),
                                tint = Color.White,
                                contentDescription = stringResource(R.string.queue),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
                if (!showControlButtons) {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // ── In-sheet hosts for the app's overlay systems ─────────────────────
            // This sheet is a ModalBottomSheet, which is a real Android dialog
            // window that floats ABOVE the app window where MainActivity hosts
            // BottomSheetMenu and BottomSheetPage. A menu (or the details page)
            // opened from inside the lyrics screen therefore rendered in the app
            // window, BEHIND this dialog: tapping the header's overflow button
            // looked dead, and the menu only materialised over the player after
            // the lyrics screen was dismissed (user report 2026-09-05: "it doesn't
            // open but when I exit the lyrics screen it's there"). Hosting both
            // systems INSIDE the sheet's content — this Box, in the dialog window,
            // composed AFTER the lyrics column so they draw above it — makes the
            // shared `bottomSheetPageState.show { ShowMediaInfo(...) }` calls (the
            // info button at the bottom of the lyrics page) render visibly. The
            // app-window instances still compose beneath the dialog but are
            // unreachable (the dialog consumes touches), so exactly one instance
            // is interactive.
            //
            // [2026-09-05] The header's more button no longer opens the shared
            // menuState menu from this sheet — it opens the anchored
            // Apple-Music-style popup below instead (per user request). The
            // BottomSheetMenu host stays for the details page's dialogs and any
            // other menuState consumer that may run inside this sheet.
            BottomSheetMenu(
                state = menuState,
                background = Color(0xF01C1C1E),
            )
            BottomSheetPage(state = bottomSheetPageState)
            } // end inner content Box (anchored-popup backdrop recording layer)

            // ── Anchored Apple-Music-style overflow popup ───────────────────────
            // Rendered as the LAST child of the sheet's content Box so it draws
            // above everything else in this dialog window (gradient, lyrics,
            // controls, header, overlay hosts). The popup manages its own
            // enter/exit animations (scale + alpha from the more icon's corner)
            // and positions itself via `moreIconBounds` (captured by the
            // onGloballyPositioned wired to the header's more IconButton).
            // This is the exact pattern AppleMusicPlayer.kt uses — the same
            // menu the Apple Music player style shows over its lyrics.
            if (showAnchoredLyricsMenu) {
                AnchoredLyricsOverflowMenu(
                    iconBoundsInRoot = moreIconBounds,
                    lyricsProvider = { currentLyricsEntity },
                    mediaMetadataProvider = { mediaMetadata },
                    lyricsSyncOffset = 0,
                    onLyricsSyncOffsetChange = {},
                    onDismiss = { showAnchoredLyricsMenu = false },
                    backdrop = popupBackdrop,
                )
            }
        }
    }

    if (queueOpen) {
        SimpMusicQueueSheet(
            playerConnection = playerConnection,
            navController = navController,
            onDismiss = { queueOpen = false },
        )
    }
}

/** One transport control: a circular ripple cell holding a centred glyph (SimpMusicControl's shape). */
@Composable
private fun RowScope.LyricsTransportIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    tint: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** `mm:ss`, zero-padded, the way SimpMusic's formatDuration writes it. */
private fun clockTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
}
