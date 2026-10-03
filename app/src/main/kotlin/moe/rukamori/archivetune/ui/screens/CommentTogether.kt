/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Ported from vivi-music (beta branch) ui/screens/CommentTogether.kt (GPL-3.0),
 * rebuilt with avatars, reactions, pins, edits, deletes, swipe-to-reply,
 * typing indicators, GIF attachments, @mentions, per-username history,
 * a liquid-glass header over a haze fade, "who did what" system rows,
 * per-user wallpapers, a Telegram-style glass composer and mention popups.
 */

package moe.rukamori.archivetune.ui.screens

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalListenTogetherManager
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListenTogetherChatMutedKey
import moe.rukamori.archivetune.constants.ListenTogetherChatWallpaperKey
import moe.rukamori.archivetune.constants.ListenTogetherServerUrlKey
import moe.rukamori.archivetune.listentogether.ChatMessagePayload
import moe.rukamori.archivetune.listentogether.ChatSystemEvent
import moe.rukamori.archivetune.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.listentogether.ListenTogetherProtocol
import moe.rukamori.archivetune.listentogether.RepliedMessage
import moe.rukamori.archivetune.listentogether.TrackInfo
import moe.rukamori.archivetune.ui.component.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LiquidGlassPillBlurRadius
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberThrottledBackdrop
import moe.rukamori.archivetune.utils.rememberPreference

private sealed interface ChatRow {
    val timestamp: Long

    data class Message(val payload: ChatMessagePayload) : ChatRow {
        override val timestamp: Long get() = payload.timestamp
    }

    data class System(val event: ChatSystemEvent) : ChatRow {
        override val timestamp: Long get() = event.timestamp
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentTogetherScreen(navController: NavController) {
    val context = LocalContext.current
    val manager = LocalListenTogetherManager.current ?: return
    val messages by manager.chatMessages.collectAsState()
    val systemEvents by manager.chatSystemEvents.collectAsState()
    val roomName by manager.roomName.collectAsState()
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

    var overflowAnchor by remember { mutableStateOf<Rect?>(null) }

    var chatMuted by rememberPreference(ListenTogetherChatMutedKey, false)

    var chatWallpaper by rememberPreference(ListenTogetherChatWallpaperKey, "")

    val chatWallpaperGlass = rememberChatWallpaperGlassColors(chatWallpaper)

    val serverUrl by rememberPreference(ListenTogetherServerUrlKey, ListenTogetherServers.defaultServerUrl)
    val chatSupported by remember(serverUrl) {
        mutableStateOf(ListenTogetherServers.findByUrl(serverUrl)?.protocol != ListenTogetherProtocol.PROTOBUF)
    }

    val lazyListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val chatGlassSource = rememberThrottledBackdrop(Color.Transparent)
    val globalGlassEnabled = LocalLiquidGlassBackdrop.current != null
    val chatGlassBackdrop = if (globalGlassEnabled) chatGlassSource else null

    val chatHazeState = remember { HazeState() }

    DisposableEffect(Unit) {
        manager.setChatScreenVisible(true)
        onDispose {
            manager.setChatScreenVisible(false)
            manager.markMentionsSeen()
        }
    }

    val reversedRows = remember(messages, systemEvents) {
        (messages.map { ChatRow.Message(it) } + systemEvents.map { ChatRow.System(it) })
            .sortedBy { it.timestamp }
            .asReversed()
    }
    val liveRowCount = remember(messages) { messages.count { !it.restored } }
    val hasRestoredMessages = remember(messages) { messages.any { it.restored } }

    val atBottom by remember {
        derivedStateOf {
            val info = lazyListState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            info.totalItemsCount == 0 || first <= 1
        }
    }

    LaunchedEffect(reversedRows.size) {
        manager.markChatAsRead()
        if (reversedRows.isNotEmpty() && atBottom) {
            lazyListState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.layoutInfo.totalItemsCount }
            .filter { it > 0 }
            .first()
        lazyListState.scrollToItem(0)
    }

    LaunchedEffect(jumpTargetKey) {
        if (jumpTargetKey == null) return@LaunchedEffect
        delay(1400)
        jumpTargetKey = null
    }

    val pinnedMessages = remember(messages) { messages.filter { it.pinned } }

    val iAmHost = roomState?.hostId != null && roomState?.hostId == userId

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
            val reversedIndex = reversedRows.indexOfFirst { row ->
                row is ChatRow.Message &&
                    "${row.payload.userId}:${row.payload.timestamp}" == key
            }
            if (reversedIndex >= 0) {
                lazyListState.scrollToItem(reversedIndex)
            } else {
                val fallback = (messages.size - 1 - forwardIndex)
                    .coerceIn(0, (messages.size - 1).coerceAtLeast(0))
                lazyListState.scrollToItem(fallback)
            }
        }
    }

    val mentionQueue = remember { mutableStateListOf<ChatMessagePayload>() }
    var lastSeenMentionCount by remember { mutableStateOf(0) }
    LaunchedEffect(mentionCount) {
        if (mentionCount > lastSeenMentionCount) {
            val myName = manager.currentUsername

            val fresh = messages.filter { message ->
                !isOwnMessage(message) &&
                    message.mentions.any { it.equals(myName ?: "", ignoreCase = true) } &&
                    mentionQueue.none { it.timestamp == message.timestamp && it.userId == message.userId }
            }
            mentionQueue.addAll(0, fresh.asReversed())
        }
        lastSeenMentionCount = mentionCount
        if (mentionCount == 0) mentionQueue.clear()
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            chatWallpaper = uri.toString()
        }
    }

    val statusBarTop = LocalStableSystemBarsTopPadding.current

    var composerHeightPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val composerBottomPadding = with(density) { (composerHeightPx.toDp()) + 14.dp }

    var headerOverlayHeightPx by remember { mutableStateOf(0) }
    val headerOverlayBottomPadding = with(density) { headerOverlayHeightPx.toDp() + 8.dp }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .hazeSource(chatHazeState)
                    .layerBackdrop(chatGlassSource)
        ) {
            if (chatWallpaper.isNotBlank()) {
                AsyncImage(
                    model = chatWallpaper,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            chatWallpaperGlass.backgroundDim
                                ?: MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f),
                        ),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                )
            }

            LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerOverlayBottomPadding, bottom = composerBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),

                    reverseLayout = true,
                ) {
                        val dividerSlot =
                            when {
                                hasRestoredMessages && liveRowCount == 0 -> 0
                                hasRestoredMessages -> liveRowCount
                                else -> -1
                            }
                        reversedRows.forEachIndexed { index, row ->
                            when (row) {
                                is ChatRow.System -> {
                                    item(key = "sys:${row.event.timestamp}:${row.event.kind}:${row.event.actor}") {
                                        SystemEventRow(event = row.event)
                                    }
                                }

                                is ChatRow.Message -> {
                                    val forwardIndex = messages.indexOfFirst {
                                        it.timestamp == row.payload.timestamp && it.userId == row.payload.userId
                                    }
                                    if (forwardIndex == dividerSlot) {
                                        item(key = "older_messages_divider") {
                                            OlderMessagesDivider()
                                        }
                                    }
                                    item(key = row.payload.timestamp.toString() + row.payload.userId) {
                                        MessageItem(
                                            message = row.payload,
                                            isMe = isOwnMessage(row.payload),
                                            myUsername = myUsername,
                                            onReply = { replyingTo = it },
                                            onLongPress = { pressed, bounds ->
                                                actionTarget =
                                                    MessageActionTarget(pressed, bounds, isOwnMessage(pressed), iAmHost)
                                            },
                                            onToggleReaction = { msg, emoji ->
                                                manager.toggleReaction(msg, emoji)
                                            },
                                            onPlayTrack = ::playSharedTrack,
                                            highlighted =
                                                jumpTargetKey == "${row.payload.userId}:${row.payload.timestamp}",
                                        )
                                    }
                                }
                            }
                        }
                }
        }

        val wallpaperGlassScrim: Color? = chatWallpaperGlass.scrim
        val headerContentColor = chatWallpaperGlass.contentColor ?: liquidGlassContentColor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coordinates ->
                    headerOverlayHeightPx = coordinates.size.height
                },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (globalGlassEnabled) {
                    val chatHazeIntensity by animateFloatAsState(
                        targetValue = if (lazyListState.canScrollBackward) 1f else 0f,
                        animationSpec = tween(durationMillis = 220),
                        label = "chatHazeIntensity",
                    )
                    HomeTopFadeBlur(
                        hazeState = chatHazeState,
                        pageColor = MaterialTheme.colorScheme.surface,
                        barHeight = statusBarTop + 48.dp,
                        intensityFraction = chatHazeIntensity,
                    )
                }

                Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = statusBarTop + 4.dp,
                    ),
            ) {
                val headerTitle = roomName?.takeIf { it.isNotBlank() }
                    ?: roomState?.roomCode?.let { code -> "Room: $code" }
                    ?: stringResource(R.string.comments)

                if (chatGlassBackdrop != null) {
                    LiquidGlassActionPill(
                        backdrop = chatGlassBackdrop,
                        modifier = Modifier,
                        scrim = wallpaperGlassScrim,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                            tint = headerContentColor,
                            modifier = Modifier
                                .clickable { navController.navigateUp() }
                                .padding(12.dp)
                                .size(24.dp),
                        )
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = headerContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(end = 14.dp)
                                .widthIn(max = 200.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    var overflowIconBounds by remember { mutableStateOf(Rect.Zero) }
                    LiquidGlassActionPill(
                        backdrop = chatGlassBackdrop,
                        scrim = wallpaperGlassScrim,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = stringResource(R.string.listen_together_chat_overflow),
                            tint = headerContentColor,
                            modifier = Modifier
                                .onGloballyPositioned { overflowIconBounds = it.boundsInRoot() }
                                .clickable { overflowAnchor = overflowIconBounds }
                                .padding(12.dp)
                                .size(24.dp),
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        modifier = Modifier,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { navController.navigateUp() }) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_back),
                                    contentDescription = null,
                                )
                            }
                            Text(
                                text = headerTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    var overflowIconBounds by remember { mutableStateOf(Rect.Zero) }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    ) {
                        IconButton(
                            onClick = { overflowAnchor = overflowIconBounds },
                            modifier = Modifier.onGloballyPositioned { overflowIconBounds = it.boundsInRoot() },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = stringResource(R.string.listen_together_chat_overflow),
                            )
                        }
                    }
                }
            }
            }

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
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .onGloballyPositioned { coordinates ->
                        composerHeightPx = coordinates.size.height
                    }
                    .windowInsetsPadding(
                        windowInsets.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (chatSupported) {
                if (mentionQueue.isNotEmpty()) {
                    val mention = mentionQueue.first()
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(end = 6.dp, bottom = 6.dp),
                    ) {
                        MentionAlertChip(
                            mention = mention,
                            count = mentionQueue.size,
                            glassBackdrop = chatGlassBackdrop,
                            scrim = wallpaperGlassScrim,
                            contentColor = headerContentColor,
                            onJump = {
                                val forwardIndex = messages.indexOfFirst {
                                    it.timestamp == mention.timestamp && it.userId == mention.userId
                                }
                                if (forwardIndex >= 0) {
                                    jumpToMessage(forwardIndex, "${mention.userId}:${mention.timestamp}")
                                }
                                mentionQueue.removeAt(0)
                                if (mentionQueue.isEmpty()) manager.markMentionsSeen()
                            },
                            onDismiss = {
                                mentionQueue.clear()
                                manager.markMentionsSeen()
                            },
                        )
                    }
                }

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

                TelegramGlassComposer(
                    text = textInput,
                    onTextChange = { newText ->
                        textInput = newText
                        if (newText.isNotBlank()) manager.notifyTyping()
                    },
                    onSend = ::sendMessage,
                    replyingTo = replyingTo,
                    editingMessage = editingMessage,
                    onClearReply = {
                        if (editingMessage != null) {
                            editingMessage = null
                            textInput = ""
                        }
                        replyingTo = null
                    },
                    onAttachmentClick = { anchor -> attachmentAnchor = anchor },
                    glassBackdrop = chatGlassBackdrop,

                    scrim = chatWallpaperGlass.scrim,
                    contentColor = chatWallpaperGlass.contentColor,
                )

                AnimatedVisibility(
                    visible = typingUsers.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    TypingIndicatorRow(typingUsers = typingUsers)
                }
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
    }

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

    if (showGifPicker) {
        GifPickerSheet(
            onPickGif = { url, width, height ->
                manager.shareGifToChat(url, gifWidth = width, gifHeight = height)
                showGifPicker = false
            },
            onDismiss = { showGifPicker = false },
        )
    }

    attachmentAnchor?.let { anchor ->
        AttachmentMenuPopup(
            anchor = anchor,
            backdrop = chatGlassBackdrop,
            scrimAlpha = if (chatWallpaper.isNotBlank()) 0.45f else 0.30f,
            onPickSong = {
                showSongPicker = true
            },
            onPickGif = {
                coroutineScope.launch {
                    delay(260)
                    showGifPicker = true
                }
            },
            onDismiss = { attachmentAnchor = null },
        )
    }

    overflowAnchor?.let { anchor ->
        ChatOverflowMenuPopup(
            anchor = anchor,
            backdrop = chatGlassBackdrop,
            wallpaperSet = chatWallpaper.isNotBlank(),
            muted = chatMuted,
            scrimAlpha = if (chatWallpaper.isNotBlank()) 0.45f else 0.30f,
            onPickWallpaper = {
                wallpaperPicker.launch(arrayOf("image/*"))
            },
            onRemoveWallpaper = {
                chatWallpaper = ""
            },
            onToggleMute = {
                chatMuted = !chatMuted
            },
            onDismiss = { overflowAnchor = null },
        )
    }
}

@Composable
private fun MentionAlertChip(
    mention: ChatMessagePayload,
    count: Int,
    glassBackdrop: moe.rukamori.archivetune.ui.component.PlatformBackdrop?,
    scrim: Color?,
    contentColor: Color,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chipShape = RoundedCornerShape(18.dp)

    val surfaceModifier = if (glassBackdrop != null) {
        Modifier
            .liquidGlass(
                backdrop = glassBackdrop,
                shape = chipShape,
                interactive = false,
                blurRadius = LiquidGlassPillBlurRadius,
                scrim = scrim,
            )
    } else {
        Modifier
            .shadow(6.dp, chipShape)
            .background(
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.96f),
                chipShape,
            )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = surfaceModifier
            .clickable(onClick = onJump)
            .padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
    ) {
        BadgedBox(
            badge = {
                if (count > 1) {
                    Badge {
                        Text(
                            text = count.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
        ) {
            Text(
                text = "@",

                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = if (glassBackdrop != null) {
                    contentColor
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }
        ChatAvatar(
            userId = mention.userId,
            fallbackName = mention.username,
            size = 26.dp,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = null,
                tint = if (glassBackdrop != null) {
                    contentColor.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ChatOverflowMenuPopup(
    anchor: Rect,
    backdrop: moe.rukamori.archivetune.ui.component.PlatformBackdrop?,
    wallpaperSet: Boolean,
    muted: Boolean,
    scrimAlpha: Float = 0.30f,
    onPickWallpaper: () -> Unit,
    onRemoveWallpaper: () -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dismissed by remember { mutableStateOf(false) }

    BackHandler(enabled = !dismissed) {
        dismissed = true
    }
    val scaleAnim = remember { Animatable(0.4f) }
    val alphaAnim = remember { Animatable(0f) }

    var popupWidthPx by remember { mutableStateOf(0) }
    var popupHeightPx by remember { mutableStateOf(0) }
    var overlayWidthPx by remember { mutableStateOf(0) }
    var overlayHeightPx by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(1f, tween(180)) }
        scaleJob.join()
        alphaJob.join()
    }

    LaunchedEffect(dismissed) {
        if (!dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(0.4f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(0f, tween(160)) }
        scaleJob.join()
        alphaJob.join()
        onDismiss()
    }

    fun placement(): IntOffset {
        val marginPx = with(density) { 12.dp.toPx() }.toInt()
        val width = if (popupWidthPx > 0) popupWidthPx else with(density) { 220.dp.toPx() }.toInt()
        val height = if (popupHeightPx > 0) popupHeightPx else with(density) { 132.dp.toPx() }.toInt()
        val screenW = if (overlayWidthPx > 0) overlayWidthPx else width + 2 * marginPx
        val screenH = if (overlayHeightPx > 0) overlayHeightPx else 2000

        val x = (anchor.right.toInt() - width)
            .coerceIn(marginPx, (screenW - width - marginPx).coerceAtLeast(marginPx))
        val y = (anchor.bottom.toInt() + with(density) { 8.dp.toPx() }.toInt())
            .coerceAtMost((screenH - height - marginPx).coerceAtLeast(marginPx))
        return IntOffset(x, y)
    }

    val popupShape = RoundedCornerShape(18.dp)
    val overlayScrimAlpha = 0.18f * alphaAnim.value

    val frostedModifier =
        remember(backdrop, scrimAlpha) {
            if (backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    effects = {
                        colorControls(saturation = 1.7f)
                        blur(20f.dp.toPx())
                        lens(
                            refractionHeight = 16f.dp.toPx(),
                            refractionAmount = 40f.dp.toPx(),
                        )
                    },
                    onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                    onDrawSurface = {
                        drawRect(Color.Black.copy(alpha = scrimAlpha))
                    },
                    shape = { popupShape },
                )
            } else {
                Modifier
                    .background(Color(0xF226262B), popupShape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), popupShape)
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    overlayWidthPx = size.width
                    overlayHeightPx = size.height
                }
                .background(Color.Black.copy(alpha = overlayScrimAlpha))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (!dismissed) dismissed = true },
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .offset { placement() }
                    .onSizeChanged { size ->
                        popupWidthPx = size.width
                        popupHeightPx = size.height
                    }
                    .widthIn(min = 180.dp, max = 250.dp)
                    .graphicsLayer {
                        this.alpha = alphaAnim.value
                        this.scaleX = scaleAnim.value
                        this.scaleY = scaleAnim.value

                        this.transformOrigin = TransformOrigin(1f, 0f)
                        this.shadowElevation = 18.dp.toPx()
                        this.shape = popupShape
                        this.clip = false
                    }
                    .clip(popupShape)
                    .then(frostedModifier)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            OverflowOptionRow(
                icon = R.drawable.image,
                label = stringResource(R.string.listen_together_chat_set_wallpaper),
                description = stringResource(R.string.listen_together_chat_wallpaper_hint),
            ) {
                onPickWallpaper()
                if (!dismissed) dismissed = true
            }

            if (wallpaperSet) {
                OverflowDivider()
                OverflowOptionRow(
                    icon = R.drawable.hide_image,
                    label = stringResource(R.string.listen_together_chat_remove_wallpaper),
                    description = stringResource(R.string.listen_together_chat_wallpaper_hint),
                ) {
                    onRemoveWallpaper()
                    if (!dismissed) dismissed = true
                }
            }

            OverflowDivider()
            OverflowOptionRow(
                icon = if (muted) R.drawable.volume_off else R.drawable.ic_notification,
                label = stringResource(R.string.listen_together_chat_mute_notifications),
                description = stringResource(R.string.listen_together_chat_mute_notifications_desc),
                trailing = {
                    Switch(
                        checked = muted,
                        onCheckedChange = { onToggleMute() },
                        modifier = Modifier.height(24.dp),
                    )
                },
            ) {
                onToggleMute()
            }
        }
    }
}

@Composable
private fun OverflowDivider() {
    Spacer(
        modifier =
            Modifier
                .padding(horizontal = 6.dp, vertical = 5.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.14f)),
    )
}

@Composable
private fun OverflowOptionRow(
    icon: Int,
    label: String,
    description: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramGlassComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    replyingTo: ChatMessagePayload?,
    editingMessage: ChatMessagePayload?,
    onClearReply: () -> Unit,
    onAttachmentClick: (Rect) -> Unit,
    glassBackdrop: moe.rukamori.archivetune.ui.component.PlatformBackdrop?,
    scrim: Color? = null,
    contentColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    var attachmentButtonBounds by remember { mutableStateOf(Rect.Zero) }
    val typing = text.isNotBlank()
    val replying = replyingTo != null || editingMessage != null
    val capsuleShape = RoundedCornerShape(28.dp)

    val accent = contentColor
        ?: if (editingMessage != null) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.primary
        }
    val secondaryContent = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant

    val capsuleModifier =
        if (glassBackdrop != null) {
            Modifier.liquidGlass(
                backdrop = glassBackdrop,
                shape = capsuleShape,
                interactive = false,
                blurRadius = 18.dp,
                scrim = scrim,
            )
        } else {
            Modifier
                .background(

                    when {
                        scrim == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                        scrim.luminance() < 0.5f -> Color.Black.copy(alpha = 0.72f)
                        else -> Color.White.copy(alpha = 0.88f)
                    },
                    capsuleShape,
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    capsuleShape,
                )
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize()
                .clip(capsuleShape)
                .then(capsuleModifier)
                .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        AnimatedVisibility(
            visible = replying,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val replyMsg = replyingTo
            val editMsg = editingMessage
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.reply),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editMsg != null) {
                            stringResource(R.string.listen_together_chat_edit_message)
                        } else {
                            stringResource(R.string.listen_together_chat_reply_to, replyMsg?.username.orEmpty())
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = editMsg?.message ?: replyMsg?.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = contentColor?.copy(alpha = 0.8f)
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onClearReply,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        tint = secondaryContent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        stringResource(R.string.type_message),
                        color = contentColor?.copy(alpha = 0.6f)
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 0.dp),
                colors = if (contentColor != null) {
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = contentColor,
                        unfocusedTextColor = contentColor,
                        cursorColor = contentColor,
                        focusedPlaceholderColor = contentColor.copy(alpha = 0.6f),
                        unfocusedPlaceholderColor = contentColor.copy(alpha = 0.6f),
                    )
                } else {
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    )
                },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                )
            )

            AnimatedVisibility(
                visible = !typing,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onAttachmentClick(attachmentButtonBounds) },
                        modifier =
                            Modifier
                                .size(38.dp)
                                .onGloballyPositioned { coordinates ->
                                    attachmentButtonBounds = coordinates.boundsInRoot()
                                },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_paperclip),
                            contentDescription = stringResource(R.string.listen_together_chat_attachment_song),
                            tint = secondaryContent,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = typing,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Surface(
                    onClick = onSend,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.send_chat),
                            contentDescription = stringResource(R.string.send),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun activeMentionQuery(text: String): String? {
    val atIdx = text.lastIndexOf('@')
    if (atIdx == -1) return null
    val token = text.substring(atIdx + 1)
    val valid = token.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    return if (valid) token else null
}

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
                        size = 34.dp,
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

private class ChatWallpaperGlassPalette(

    val scrim: Color?,

    val contentColor: Color?,

    val backgroundDim: Color?,
)

private const val WallpaperDarkLuminanceThreshold = 0.5f

@Composable
private fun rememberChatWallpaperGlassColors(wallpaper: String): ChatWallpaperGlassPalette {
    val context = LocalContext.current
    var luminance by remember(wallpaper) { mutableStateOf<Float?>(null) }
    LaunchedEffect(wallpaper) {
        luminance =
            if (wallpaper.isBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { measureWallpaperLuminance(context, wallpaper) }.getOrNull()
                }
            }
    }
    return when {
        wallpaper.isBlank() -> ChatWallpaperGlassPalette(null, null, null)

        luminance == null || luminance!! < WallpaperDarkLuminanceThreshold -> ChatWallpaperGlassPalette(
            scrim = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            backgroundDim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f),
        )

        else -> ChatWallpaperGlassPalette(
            scrim = Color.White.copy(alpha = 0.50f),
            contentColor = Color(0xFF1C1B1F),
            backgroundDim = Color.Black.copy(alpha = 0.35f),
        )
    }
}

private suspend fun measureWallpaperLuminance(
    context: android.content.Context,
    source: String,
): Float? {
    val request =
        ImageRequest
            .Builder(context)
            .data(source)
            .allowHardware(false)
            .size(48, 48)
            .build()
    val result = runCatching { context.imageLoader.execute(request) }.getOrNull() ?: return null

    val bitmap = runCatching { result.image?.toBitmap() }.getOrNull() ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    var total = 0.0
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) / 255.0
            val r = ((pixel shr 16) and 0xFF) * alpha
            val g = ((pixel shr 8) and 0xFF) * alpha
            val b = (pixel and 0xFF) * alpha
            total += 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
    }
    return (total / (bitmap.width * bitmap.height) / 255.0).toFloat()
}
