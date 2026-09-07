/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * One-on-one chat screen with a saved Telegram bot. The user pastes a song link, the app sends it
 * to the bot via [TdApi.SendMessage], then waits on [TelegramBotClient.messagesForChat] for audio
 * replies. Each reply is persisted as a Song + Format row so it can be played / downloaded /
 * added-to-playlist through the existing infrastructure.
 *
 * When the user adds a bot-fetched song to a Telegram-channel playlist (LPtg<chatId>) AND the
 * "Auto-forward to my channel" toggle is on, the original bot message is forwarded to that channel
 * via [TdApi.ForwardMessages] — matching the user's spec: "if I add it to my telegram playlist the
 * song should also get forwarded to my own channel automatically".
 *
 * "Streaming a lot of files": the collector returns every audio reply that arrives within the
 * timeout window, and the "Play all" button loads them all into the player queue at once.
 *
 * Quality picker: many music bots reply with an inline keyboard ("Choose quality: ALAC / AAC /
 * Cancel") instead of the audio file directly. The screen surfaces those buttons as a row of
 * chips. When the user taps one, the screen calls [TelegramBotClient.clickInlineButton] (which
 * fires a [TdApi.GetCallbackQueryAnswer]) and then re-enters the collector with
 * `afterMessageId = prompt.messageId` so the bot's resulting audio reply is captured.
 */

package moe.rukamori.archivetune.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.TelegramBotForwardToChannelKey
import moe.rukamori.archivetune.constants.TelegramBotsKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.telegram.BotReply
import moe.rukamori.archivetune.telegram.TelegramBot
import moe.rukamori.archivetune.telegram.TelegramBotClient
import moe.rukamori.archivetune.telegram.TelegramBotCodec
import moe.rukamori.archivetune.telegram.TelegramBotCommand
import moe.rukamori.archivetune.telegram.TelegramBotPrompt
import moe.rukamori.archivetune.telegram.TelegramBotPromptButton
import moe.rukamori.archivetune.telegram.TelegramTrack
import moe.rukamori.archivetune.telegram.telegramArtworkModel
import moe.rukamori.archivetune.telegram.toFormatEntity
import moe.rukamori.archivetune.telegram.toMediaMetadata
import moe.rukamori.archivetune.ui.component.FrostedTopAppBar
import moe.rukamori.archivetune.ui.menu.AddToPlaylistDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.LocalDatabase
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TelegramBotChatScreen(
    botId: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()
    val (rawBots, onBotsChange) = rememberPreference(TelegramBotsKey, "")
    val (forwardToChannel) = rememberPreference(TelegramBotForwardToChannelKey, true)

    val bot: TelegramBot? = remember(rawBots) {
        TelegramBotCodec.decode(rawBots).find { it.id == botId }
    }

    var songLink by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var noReply by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<TelegramTrack>() }

    var pendingPrompt by remember { mutableStateOf<TelegramBotPrompt?>(null) }

    var highestSeenMessageId by remember { mutableLongStateOf(0L) }

    var pendingChoiceText by remember { mutableStateOf<String?>(null) }

    var addToPlaylistTrack by remember { mutableStateOf<TelegramTrack?>(null) }

    var botCommands by remember { mutableStateOf<List<TelegramBotCommand>>(emptyList()) }
    var showCommandMenu by remember { mutableStateOf(false) }
    var commandsFetchedForChatId by remember { mutableLongStateOf(0L) }

    LaunchedEffect(bot?.chatId) {
        val chatId = bot?.chatId ?: 0L
        if (chatId != 0L && chatId != commandsFetchedForChatId) {
            commandsFetchedForChatId = chatId
            botCommands = TelegramBotClient.fetchBotCommands(chatId)
        }
    }

    if (bot == null) {

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { navController.navigateUp() }) {
                Text("Bot not found — tap to go back")
            }
        }
        return
    }

    fun persistBot(updated: TelegramBot) {
        val list = TelegramBotCodec.decode(rawBots).map { if (it.id == updated.id) updated else it }
        onBotsChange(TelegramBotCodec.encode(list))
    }

    suspend fun ensureBotChatId(): TelegramBot {
        if (bot.chatId != 0L) return bot
        val resolved = TelegramBotClient.resolveBot(bot.username) ?: return bot
        val title = runCatching {
            val type = resolved.type as org.drinkless.tdlib.TdApi.ChatTypePrivate
            moe.rukamori.archivetune.telegram.TelegramClient.send(
                org.drinkless.tdlib.TdApi.GetUser(type.userId),
            ).firstName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: bot.title
        val updated = bot.copy(chatId = resolved.id, title = title.ifBlank { bot.title })
        persistBot(updated)
        return updated
    }

    suspend fun persistTracks(tracks: List<TelegramTrack>, sourceTitle: String) {
        database.withTransaction {
            tracks.forEach { track ->
                insert(track.toMediaMetadata(sourceTitle))
                upsert(track.toFormatEntity())
            }
        }
        results.clear()
        results.addAll(tracks)
    }

    suspend fun collectAndApply(
        chatId: Long,
        afterMessageId: Long,
        sourceTitle: String,
    ) {
        val replies = TelegramBotClient.collectBotReplies(
            chatId = chatId,
            afterMessageId = afterMessageId,
        )

        replies.maxOfOrNull { reply ->
            when (reply) {
                is BotReply.Track -> reply.track.messageId
                is BotReply.Prompt -> reply.prompt.messageId
            }
        }?.let { if (it > highestSeenMessageId) highestSeenMessageId = it }

        val newTracks = replies.filterIsInstance<BotReply.Track>().map { it.track }
        val latestPrompt = replies.filterIsInstance<BotReply.Prompt>().lastOrNull()?.prompt

        if (newTracks.isNotEmpty()) {

            noReply = false
            pendingPrompt = null
            persistTracks(newTracks, sourceTitle)
        } else if (latestPrompt != null) {

            pendingPrompt = latestPrompt
        } else {

            if (pendingPrompt == null) noReply = true
        }
    }

    fun send() {
        val link = songLink.trim()
        if (link.isBlank() || sending) return
        sending = true
        noReply = false
        results.clear()
        pendingPrompt = null
        pendingChoiceText = null
        coroutineScope.launch {
            val activeBot = ensureBotChatId()
            if (activeBot.chatId == 0L) {
                sending = false
                Toast.makeText(context, R.string.telegram_bots_resolve_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sent = runCatching {
                TelegramBotClient.sendTextMessage(activeBot.chatId, link)
            }.getOrElse {
                sending = false
                Toast.makeText(
                    context,
                    context.getString(R.string.telegram_error, it.message ?: "?"),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            highestSeenMessageId = sent.id
            collectAndApply(
                chatId = activeBot.chatId,
                afterMessageId = sent.id,
                sourceTitle = activeBot.title,
            )
            sending = false
        }
    }

    fun choosePromptOption(button: TelegramBotPromptButton) {
        val prompt = pendingPrompt ?: return

        if (button.callbackData == null) {
            button.url?.let { url ->
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            return
        }
        if (sending) return
        sending = true
        pendingChoiceText = button.text
        coroutineScope.launch {

            if (prompt.isCancelButton(button)) {
                pendingPrompt = null
                pendingChoiceText = null
                sending = false
                return@launch
            }
            TelegramBotClient.clickInlineButton(
                chatId = prompt.chatId,
                messageId = prompt.messageId,
                callbackData = button.callbackData,
            )

            collectAndApply(
                chatId = prompt.chatId,
                afterMessageId = prompt.messageId,
                sourceTitle = bot.title,
            )
            pendingChoiceText = null
            sending = false
        }
    }

    fun playTrack(track: TelegramTrack) {
        if (playerConnection == null) return
        val mediaItem = track.toMediaMetadata(bot.title).toMediaItem()
        playerConnection.playQueue(ListQueue(title = bot.title, items = listOf(mediaItem)))
    }

    fun playAll() {
        if (playerConnection == null || results.isEmpty()) return
        val items = results.map { it.toMediaMetadata(bot.title).toMediaItem() }
        playerConnection.playQueue(ListQueue(title = bot.title, items = items))
    }

    fun downloadTrack(track: TelegramTrack) {

        runCatching { downloadUtil.downloadCache.removeResource(track.mediaId) }
        val request = DownloadRequest.Builder(track.mediaId, track.mediaId.toUri())
            .setCustomCacheKey(track.mediaId)
            .setData(track.displayTitle.toByteArray())
            .build()
        runCatching {
            DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
        }.onFailure {
            Toast.makeText(context, R.string.telegram_error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    fun addToPlaylist(track: TelegramTrack) {
        addToPlaylistTrack = track
    }

    suspend fun maybeForwardToTelegramChannels(track: TelegramTrack) {
        if (!forwardToChannel) return

        val playlists = database.playlistsByCreateDateAsc().first()
        val tgPlaylists = playlists.filter { it.id.startsWith("LPtg") }
        for (playlist in tgPlaylists) {
            val inPlaylist = database.withTransaction {
                playlistDuplicates(playlist.id, listOf(track.mediaId)).isNotEmpty()
            }
            if (!inPlaylist) continue
            val chatId = playlist.id.removePrefix("LPtg").toLongOrNull() ?: continue
            runCatching {
                TelegramBotClient.forwardMessage(
                    toChatId = chatId,
                    fromChatId = track.chatId,
                    messageId = track.messageId,
                )
            }.onSuccess {
                Toast.makeText(context, R.string.telegram_bot_forwarded, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(
                    context,
                    context.getString(R.string.telegram_bot_forward_failed, e.message ?: "?"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistDialog(
            isVisible = true,
            onGetSong = { listOf(track.mediaId) },
            onDismiss = { addToPlaylistTrack = null },
            onAddComplete = { _, _ ->

                if (forwardToChannel) {
                    coroutineScope.launch { maybeForwardToTelegramChannels(track) }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            FrostedTopAppBar(
                title = { Text(stringResource(R.string.telegram_bot_chat_title, bot.username)) },
                onBack = navController::navigateUp,
                onBackLongClick = navController::backToMain,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = songLink,
                    onValueChange = { songLink = it },
                    placeholder = { Text(stringResource(R.string.telegram_bot_chat_hint)) },
                    singleLine = true,
                    enabled = !sending,
                    leadingIcon = {
                        Box {
                            IconButton(
                                onClick = { showCommandMenu = true },
                                enabled = !sending,
                            ) {
                                Text(
                                    text = "/",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            DropdownMenu(
                                expanded = showCommandMenu,
                                onDismissRequest = { showCommandMenu = false },
                            ) {

                                if (botCommands.isNotEmpty()) {
                                    botCommands.forEach { cmd ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = cmd.withSlash,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                    if (cmd.description.isNotBlank()) {
                                                        Text(
                                                            text = cmd.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                songLink = "${cmd.withSlash} "
                                                showCommandMenu = false
                                            },
                                        )
                                    }

                                    androidx.compose.material3.HorizontalDivider()
                                }

                                CommonBotCommands.forEach { cmd ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = cmd.withSlash,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                if (cmd.description.isNotBlank()) {
                                                    Text(
                                                        text = cmd.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            songLink = "${cmd.withSlash} "
                                            showCommandMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = ::send, enabled = !sending && songLink.isNotBlank()) {
                            if (sending && pendingChoiceText == null) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(painterResource(R.drawable.solar_send_square_linear), contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (sending && pendingPrompt == null && results.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.telegram_bot_waiting))
                    }
                }
            }

            pendingPrompt?.let { prompt ->
                PromptCard(
                    prompt = prompt,
                    pendingChoiceText = pendingChoiceText,
                    onChoose = ::choosePromptOption,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (noReply) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.telegram_bot_no_reply),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (results.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.telegram_bot_results, results.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (results.size > 1) {
                        TextButton(onClick = ::playAll) {
                            Icon(painterResource(R.drawable.solar_play_linear), contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.telegram_bot_play_all))
                        }
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(results, key = TelegramTrack::mediaId) { track ->
                        BotResultRow(
                            track = track,
                            onStream = { playTrack(track) },
                            onDownload = { downloadTrack(track) },
                            onAddToPlaylist = { addToPlaylist(track) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptCard(
    prompt: TelegramBotPrompt,
    pendingChoiceText: String?,
    onChoose: (TelegramBotPromptButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
    ) {
        if (prompt.text.isNotBlank()) {
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        prompt.rows.forEach { row ->
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { button ->
                    val isPending = pendingChoiceText == button.text
                    FilterChip(
                        selected = false,
                        onClick = { onChoose(button) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(button.text)
                            }
                        },
                        enabled = pendingChoiceText == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun BotResultRow(
    track: TelegramTrack,
    onStream: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {

    val thumbModel = remember(track) {
        val metadata = track.lookupMetadata
        telegramArtworkModel(track.thumbnailFileId, metadata.title, metadata.artist)
            ?: moe.rukamori.archivetune.telegram.TelegramClient.cacheArtwork(
                uniqueKey = track.fileUniqueId.ifEmpty { "${track.chatId}-${track.messageId}" },
                data = track.albumCoverMinithumbnail,
            )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {

            Icon(
                painter = painterResource(R.drawable.solar_music_note_2_linear),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val performer = track.performer ?: track.lookupMetadata.artist
            if (!performer.isNullOrBlank()) {
                Text(
                    text = performer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onStream) {
            Icon(
                painter = painterResource(R.drawable.solar_play_linear),
                contentDescription = stringResource(R.string.telegram_bot_stream),
            )
        }
        IconButton(onClick = onDownload) {
            Icon(
                painter = painterResource(R.drawable.solar_download_linear),
                contentDescription = stringResource(R.string.telegram_bot_download),
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                painter = painterResource(R.drawable.solar_playlist_linear),
                contentDescription = stringResource(R.string.telegram_bot_add_to_playlist),
            )
        }
    }
}

private val CommonBotCommands = listOf(
    TelegramBotCommand("start", "Initialize / restart the bot"),
    TelegramBotCommand("help", "Show the bot's help / usage guide"),
    TelegramBotCommand("search", "Search for a song by name or artist"),
    TelegramBotCommand("download", "Download a song from a link or search query"),
    TelegramBotCommand("song", "Search for a song by name"),
    TelegramBotCommand("music", "Search for music by query"),
    TelegramBotCommand("lyrics", "Fetch lyrics for a song"),
    TelegramBotCommand("flac", "Request FLAC (lossless) quality"),
    TelegramBotCommand("alac", "Request ALAC (Apple Lossless) quality"),
    TelegramBotCommand("mp3", "Request MP3 (lossy) quality"),
    TelegramBotCommand("cancel", "Cancel the current operation"),
)
