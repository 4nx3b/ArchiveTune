/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * In-app chat notification popup for Listen Together: while the app is in the
 * FOREGROUND but the chat screen is closed, incoming room messages surface as
 * a single heads-up card near the top of the screen — liquid glass (the same
 * frosted + lens treatment as the app's other glass surfaces, sampled from
 * the throttled menu recorder) while the mode is on, and the exact same card
 * opaque when it is off — stacking messages that arrive together in one
 * scrolling list, with a prominent quick-reply action. Mentions additionally
 * get a mark-as-read action and never auto-dismiss.
 *
 * 2026-09: the card is capped at a fixed maximum height (the message list
 * autoscrolls inside it instead of the card growing without bound), is
 * swipe-dismissible horizontally, adapts its glass scrim/ink polarity to the
 * theme so light mode stays readable, and carries ONE consistent hairline
 * border treatment in both glass and opaque modes (the old mixed
 * white-hairline / theme-outline / 14dp-shadow edges read as inconsistent
 * black borders around the card).
 *
 * 2026-09 (round 2): the glass edge's refraction strength is now a FIXED
 * pixel amount instead of scaling with the card's dimensions — the lens used
 * to displace up to size/5 px at the inner edge, which grew with every stacked
 * message and read as a dark band INSIDE the card that got more visible the
 * taller the card became. The reply action is an icon-only button with clear
 * space from the stacked messages above it.
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.listentogether.ChatMessagePayload
import moe.rukamori.archivetune.listentogether.RepliedMessage
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.sign

/** One stacked entry of the in-app notification card. */
data class InAppNotificationEntry(
    val message: ChatMessagePayload,
    /** True when the message @-mentions the local user. */
    val isMention: Boolean,
)

/** The card never grows past this; the message list autoscrolls inside. */
private val InAppNotificationMaxCardHeight = 320.dp

/**
 * The in-app chat notification card. Hosted at the top of the app window
 * (over whatever screen is showing, but BELOW the shade conversation
 * notification that still handles the backgrounded case).
 *
 * Behaviour contract (per the feature request):
 *  - a REGULAR message auto-dismisses after a few seconds;
 *  - a MENTION persists until the user acts on it, and shows both Reply and
 *    Mark-as-read;
 *  - several messages arriving together never spawn separate cards — they
 *    stack inside this one, the card capped at [InAppNotificationMaxCardHeight]
 *    with the list auto-scrolling to the newest;
 *  - tapping the card opens the chat screen;
 *  - swiping the card horizontally dismisses it;
 *  - the reply field sends straight into the room (the message rides the
 *    normal chat relay) and dismisses the card.
 */
@Composable
fun InAppChatNotificationPopup(
    entries: List<InAppNotificationEntry>,
    backdrop: Backdrop?,
    onOpenChat: () -> Unit,
    onReply: (text: String) -> Unit,
    onMarkAsRead: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasMention = entries.any { it.isMention }
    val cardShape = RoundedCornerShape(22.dp)
    val glassActive = backdrop != null

    // Theme polarity for the glass scrim: light theme puts a bright scrim
    // under the frost with dark ink, dark theme the reverse. The old card
    // always painted Black@45% + white ink, which read fine in dark mode but
    // left light-mode content washed out and the hairline border glowing.
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val cardInk = when {
        glassActive && isLightTheme -> Color(0xFF1C1B1F)
        glassActive -> Color.White.copy(alpha = 0.94f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val cardSecondaryInk = when {
        glassActive && isLightTheme -> Color(0xFF1C1B1F).copy(alpha = 0.68f)
        glassActive -> Color.White.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glassScrim = if (isLightTheme) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.45f)

    // ONE hairline treatment for both modes: the same 0.75dp stroke, tinted to
    // the surface it sits on. The glass card keeps its faint white rim (over a
    // dark scrim) and gains a subtle dark rim in light theme; the opaque card
    // gains the matching hairline it never had — which is why its edge used to
    // read as an inconsistent black band against the glass card's white one.
    val hairline = when {
        glassActive && isLightTheme -> Color(0xFF1C1B1F).copy(alpha = 0.10f)
        glassActive -> Color.White.copy(alpha = 0.22f)
        isLightTheme -> MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.72f)
    }

    // Swipe-to-dismiss: horizontal drag translates and fades the card; past a
    // fraction of its width the gesture commits to dismissal, otherwise it
    // springs back. Vertical drags (scrolling the message list) are untouched.
    val swipeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 96.dp.toPx() }

    val cardModifier =
        (
            if (glassActive) {
                // Liquid glass: frost plus the lens refraction the app's glass
                // pills carry, over the throttled menu recorder (the only recorder
                // whose subtree excludes this card). The lens displacement is a
                // FIXED pixel amount (the same recipe as the chat overflow
                // popup): it must NOT scale with the card's size, or the inner
                // edge builds an ever-wider dark refraction band as messages
                // stack and the card grows.
                Modifier.drawBackdrop(
                    backdrop = backdrop!!,
                    effects = {
                        colorControls(saturation = 1.6f)
                        blur(20.dp.toPx())
                        lens(
                            refractionHeight = 16f.dp.toPx(),
                            refractionAmount = 32f.dp.toPx(),
                            depthEffect = false,
                            chromaticAberration = false,
                        )
                    },
                    onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                    onDrawSurface = { drawRect(glassScrim) },
                    shape = { cardShape },
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, cardShape)
            }
        )
            .border(0.75.dp, hairline, cardShape)

    AnimatedVisibility(
        visible = entries.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = cardShape,
            color = Color.Transparent,
            // A modest ambient shadow: the previous 14dp elevation cast a hard
            // dark band around the card that read as yet another border.
            shadowElevation = 5.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .graphicsLayer {
                        translationX = swipeOffset.value
                        alpha = 1f - (abs(swipeOffset.value) / (dismissThresholdPx * 2f))
                            .coerceIn(0f, 1f)
                    }
                    .pointerInput(entries.isNotEmpty()) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next =
                                    (swipeOffset.value + dragAmount)
                                        .coerceIn(-dismissThresholdPx * 1.8f, dismissThresholdPx * 1.8f)
                                coroutineScope.launch {
                                    swipeOffset.snapTo(next)
                                }
                            },
                            onDragEnd = {
                                val overThreshold = abs(swipeOffset.value) >= dismissThresholdPx
                                if (overThreshold) {
                                    // Commit: fling the card off the side, then dismiss.
                                    coroutineScope.launch {
                                        swipeOffset.animateTo(
                                            dismissThresholdPx * 2.2f * sign(swipeOffset.value),
                                            tween(180),
                                        )
                                        onDismiss()
                                        swipeOffset.snapTo(0f)
                                    }
                                } else {
                                    coroutineScope.launch {
                                        swipeOffset.animateTo(0f, tween(220))
                                    }
                                }
                            },
                        )
                    }
                    .clip(cardShape)
                    .then(cardModifier),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = InAppNotificationMaxCardHeight)
                        .clickable(onClick = onOpenChat)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                // Breathing room between the header, the stacked messages and
                // the action row — the actions used to sit flush against the
                // last message.
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header: title + dismiss.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.chat_msg),
                        contentDescription = null,
                        tint = cardInk,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (hasMention) {
                            stringResource(R.string.listen_together_in_app_notification_mention_title)
                        } else {
                            stringResource(R.string.listen_together_in_app_notification_title)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = cardInk,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = null,
                            tint = cardSecondaryInk,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // The stacked messages: newest last, auto-scrolled so bursts
                // of messages roll inside the single card. weight(1f, fill =
                // false): the row grows with its content until the CARD hits
                // its cap, at which point this is the child that compresses —
                // so the header and actions keep their size and the list
                // scrolls instead of the popup growing ever taller.
                val listState = rememberScrollState()
                LaunchedEffect(entries.size) {
                    if (entries.isNotEmpty()) {
                        delay(80)
                        listState.animateScrollTo(listState.maxValue)
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 168.dp)
                            .verticalScroll(listState),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.takeLast(8).forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ChatAvatar(
                                userId = entry.message.userId,
                                fallbackName = entry.message.username,
                                size = 26.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.message.username,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = cardInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val body = entry.message.sharedTrack?.let {
                                    stringResource(R.string.listen_together_chat_shared_song, it.title)
                                } ?: if (entry.message.gifUrl != null) {
                                    stringResource(R.string.listen_together_chat_sent_gif)
                                } else {
                                    entry.message.message
                                }
                                Text(
                                    text = body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cardSecondaryInk,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Actions: Reply is an ICON-ONLY button (just the reply glyph,
                // no label — it sat too close to the messages with a text
                // button's visual weight); Mark-as-read stays a quiet text
                // button on mentions. Same row in both glass and opaque modes.
                var replying by remember { mutableStateOf(false) }
                var replyText by remember { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = { replying = !replying },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.reply),
                            contentDescription = stringResource(
                                R.string.listen_together_in_app_notification_reply,
                            ),
                            tint = cardInk,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    if (hasMention) {
                        TextButton(
                            onClick = onMarkAsRead,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.listen_together_in_app_notification_mark_read),
                                color = cardSecondaryInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                if (replying) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        placeholder = {
                            Text(stringResource(R.string.listen_together_in_app_notification_reply_hint))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = hairline,
                            focusedTextColor = cardInk,
                            unfocusedTextColor = cardInk,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (replyText.isNotBlank()) {
                                    onReply(replyText.trim())
                                    replyText = ""
                                    replying = false
                                    onDismiss()
                                }
                            },
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (replyText.isNotBlank()) {
                                        onReply(replyText.trim())
                                        replyText = ""
                                        replying = false
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.send_chat),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * State controller for the in-app chat notification: collects live room
 * messages while the feature is enabled, the chat's one-tap mute is off, the
 * app shows a room and the chat screen is CLOSED, stacks them into the
 * popup's entry list, auto-dismisses non-mention cards after a short delay
 * (mentions persist until acted on) and wires the popup's actions (open
 * chat / reply / mark-as-read).
 */
@Composable
fun InAppChatNotificationsHost(
    manager: moe.rukamori.archivetune.listentogether.ListenTogetherManager,
    navController: androidx.navigation.NavController,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    onActiveChanged: (Boolean) -> Unit = {},
) {
    val inAppEnabled by rememberPreference(
        moe.rukamori.archivetune.constants.ListenTogetherInAppNotificationsKey,
        true,
    )
    val chatMuted by rememberPreference(
        moe.rukamori.archivetune.constants.ListenTogetherChatMutedKey,
        false,
    )
    val entries = remember { mutableStateListOf<InAppNotificationEntry>() }

    // The glass card needs the menu recorder RUNNING to have anything to
    // sample: report the card's presence so the activity keeps the throttled
    // backdrop recording (it otherwise only records while a menu is open —
    // which is exactly why the card used to render fully transparent).
    LaunchedEffect(inAppEnabled, chatMuted, entries.isNotEmpty()) {
        onActiveChanged(inAppEnabled && !chatMuted && entries.isNotEmpty())
    }

    // Muting mid-conversation retires whatever is on screen.
    LaunchedEffect(chatMuted) {
        if (chatMuted) entries.clear()
    }

    // Opening the chat screen retires the popup — the conversation itself is
    // now visible.
    val chatScreenVisible by manager.chatScreenVisible.collectAsState()
    LaunchedEffect(chatScreenVisible) {
        if (chatScreenVisible) entries.clear()
    }

    // Regular (non-mention) cards are transient: give them a few seconds of
    // fame, then clear the stack. Mention-bearing cards wait for the user.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && entries.none { it.isMention }) {
            delay(6000)
            entries.clear()
        }
    }

    if (inAppEnabled && !chatMuted) {
        LaunchedEffect(Unit) {
            manager.chatMessageEvents.collect { payload ->
                if (!manager.isInRoom) return@collect
                if (manager.chatScreenVisible.value) {
                    entries.clear()
                    return@collect
                }
                val myName = manager.currentUsername
                val isMention = myName != null &&
                    payload.mentions.any { it.equals(myName, ignoreCase = true) }
                entries.add(InAppNotificationEntry(payload, isMention))
                if (entries.size > 8) entries.removeAt(0)
            }
        }
    }

    InAppChatNotificationPopup(
        entries = entries.toList(),
        backdrop = backdrop,
        onOpenChat = {
            entries.clear()
            runCatching { navController.navigate("listen_together/chat") }
        },
        onReply = { text ->
            val latest = entries.lastOrNull()?.message
            if (latest != null) {
                manager.sendChatMessage(
                    text,
                    RepliedMessage(latest.username, latest.message),
                )
            } else {
                manager.sendChatMessage(text)
            }
        },
        onMarkAsRead = {
            manager.markChatAsRead()
            entries.clear()
        },
        onDismiss = { entries.clear() },
        modifier = modifier,
    )
}
