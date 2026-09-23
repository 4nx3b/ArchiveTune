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

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.utils.rememberPreference

/** The list rows: chat messages interleaved with "who did what" system rows. */
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

    // The local chat wallpaper (device-only, never synced, never seen by other
    // members) rendered behind the conversation.
    var chatWallpaper by rememberPreference(ListenTogetherChatWallpaperKey, "")

    // Glass contrast over that wallpaper, MEASURED from the image itself: a
    // dark wallpaper gets a dark glass scrim with WHITE icons and text even
    // while the app theme is light, and a bright one flips the pill bright
    // with dark ink — the pill stays legible in every theme/wallpaper
    // combination instead of following the theme's surface luminance.
    val chatWallpaperGlass = rememberChatWallpaperGlassColors(chatWallpaper)

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

    // Local liquid-glass source for the floating header pill, the composer
    // capsule AND the anchored popups: the chat content records into it while
    // the glass surfaces are composed as SIBLINGS below, so sampling can never
    // recurse. Drawing from the app-wide LocalLiquidGlassBackdrop (the chat
    // lives inside the subtree that backdrop records) caused a
    // circular-rendering SIGSEGV.
    val chatGlassSource = rememberLayerBackdrop()
    val globalGlassEnabled = LocalLiquidGlassBackdrop.current != null
    val chatGlassBackdrop = if (globalGlassEnabled) chatGlassSource else null

    // The haze fade behind the header (the home screen's effect): the chat
    // content is the haze source, the top band blurs it as it scrolls under.
    val chatHazeState = remember { HazeState() }

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
    //
    // Messages and system rows interleave by timestamp so "who did what" reads
    // exactly where it happened in the conversation.
    val reversedRows = remember(messages, systemEvents) {
        (messages.map { ChatRow.Message(it) } + systemEvents.map { ChatRow.System(it) })
            .sortedBy { it.timestamp }
            .asReversed()
    }
    val liveRowCount = remember(messages) { messages.count { !it.restored } }
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
    LaunchedEffect(reversedRows.size) {
        manager.markChatAsRead()
        if (reversedRows.isNotEmpty() && atBottom) {
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
            // target; the highlight flash marks the row. The index resolves
            // against the ACTUAL row list (messages interleaved with system
            // rows), not the messages-only list — otherwise every newer
            // "who did what" row shifts the landing spot.
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

    // ---- in-chat mention popup --------------------------------------------------
    // A mention that lands while the user is INSIDE the chat raises a top popup
    // (not just the header badge): it stays until the user jumps to the mention
    // or explicitly dismisses it — a transient toast is too easy to miss.
    var mentionPopup by remember { mutableStateOf<ChatMessagePayload?>(null) }
    var lastSeenMentionCount by remember { mutableStateOf(0) }
    LaunchedEffect(mentionCount) {
        if (mentionCount > lastSeenMentionCount) {
            val myName = manager.currentUsername
            mentionPopup = messages.lastOrNull { message ->
                !isOwnMessage(message) && message.mentions.any { it.equals(myName ?: "", ignoreCase = true) }
            }
        }
        lastSeenMentionCount = mentionCount
        if (mentionCount == 0) mentionPopup = null
    }

    // ---- wallpaper picker --------------------------------------------------------
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

    // ---- screen geometry ----------------------------------------------------------
    // The notch/status-bar inset comes from the app-level CompositionLocal: the
    // plain WindowInsets.statusBars read returns ZERO here because ancestors of
    // the NavHost already consume the top insets, which used to plant the glass
    // header pill straight into the cutout on notched devices.
    val statusBarTop = LocalStableSystemBarsTopPadding.current
    // The composer column floats over the list's bottom; the list reserves
    // room for it so the newest message is never hidden behind the capsule.
    var composerHeightPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val composerBottomPadding = with(density) { (composerHeightPx.toDp()) + 12.dp }

    // Root wrapper: the chat (inside the recorded box) and the floating glass
    // surfaces (siblings, outside it) — the structure that keeps the liquid
    // glass non-recursive. The whole screen resizes with the IME so the
    // reversed list's start-anchored newest message rides above the keyboard.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .hazeSource(chatHazeState)
                    .layerBackdrop(chatGlassSource)
        ) {
            // Background: the local wallpaper (when set) over a dim scrim, or
            // the plain theme surface. Inside the recorded box so both the top
            // haze fade and the composer glass blur/sample it. The scrim is
            // what keeps the glass surfaces (header pill, composer capsule)
            // readable: they sample and blur whatever is behind them, so a
            // bright wallpaper at 35% dim came through the glass almost
            // unattenuated and washed the icons/text out.
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

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Reserve the floating header zone: the list scrolls UNDER the
                // haze fade and the glass pill (the transparent-header effect).
                Spacer(
                    modifier = Modifier.height(statusBarTop + 48.dp + 14.dp),
                )

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

                // An empty room renders an empty list — no placeholder icon
                // and "no messages" copy; the composer already says everything
                // there is to say.
                LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = composerBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // Newest at the visual bottom (see the comment above): the
                        // list opens on the latest message and the keyboard resize
                        // can never cover it.
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
        }

        // ---- floating liquid-glass header + haze fade (sibling of the recorded
        // content: samples the recorded backdrop, no recursion possible).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            // The same haze fade the home screen's header uses, blurring the
            // chat content (and the wallpaper) as it scrolls under the header —
            // with the glass pill floating ON the band, not stacked under it.
            Box(modifier = Modifier.fillMaxWidth()) {
                if (globalGlassEnabled) {
                    HomeTopFadeBlur(
                        hazeState = chatHazeState,
                        pageColor = MaterialTheme.colorScheme.surface,
                        barHeight = statusBarTop + 48.dp,
                    )
                }

                // The header pill itself: back button + title inside liquid glass,
                // over a transparent background (no opaque top bar anymore).
                // Over a wallpaper the glass gets an explicit surface scrim whose
                // polarity follows the MEASURED wallpaper luminance (dark image →
                // dark scrim + white content, bright image → light scrim + dark
                // ink), so the pill reads in light theme and dark alike.
                val wallpaperGlassScrim: Color? = chatWallpaperGlass.scrim
                val headerContentColor = chatWallpaperGlass.contentColor ?: liquidGlassContentColor()
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
                        modifier = Modifier.weight(1f, fill = false),
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
                                .weight(1f, fill = false)
                                .widthIn(max = 200.dp)
                                .padding(end = 14.dp),
                        )
                        if (mentionCount > 0) {
                            MentionBadge(count = mentionCount, onClick = {
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
                            })
                        }
                    }
                } else {
                    // Glass disabled: the same header, plain surfaces.
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        modifier = Modifier.weight(1f, fill = false),
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
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .widthIn(max = 200.dp),
                            )
                            if (mentionCount > 0) {
                                MentionBadge(count = mentionCount, onClick = {
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
                                })
                            }
                        }
                    }
                }
            }
            } // end header-overlay Box (haze band + glass pill)

            // The in-chat mention popup: persists until jumped-to or dismissed.
            mentionPopup?.let { popup ->
                Spacer(Modifier.height(8.dp))
                MentionAlertPopup(
                    username = popup.username,
                    snippet = popup.sharedTrack?.title
                        ?: if (popup.gifUrl != null) "GIF" else popup.message,
                    onJump = {
                        val forwardIndex = messages.indexOfFirst {
                            it.timestamp == popup.timestamp && it.userId == popup.userId
                        }
                        if (forwardIndex >= 0) {
                            jumpToMessage(forwardIndex, "${popup.userId}:${popup.timestamp}")
                        }
                        manager.markMentionsSeen()
                        mentionPopup = null
                    },
                    onDismiss = { mentionPopup = null },
                )
            }
        }

        // ---- floating composer (sibling of the recorded content) ---------------
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        windowInsets.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .onGloballyPositioned { coordinates ->
                        composerHeightPx = coordinates.size.height
                    },
        ) {
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
                    // Wallpaper mode: the capsule's scrim polarity AND its content
                    // color both follow the measured wallpaper luminance (see
                    // rememberChatWallpaperGlassColors).
                    scrim = chatWallpaperGlass.scrim,
                    contentColor = chatWallpaperGlass.contentColor,
                )

                // Typing indicator with layered avatars
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

    // Song picker opened from the composer's "/" quick action: search
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

    // GIF picker (Giphy + custom device GIFs) opened from the composer's GIF
    // button or the attachment menu; picking one sends the link (and the
    // GIF's intrinsic size, so receivers keep the original aspect ratio).
    if (showGifPicker) {
        GifPickerSheet(
            onPickGif = { url, width, height ->
                manager.shareGifToChat(url, gifWidth = width, gifHeight = height)
                showGifPicker = false
            },
            onDismiss = { showGifPicker = false },
        )
    }

    // Attachment menu: liquid-glass morph popup over the composer with
    // Song, GIF and wallpaper entries (see AttachmentMenuPopup). The wallpaper
    // controls moved here from the composer's kebab so the input box keeps
    // only the paperclip and the send button.
    attachmentAnchor?.let { anchor ->
        AttachmentMenuPopup(
            anchor = anchor,
            backdrop = chatGlassBackdrop,
            wallpaperSet = chatWallpaper.isNotBlank(),
            scrimAlpha = if (chatWallpaper.isNotBlank()) 0.45f else 0.30f,
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
            onPickWallpaper = {
                wallpaperPicker.launch(arrayOf("image/*"))
            },
            onRemoveWallpaper = {
                chatWallpaper = ""
            },
            onDismiss = { attachmentAnchor = null },
        )
    }
}

/** Small @-mention count chip inside the chat header pill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MentionBadge(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
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
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * The in-chat mention alert: a compact top pill that appears the moment
 * someone @-mentions the user while they are reading the chat. It never
 * auto-hides — only jumping to the mention or the dismiss button clears it,
 * so a mention can't slip by unnoticed.
 */
@Composable
private fun MentionAlertPopup(
    username: String,
    snippet: String,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clickable(onClick = onJump)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.alternate_email),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$username ${stringResource(R.string.listen_together_chat_mentioned_you)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snippet.isNotBlank()) {
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/**
 * The Telegram-style liquid-glass composer, recreated one-to-one from the
 * reference: a stadium capsule with the text field through the middle and a
 * paperclip (Song / GIF / wallpaper all live behind it in the attachment
 * popup). While typing, the paperclip yields
 * to the send button, exactly like the reference. The reply state grows the
 * capsule upward with the accent reply preview INSIDE it (arrow, "Reply to
 * name", snippet, close) instead of a separate strip above.
 *
 * The capsule is liquid glass over a transparent background — never an
 * opaque bar — so the conversation (and the wallpaper) shows through it.
 */
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
    // Over a wallpaper the reply/edit accent follows the measured content
    // color — the labels themselves ("Reply to…" vs "Edit message") carry
    // the distinction, so legibility wins over the theme's accent hues.
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
                    // No-glass fallback: without a wallpaper the usual
                    // translucent surfaceVariant; over a wallpaper the capsule
                    // keeps the scrim's polarity so its content color still
                    // contrasts (a dark image must not put white text on a
                    // light fallback surface).
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
        // Reply / edit preview, inside the capsule (the reference's reply
        // state): accent arrow + "Reply to <name>" + snippet + close.
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

        // The input row.
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
                    // Wallpaper mode: typed text, cursor and placeholder all
                    // follow the measured contrast color instead of the theme's
                    // (light-theme dark ink over a dark scrim was unreadable).
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

            // Trailing cluster: just the paperclip — Song, GIF and the
            // wallpaper entries all live behind it in the attachment popup.
            // Hidden while typing, when the send button takes its place,
            // exactly like the reference.
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

            // Send button: appears only with text in the field.
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

/**
 * The glass-surface contrast choices a chat wallpaper implies.
 *
 * A null field means "no wallpaper" — the caller keeps its theme-derived
 * behavior. With a wallpaper set, every field is non-null and derived from
 * the image's MEASURED luminance: dark images drive a dark glass scrim with
 * white content (regardless of app theme — the reported bug was dark
 * wallpaper + light theme rendering dark ink on a dark pill), bright images
 * drive a light scrim with dark ink.
 */
private class ChatWallpaperGlassPalette(
    /** The glass surfaces' scrim (polarity follows the wallpaper). */
    val scrim: Color?,
    /** The icons/text color that contrasts with [scrim]'d glass. */
    val contentColor: Color?,
    /** The full-screen dim behind the conversation. */
    val backgroundDim: Color?,
)

/** Luminance threshold below which a wallpaper drives the dark-glass palette. */
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
        // While the measurement is in flight (or failed — a broken image also
        // renders nothing): assume dark. White content over the dark scrim is
        // the safe default in both themes.
        luminance == null || luminance!! < WallpaperDarkLuminanceThreshold -> ChatWallpaperGlassPalette(
            scrim = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            backgroundDim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f),
        )
        // Bright wallpaper: light glass scrim + dark ink, and a lighter
        // full-screen dim so the image keeps its character.
        else -> ChatWallpaperGlassPalette(
            scrim = Color.White.copy(alpha = 0.50f),
            contentColor = Color(0xFF1C1B1F),
            backgroundDim = Color.Black.copy(alpha = 0.35f),
        )
    }
}

/**
 * Average perceived luminance (0..1) of the wallpaper, measured over a tiny
 * 48px decode so the cost is one small bitmap, once per wallpaper change.
 */
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
    // ImageResult.image is nullable on the ErrorResult branch of Coil's API.
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
