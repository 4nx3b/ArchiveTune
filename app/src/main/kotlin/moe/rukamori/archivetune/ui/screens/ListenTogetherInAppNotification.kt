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
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.listentogether.ChatMessagePayload
import moe.rukamori.archivetune.listentogether.RepliedMessage
import moe.rukamori.archivetune.utils.rememberPreference

/** One stacked entry of the in-app notification card. */
data class InAppNotificationEntry(
    val message: ChatMessagePayload,
    /** True when the message @-mentions the local user. */
    val isMention: Boolean,
)

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
 *    stack inside this one, the list auto-scrolling to the newest;
 *  - tapping the card opens the chat screen;
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

    // One ink for the whole card: over the glass scrim the content stays the
    // light treatment every glass surface uses; on the opaque card it follows
    // the theme so light theme keeps a readable dark ink. Same layout and
    // dimensions either way — only the surface changes.
    val cardInk = if (glassActive) Color.White.copy(alpha = 0.94f) else MaterialTheme.colorScheme.onSurface
    val cardSecondaryInk =
        if (glassActive) {
            Color.White.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    val cardModifier =
        if (backdrop != null) {
            // Liquid glass: frost plus the lens refraction the app's glass
            // pills carry, over the throttled menu recorder (the only recorder
            // whose subtree excludes this card).
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    effects = {
                        colorControls(saturation = 1.6f)
                        blur(24.dp.toPx())
                        lens(
                            refractionHeight = 22f.dp.toPx(),
                            refractionAmount = size.minDimension / 5f,
                            depthEffect = false,
                            chromaticAberration = false,
                        )
                    },
                    onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.45f)) },
                    shape = { cardShape },
                )
                .border(0.75.dp, Color.White.copy(alpha = 0.22f), cardShape)
        } else {
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, cardShape)
        }

    AnimatedVisibility(
        visible = entries.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = cardShape,
            color = Color.Transparent,
            shadowElevation = 14.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(cardShape)
                    .then(cardModifier),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .clickable(onClick = onOpenChat)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                // of messages roll inside the single card.
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

                // Actions: Reply always (a filled tonal button — it has to read
                // as the card's primary action at a glance); Mark-as-read on
                // mentions. Same row, same dimensions in both glass and opaque
                // modes.
                var replying by remember { mutableStateOf(false) }
                var replyText by remember { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(
                        onClick = { replying = !replying },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.reply),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.listen_together_in_app_notification_reply),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
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
                            unfocusedBorderColor = cardSecondaryInk.copy(alpha = 0.45f),
                            focusedTextColor = cardInk,
                            unfocusedTextColor = cardInk,
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
 * messages while the feature is enabled, the app shows a room and the chat
 * screen is CLOSED, stacks them into the popup's entry list, auto-dismisses
 * non-mention cards after a short delay (mentions persist until acted on)
 * and wires the popup's actions (open chat / reply / mark-as-read).
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
    val entries = remember { mutableStateListOf<InAppNotificationEntry>() }

    // The glass card needs the menu recorder RUNNING to have anything to
    // sample: report the card's presence so the activity keeps the throttled
    // backdrop recording (it otherwise only records while a menu is open —
    // which is exactly why the card used to render fully transparent).
    LaunchedEffect(inAppEnabled, entries.isNotEmpty()) {
        onActiveChanged(inAppEnabled && entries.isNotEmpty())
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

    if (inAppEnabled) {
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
