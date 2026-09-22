/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Ported from vivi-music (beta branch) ui/screens/CommentTogether.kt (GPL-3.0),
 * rebuilt with avatars, reactions, pins, edits, deletes, swipe-to-reply,
 * typing indicators, GIF attachments, @mentions and per-username history.
 */

package moe.rukamori.archivetune.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalListenTogetherManager
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListenTogetherServerUrlKey
import moe.rukamori.archivetune.listentogether.ChatMessagePayload
import moe.rukamori.archivetune.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.listentogether.ListenTogetherProtocol
import moe.rukamori.archivetune.listentogether.RepliedMessage
import moe.rukamori.archivetune.listentogether.TrackInfo
import moe.rukamori.archivetune.ui.component.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentTogetherScreen(navController: NavController) {
    val context = LocalContext.current
    val manager = LocalListenTogetherManager.current ?: return
    val messages by manager.chatMessages.collectAsState()
    val userId by manager.userId.collectAsState()
    val roomState by manager.roomState.collectAsState()
    val typingUsers by manager.typingUsers.collectAsState()
    val mentionCount by manager.mentionCount.collectAsState()
    val windowInsets = LocalPlayerAwareWindowInsets.current

    var textInput by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ChatMessagePayload?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessagePayload?>(null) }
    var actionTarget by remember { mutableStateOf<MessageActionTarget?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showSongPicker by remember { mutableStateOf(false) }
    var showGifPicker by remember { mutableStateOf(false) }
    var attachmentAnchor by remember { mutableStateOf<Rect?>(null) }
    var jumpTargetKey by remember { mutableStateOf<String?>(null) }

    // metroserver (The Meowery) speaks a protobuf protocol with no chat message
    // type at all — the composer is replaced by an explanatory notice there.
    val serverUrl by rememberPreference(ListenTogetherServerUrlKey, ListenTogetherServers.defaultServerUrl)
    val chatSupported by remember(serverUrl) {
        mutableStateOf(ListenTogetherServers.findByUrl(serverUrl)?.protocol != ListenTogetherProtocol.PROTOBUF)
    }

    val lazyListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Local liquid-glass source for the anchored popups: records the chat
    // content ONLY while a popup is open. The popups are composed as SIBLINGS
    // below, so sampling this backdrop cannot recurse — drawing from the
    // app-wide LocalLiquidGlassBackdrop here (the popups live inside the
    // NavHost subtree that backdrop records) caused a circular-rendering
    // SIGSEGV on long-press.
    val chatGlassSource = rememberLayerBackdrop()
    val globalGlassEnabled = LocalLiquidGlassBackdrop.current != null
    val chatGlassBackdrop = if (globalGlassEnabled) chatGlassSource else null

    // While the chat screen is on top, the client suppresses chat-message
    // notifications (and the shade conversation is cancelled via markChatAsRead).
    DisposableEffect(Unit) {
        manager.setChatScreenVisible(true)
        onDispose {
            manager.setChatScreenVisible(false)
            manager.markMentionsSeen()
        }
    }

    // ---- reversed list geometry -------------------------------------------------
    // The message list is reversed (newest at the visual bottom, index 0), the
    // classic chat arrangement: the newest message is anchored to the list's
    // start edge, so the shrinking viewport while the keyboard opens can never
    // leave it hidden behind the IME — the resize keeps the start-anchored
    // content pinned above the composer.
    val reversedMessages = remember(messages) { messages.asReversed() }
    val liveMessageCount = remember(messages) { messages.count { !it.restored } }
    val hasRestoredMessages = remember(messages) { messages.any { it.restored } }

    // "At the latest message" for the reversed list = reading near index 0.
    val atBottom by remember {
        derivedStateOf {
            val info = lazyListState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            info.totalItemsCount == 0 || first <= 1
        }
    }

    // Auto-follow: while the reader is on the newest messages, every arrival
    // (and every keyboard open) re-pins the list to the bottom.
    LaunchedEffect(messages.size) {
        manager.markChatAsRead()
        if (reversedMessages.isNotEmpty() && atBottom) {
            lazyListState.animateScrollToItem(0)
        }
    }

    // The chat always OPENS on the most recent message; the reversed list
    // makes that the natural start position, so a plain snap is enough even
    // for very long restored histories (no layout race to lose anymore).
    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.layoutInfo.totalItemsCount }
            .filter { it > 0 }
            .first()
        lazyListState.scrollToItem(0)
    }

    // Clear the jump highlight shortly after it lands.
    LaunchedEffect(jumpTargetKey) {
        if (jumpTargetKey == null) return@LaunchedEffect
        delay(1400)
        jumpTargetKey = null
    }

    val pinnedMessages = remember(messages) { messages.filter { it.pinned } }

    // Host role from the live room state (recomposes on host transfer) — drives
    // the "delete for everyone" moderation action on other people's messages.
    val iAmHost = roomState?.hostId != null && roomState?.hostId == userId

    // Own-message detection: session user id first, username as the fallback —
    // restored history from a previous session carries the OLD session's user
    // ids, and those messages must still land on the right side.
    val myUsername = manager.currentUsername
    fun isOwnMessage(message: ChatMessagePayload): Boolean =
        message.userId == userId || (myUsername != null && message.username == myUsername)

    fun sendMessage() {
        if (textInput.isBlank()) return
        when {
            editingMessage != null -> {
                manager.editMessage(editingMessage!!, textInput.trim())
                editingMessage = null
                textInput = ""
                focusManager.clearFocus()
            }
            replyingTo != null -> {
                val replyData = RepliedMessage(replyingTo!!.username, replyingTo!!.message)
                manager.sendChatMessage(textInput.trim(), replyData)
                replyingTo = null
                textInput = ""
                focusManager.clearFocus()
            }
            else -> {
                manager.sendChatMessage(textInput.trim())
                textInput = ""
                focusManager.clearFocus()
            }
        }
    }

    fun shareCurrentTrack() {
        // Room state first (guests are synced from the host); the local player
        // window covers hosts whose room state may lag its own track changes.
        val track = roomState?.currentTrack ?: manager.currentLocalTrack()
        if (track == null || track.id.isBlank() || track.id == "unknown") {
            Toast.makeText(context, R.string.listen_together_chat_nothing_playing, Toast.LENGTH_SHORT).show()
            return
        }
        manager.shareTrackToChat(track)
    }

    fun sharePickedTrack(track: TrackInfo) {
        manager.shareTrackToChat(track)
    }

    fun playSharedTrack(track: TrackInfo) {
        manager.playSharedTrack(track)
        Toast.makeText(context, R.string.listen_together_chat_play_song, Toast.LENGTH_SHORT).show()
    }

    fun jumpToMessage(forwardIndex: Int, key: String) {
        jumpTargetKey = key
        coroutineScope.launch {
            // Instant (not animated) so long histories snap straight to the
            // target; the highlight flash marks the row.
            val reversedIndex = (messages.size - 1 - forwardIndex).coerceIn(0, (messages.size - 1).coerceAtLeast(0))
            lazyListState.scrollToItem(reversedIndex)
        }
    }

    // Root wrapper: the chat (inside the recorded box) and the anchored popups
    // (siblings, outside it) — the structure that keeps the liquid glass
    // non-recursive. The layerBackdrop modifier is attached ONLY while a
    // popup is open, so normal chatting pays zero recording overhead.
    Box(modifier = Modifier.fillMaxSize()) {
        val recordingGlass = chatGlassBackdrop != null && (actionTarget != null || attachmentAnchor != null)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (recordingGlass) {
                            Modifier.layerBackdrop(chatGlassSource)
                        } else {
                            Modifier
                        }
                    )
        ) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            // Resize the ENTIRE screen (message list included) with the
            // keyboard, classic adjustResize behaviour — the reversed list's
            // start-anchored newest message rides above the IME by design.
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.comments),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (mentionCount > 0) {
                                Spacer(modifier = Modifier.width(10.dp))
                                // @-mentions received while reading the chat:
                                // the counter the badge shows, tappable to jump
                                // to the newest mention and clear it.
                                Surface(
                                    onClick = {
                                        val myName = manager.currentUsername
                                        val target = messages.lastOrNull { message ->
                                            !isOwnMessage(message) &&
                                                message.mentions.any { it.equals(myName ?: "", ignoreCase = true) }
                                        }
                                        if (target != null) {
                                            val forwardIndex = messages.indexOfFirst {
                                                it.timestamp == target.timestamp && it.userId == target.userId
                                            }
                                            if (forwardIndex >= 0) {
                                                jumpToMessage(
                                                    forwardIndex,
                                                    "${target.userId}:${target.timestamp}",
                                                )
                                            }
                                        }
                                        manager.markMentionsSeen()
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.alternate_email),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = mentionCount.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                        roomState?.roomCode?.let { code ->
                            Text(
                                text = "Room: $code",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Keep the composer clear of both the gesture nav bar and the
                    // mini player, which draws over NavHost content otherwise.
                    // (The IME itself is handled once, on the Scaffold above.)
                    .windowInsetsPadding(
                        windowInsets.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    )
                    .padding(16.dp)
            ) {
                // Reply / edit preview strip
                AnimatedVisibility(
                    visible = replyingTo != null || editingMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val replyMsg = replyingTo
                    val editMsg = editingMessage
                    Surface(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(8.dp)
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (editMsg != null) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.primary
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = if (editMsg != null) {
                                        stringResource(R.string.listen_together_chat_edit_message)
                                    } else {
                                        replyMsg?.username.orEmpty()
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (editMsg != null) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = editMsg?.message ?: replyMsg?.message.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                if (editingMessage != null) {
                                    editingMessage = null
                                    textInput = ""
                                }
                                replyingTo = null
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Typing indicator with layered avatars
                AnimatedVisibility(
                    visible = typingUsers.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    TypingIndicatorRow(typingUsers = typingUsers)
                }

                if (chatSupported) {
                    // @-mention autocomplete: appears while the composer's text
                    // ends in an @token, listing the room's other members.
                    val mentionCandidates = remember(roomState, userId) {
                        roomState?.users?.filter { it.userId != userId }.orEmpty()
                    }
                    val mentionQuery = remember(textInput) {
                        activeMentionQuery(textInput)
                    }
                    AnimatedVisibility(
                        visible = mentionQuery != null && mentionCandidates.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        MentionSuggestionList(
                            query = mentionQuery.orEmpty(),
                            candidates = mentionCandidates,
                            onPick = { name ->
                                val atIdx = textInput.lastIndexOf('@')
                                if (atIdx >= 0) {
                                    textInput = textInput.substring(0, atIdx) + "@$name "
                                }
                            },
                        )
                    }

                    ChatInputArea(
                        text = textInput,
                        onTextChange = { newText ->
                            textInput = newText
                            if (newText.isNotBlank()) manager.notifyTyping()
                        },
                        onSend = ::sendMessage,
                        onAttachmentClick = { anchor -> attachmentAnchor = anchor },
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.chat_msg),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.listen_together_chat_unsupported_server),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = windowInsets
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (pinnedMessages.isNotEmpty()) {
                PinnedMessagesStack(
                    messages = pinnedMessages,
                    onUnpin = { pinned -> manager.setPinned(pinned, false) },
                    onJumpTo = { pinned ->
                        val index =
                            messages.indexOfFirst {
                                it.timestamp == pinned.timestamp && it.userId == pinned.userId
                            }
                        if (index >= 0) {
                            jumpToMessage(index, "${pinned.userId}:${pinned.timestamp}")
                        }
                    },
                )
            }

            if (messages.isEmpty() && typingUsers.isEmpty()) {
                EmptyChatPlaceholder()
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // Newest at the visual bottom (see the comment above): the
                    // list opens on the latest message and the keyboard resize
                    // can never cover it.
                    reverseLayout = true,
                ) {
                    val dividerSlot =
                        when {
                            hasRestoredMessages && liveMessageCount == 0 -> 0
                            hasRestoredMessages -> liveMessageCount
                            else -> -1
                        }
                    reversedMessages.forEachIndexed { index, message ->
                        if (index == dividerSlot) {
                            item(key = "older_messages_divider") {
                                OlderMessagesDivider()
                            }
                        }
                        item(key = message.timestamp.toString() + message.userId) {
                            MessageItem(
                                message = message,
                                isMe = isOwnMessage(message),
                                myUsername = myUsername,
                                onReply = { replyingTo = it },
                                onLongPress = { pressed, bounds ->
                                    actionTarget = MessageActionTarget(pressed, bounds, isOwnMessage(pressed), iAmHost)
                                },
                                onToggleReaction = { msg, emoji ->
                                    manager.toggleReaction(msg, emoji)
                                },
                                onPlayTrack = ::playSharedTrack,
                                highlighted = jumpTargetKey == "${message.userId}:${message.timestamp}",
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // Anchored Instagram-style action popup (morph + liquid glass over the
    // locally-recorded chat layer — see chatGlassSource above).
    actionTarget?.let { target ->
        MessageActionsPopup(
            target = target,
            backdrop = chatGlassBackdrop,
            myUsername = manager.currentUsername,
            onReact = { emoji -> manager.toggleReaction(target.message, emoji) },
            onOpenEmojiPicker = { showEmojiPicker = true },
            onReply = { replyingTo = target.message },
            onCopy = {
                clipboardManager.setText(AnnotatedString(target.message.message))
                Toast.makeText(
                    context,
                    context.getString(R.string.listen_together_chat_message_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onEdit = {
                editingMessage = target.message
                replyingTo = null
                textInput = target.message.message
            },
            onPinToggle = { manager.setPinned(target.message, !target.message.pinned) },
            onDeleteForMe = { manager.deleteMessageForMe(target.message) },
            onDeleteForEveryone = { manager.deleteMessageForEveryone(target.message) },
            onDismiss = { actionTarget = null },
        )
    }

    // Full emoji picker for reactions with any emoji.
    if (showEmojiPicker) {
        EmojiPickerSheet(
            onPick = { emoji ->
                showEmojiPicker = false
                actionTarget?.let { target ->
                    manager.toggleReaction(target.message, emoji)
                }
                actionTarget = null
            },
            onDismiss = { showEmojiPicker = false },
        )
    }

    // Song picker opened from the composer's "+" attachment menu: search
    // YouTube Music and share any result as a rich tappable card.
    if (showSongPicker) {
        ShareSongPickerSheet(
            currentTrack = roomState?.currentTrack ?: manager.currentLocalTrack(),
            onShareTrack = { track ->
                sharePickedTrack(track)
                showSongPicker = false
            },
            onShareCurrent = {
                shareCurrentTrack()
                showSongPicker = false
            },
            onDismiss = { showSongPicker = false },
        )
    }

    // GIF picker (Giphy) opened from the "+" attachment menu after the menu
    // itself has closed; picking one sends just the link into the chat.
    if (showGifPicker) {
        GifPickerSheet(
            onPickGif = { url ->
                manager.shareGifToChat(url)
                showGifPicker = false
            },
            onDismiss = { showGifPicker = false },
        )
    }

    // "+" attachment menu: liquid-glass morph popup over the composer with
    // Song and GIF entries (see AttachmentMenuPopup).
    attachmentAnchor?.let { anchor ->
        AttachmentMenuPopup(
            anchor = anchor,
            backdrop = chatGlassBackdrop,
            onPickSong = {
                showSongPicker = true
            },
            onPickGif = {
                // Open the bottom sheet after the overflow menu finishes its
                // dismiss morph, as specified.
                coroutineScope.launch {
                    delay(260)
                    showGifPicker = true
                }
            },
            onDismiss = { attachmentAnchor = null },
        )
    }
    }
}

/**
 * The active @-mention query of a composer text: the text must currently end
 * in "@token" (the token may be empty right after typing "@"); typing a space
 * or removing the @ dismisses the suggestions.
 */
private fun activeMentionQuery(text: String): String? {
    val atIdx = text.lastIndexOf('@')
    if (atIdx == -1) return null
    val token = text.substring(atIdx + 1)
    val valid = token.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    return if (valid) token else null
}

/** Autocomplete list of room members for the composer's active @token. */
@Composable
private fun MentionSuggestionList(
    query: String,
    candidates: List<moe.rukamori.archivetune.listentogether.UserInfo>,
    onPick: (String) -> Unit,
) {
    val filtered =
        remember(query, candidates) {
            if (query.isBlank()) {
                candidates
            } else {
                candidates.filter { it.username.contains(query, ignoreCase = true) }
            }
        }
    if (filtered.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(
                count = filtered.size,
                key = { filtered[it].userId },
            ) { index ->
                val member = filtered[index]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(member.username) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ChatAvatar(
                        userId = member.userId,
                        fallbackName = member.username,
                        size = 30.dp,
                    )
                    Text(
                        text = member.username,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (member.isHost) {
                        Icon(
                            painter = painterResource(R.drawable.fire),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachmentClick: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    var attachmentButtonBounds by remember { mutableStateOf(Rect.Zero) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            // "+" opens the attachment menu (Song / GIF) with the liquid-glass
            // morph popup; it replaced the old direct music-note song button.
            IconButton(
                onClick = { onAttachmentClick(attachmentButtonBounds) },
                modifier =
                    Modifier
                        .size(48.dp)
                        .onGloballyPositioned { coordinates ->
                            attachmentButtonBounds = coordinates.boundsInRoot()
                        },
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = stringResource(R.string.listen_together_chat_pick_song_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.type_message)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                )
            )

            FloatingActionButton(
                onClick = onSend,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(48.dp),
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.send_chat),
                    contentDescription = stringResource(R.string.send),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.chat_msg),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "No messages yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Start the conversation!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
