/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Listen Together player bridge — ported from vivi-music (beta branch),
 * vivi-music's listentogether.ListenTogetherManager (GPL-3.0).
 *
 * Adaptations for ArchiveTune (all marked with PORT-NOTE below):
 *  - PlayerConnection exposes `player: Player` (the service's active player)
 *    instead of vivi's `player: ExoPlayer`; every member used here exists on
 *    the media3 Player interface, so no casts were needed.
 *  - vivi's PlayerConnection carried Listen-Together hooks
 *    (shouldBlockPlaybackChanges / allowInternalSync / onSkipPrevious /
 *    onSkipNext / onRestartSong / setMuted/isMuted). ArchiveTune's
 *    PlayerConnection has none of them yet, so the manager does not touch
 *    them; guest playback gating is left to the integration phase.
 */

package moe.rukamori.archivetune.listentogether

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.constants.ListenTogetherAvatarIndexKey
import moe.rukamori.archivetune.constants.ListenTogetherChatHistoryKey
import moe.rukamori.archivetune.constants.ListenTogetherSmartResyncKey
import moe.rukamori.archivetune.constants.ListenTogetherSyncVolumeKey
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.MediaMetadata.Album
import moe.rukamori.archivetune.models.MediaMetadata.Artist
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListenTogetherManager @Inject constructor(
    private val client: ListenTogetherClient,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ListenTogetherManager"

        private const val SYNC_DEBOUNCE_THRESHOLD_MS = 1000L

        private const val POSITION_TOLERANCE_MS = 2000L

        private const val PLAYBACK_POSITION_TOLERANCE_MS = 3000L

        /** Typing indicators stay alive for this long after the last typing event. */
        private const val TYPING_TTL_MS = 4500L

        /** How often a typing client re-announces itself while composing. */
        private const val TYPING_THROTTLE_MS = 2500L

        /** Chat history persisted per local username (survives room switches). */
        private const val MAX_PERSISTED_CHAT_MESSAGES = 150
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        initialize()
        observePreferences()
    }

    private var playerConnection: PlayerConnection? = null
    private var eventCollectorJob: Job? = null
    private var queueObserverJob: Job? = null
    private var volumeObserverJob: Job? = null
    private var playerListenerRegistered = false

    private val syncHostVolumeEnabled = MutableStateFlow(false)
    private val smartResyncEnabled = MutableStateFlow(true)
    private var lastSyncedVolume: Float? = null

    private var lastRole: RoomRole = RoomRole.NONE

    @Volatile
    private var isSyncing = false

    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSyncedTrackId: String? = null

    private var lastSyncActionTime: Long = 0L

    private var bufferingTrackId: String? = null

    private var activeSyncJob: Job? = null

    private var currentTrackGeneration: Int = 0

    private var pendingSyncState: SyncStatePayload? = null

    private var bufferCompleteReceivedForTrack: String? = null

    private var idleDisconnectJob: Job? = null

    val connectionState = client.connectionState
    val roomState = client.roomState
    val role = client.role
    val userId = client.userId
    val pendingJoinRequests = client.pendingJoinRequests
    val bufferingUsers = client.bufferingUsers
    val logs = client.logs
    val events = client.events
    val blockedUsernames = client.blockedUsernames
    val pendingSuggestions = client.pendingSuggestions

    val isInRoom: Boolean get() = client.isInRoom
    val isHost: Boolean get() = client.isHost
    val hasPersistedSession: Boolean get() = client.hasPersistedSession

    private val _chatMessages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())
    val chatMessages = _chatMessages

    /** Room members currently typing, freshest last; expires via [TYPING_TTL_MS]. */
    private val _typingUsers = MutableStateFlow<List<TypingUser>>(emptyList())
    val typingUsers: kotlinx.coroutines.flow.StateFlow<List<TypingUser>> = _typingUsers

    private var chatPersistJob: Job? = null
    private val chatHistoryJson = Json { ignoreUnknownKeys = true }
    private var lastTypingSentAt = 0L

    private val _unreadMessageCount = MutableStateFlow(0)
    val unreadMessageCount: kotlinx.coroutines.flow.StateFlow<Int> = _unreadMessageCount

    fun markChatAsRead() {
        _unreadMessageCount.value = 0
        client.cancelChatNotification()
    }

    /** Tells the client whether the chat screen is on top (suppresses its chat notifications). */
    fun setChatScreenVisible(visible: Boolean) {
        client.setChatScreenVisible(visible)
    }

    private fun Player.playForSync() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            try {
                if (isSyncing || !isHost || !isInRoom) return

                val connection = playerConnection ?: return
                val player = connection.player

                Timber.tag(TAG).d("Play state changed: $playWhenReady (reason: $reason)")

                val currentTrackId = player.currentMediaItem?.mediaId
                if (currentTrackId != null && currentTrackId != lastSyncedTrackId) {
                    Timber.tag(TAG)
                        .d("[SYNC] Sending track change before play state: track = $currentTrackId")
                    player.currentMetadata?.let { metadata ->
                        sendTrackChangeInternal(metadata)
                        lastSyncedTrackId = currentTrackId

                        lastSyncedIsPlaying = false
                    }

                    if (playWhenReady) {
                        Timber.tag(TAG).d("[SYNC] Host is playing, sending PLAY after track change")
                        lastSyncedIsPlaying = true
                        val position = player.currentPosition
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    }
                    return
                }

                sendPlayState(playWhenReady, player)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPlayWhenReadyChanged")
            }
        }

        private fun sendPlayState(playWhenReady: Boolean, player: Player) {
            try {
                val position = player.currentPosition

                if (playWhenReady) {
                    Timber.tag(TAG).d("Host sending PLAY at position $position")
                    client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    lastSyncedIsPlaying = true
                } else if (!playWhenReady && (lastSyncedIsPlaying == true)) {
                    Timber.tag(TAG).d("Host sending PAUSE at position $position")
                    client.sendPlaybackAction(PlaybackActions.PAUSE, position = position)
                    lastSyncedIsPlaying = false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in sendPlayState")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            try {
                if (isSyncing || !isInRoom) return
                if (mediaItem == null) return

                val connection = playerConnection ?: return
                val player = connection.player

                val trackId = mediaItem.mediaId

                if (!isHost) {
                    // Guests cannot broadcast playback actions — their local track
                    // change travels to the room as a suggestion instead, so the host
                    // (auto-)approves it and everyone, including the guest, syncs to it.
                    suggestLocalTrackChange(trackId, player)
                    return
                }

                if (trackId == lastSyncedTrackId) return

                lastSyncedTrackId = trackId

                lastSyncedIsPlaying = false

                player.currentMetadata?.let { metadata ->
                    Timber.tag(TAG).d("Host sending track change: ${metadata.title}")
                    sendTrackChange(metadata)

                    val isPlaying = player.playWhenReady
                    if (isPlaying) {
                        Timber.tag(TAG).d("Host is playing during track change, sending PLAY")
                        lastSyncedIsPlaying = true
                        val position = player.currentPosition
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onMediaItemTransition")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            try {
                if (isSyncing || !isHost || !isInRoom) return

                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    Timber.tag(TAG).d("Host sending SEEK to ${newPosition.positionMs}")
                    client.sendPlaybackAction(PlaybackActions.SEEK, position = newPosition.positionMs)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPositionDiscontinuity")
            }
        }
    }

    fun setPlayerConnection(connection: PlayerConnection?) {
        Timber.tag(TAG).d("setPlayerConnection: ${connection != null}, isInRoom: $isInRoom")

        try {

            val oldConnection = playerConnection
            if (playerListenerRegistered && oldConnection != null) {
                try {
                    oldConnection.player.removeListener(playerListener)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error removing old player listener")
                }
                playerListenerRegistered = false
            }

            playerConnection = connection

            if (connection != null && isInRoom) {
                try {
                    connection.player.addListener(playerListener)
                    playerListenerRegistered = true
                    Timber.tag(TAG).d("Added player listener for room sync")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to add player listener")
                    playerListenerRegistered = false
                }
            }

            if (connection != null && isInRoom && isHost) {
                startQueueSyncObservation()
                startHeartbeat()
                startVolumeSyncObservation()
            } else {
                stopQueueSyncObservation()
                stopHeartbeat()
                stopVolumeSyncObservation()
            }
            updateGuestMuteState()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in setPlayerConnection")
        }
    }

    private fun observePreferences() {
        scope.launch {
            context.dataStore.data
                .map { it[ListenTogetherSyncVolumeKey] ?: false }
                .distinctUntilChanged()
                .collect { enabled ->
                    syncHostVolumeEnabled.value = enabled
                }

            context.dataStore.data
                .map { it[ListenTogetherSmartResyncKey] ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    smartResyncEnabled.value = enabled
                }
        }
    }

    fun initialize() {
        Timber.tag(TAG).d("Initializing ListenTogetherManager")
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            client.events.collect { event ->
                try {
                    Timber.tag(TAG).d("Received event: $event")
                    handleEvent(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling event: $event")
                }
            }
        }

        // Expire stale typing indicators.
        scope.launch {
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val fresh = _typingUsers.value.filter { it.expiresAt > now }
                if (fresh.size != _typingUsers.value.size) {
                    _typingUsers.value = fresh
                }
            }
        }

        scope.launch {
            combine(connectionState, client.roomState) { cState, rState ->
                cState to rState
            }.collect { (cState, rState) ->
                idleDisconnectJob?.cancel()
                if (cState == ConnectionState.CONNECTED && rState == null) {
                    idleDisconnectJob = scope.launch {
                        delay(15 * 60 * 1000L)
                        Timber.tag(TAG).w("Idle disconnect timeout reached (15m without joining a room). Disconnecting...")
                        disconnect()
                    }
                }
            }
        }

        scope.launch {
            role.collect { newRole ->
                try {
                    val previousRole = lastRole
                    lastRole = newRole

                    val wasHost = previousRole == RoomRole.HOST
                    if (newRole == RoomRole.HOST && !wasHost) {
                        val connection = playerConnection
                        if (connection != null) {
                            Timber.tag(TAG).d("Role changed to HOST, starting sync services")
                            startQueueSyncObservation()
                            startHeartbeat()
                            startVolumeSyncObservation()

                            if (!playerListenerRegistered) {
                                try {
                                    connection.player.addListener(playerListener)
                                    playerListenerRegistered = true
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Failed to add player listener on role change")
                                }
                            }
                        }
                    } else if (newRole != RoomRole.HOST && wasHost) {
                        Timber.tag(TAG).d("Role changed from HOST, stopping sync services")
                        stopQueueSyncObservation()
                        stopHeartbeat()
                        stopVolumeSyncObservation()
                    }
                    updateGuestMuteState()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in role change handler")
                }
            }
        }
    }

    private fun handleEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.Connected -> {
                Timber.tag(TAG).d("Connected to server with userId: ${event.userId}")
            }

            is ListenTogetherEvent.RoomCreated -> {
                Timber.tag(TAG).d("Room created: ${event.roomCode}")
                try {
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Added player listener as host")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to add player listener on room create")
                        }
                    }

                    lastSyncedIsPlaying = player?.playWhenReady
                    lastSyncedTrackId = player?.currentMediaItem?.mediaId

                    player?.currentMetadata?.let { metadata ->
                        Timber.tag(TAG).d("Room created with existing track: ${metadata.title}")

                        sendTrackChangeInternal(metadata)

                        val isPlaying = player.playWhenReady
                        if (isPlaying) {
                            lastSyncedIsPlaying = true
                            val position = player.currentPosition
                            Timber.tag(TAG).d("Host already playing on room create, sending PLAY at $position")
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                        }
                    }
                    startQueueSyncObservation()
                    startHeartbeat()
                    startVolumeSyncObservation()
                    broadcastCustomAvatar()
                    loadPersistedChatHistory()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling RoomCreated event")
                }
            }

            is ListenTogetherEvent.JoinApproved -> {
                Timber.tag(TAG).d("Join approved for room: ${event.roomCode}")

                saveMuteStateOnJoin()
                broadcastCustomAvatar()
                loadPersistedChatHistory()

                applyPlaybackState(
                    currentTrack = event.state.currentTrack,
                    isPlaying = event.state.isPlaying,
                    position = event.state.position,
                    queue = event.state.queue

                )
                applyHostVolumeIfNeeded(event.state.volume)
                updateGuestMuteState()
            }

            is ListenTogetherEvent.PlaybackSync -> {
                Timber.tag(TAG).d("PlaybackSync received: ${event.action.action}")

                val actionType = event.action.action
                val isQueueOp = actionType == PlaybackActions.QUEUE_ADD ||
                        actionType == PlaybackActions.QUEUE_REMOVE ||
                        actionType == PlaybackActions.QUEUE_CLEAR
                if (!isHost || isQueueOp) {
                    handlePlaybackSync(event.action)
                }
            }

            is ListenTogetherEvent.UserJoined -> {
                Timber.tag(TAG).d("[SYNC] User joined: ${event.username}")

                // Re-share our custom avatar so the newcomer can see it too.
                broadcastCustomAvatar()

                if (isHost) {
                    try {
                        val connection = playerConnection
                        val player = connection?.player
                        player?.currentMetadata?.let { metadata ->
                            Timber.tag(TAG).d("[SYNC] Sending current track to newly joined user: ${metadata.title}")
                            sendTrackChangeInternal(metadata)

                            if (player.playWhenReady) {
                                val pos = player.currentPosition
                                Timber.tag(TAG).d("[SYNC] Host playing, sending PLAY at $pos for new joiner")
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                            }

                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling UserJoined event")
                    }
                }
            }

            is ListenTogetherEvent.BufferWait -> {
                Timber.tag(TAG).d("BufferWait: waiting for ${event.waitingFor.size} users")
            }

            is ListenTogetherEvent.BufferComplete -> {
                Timber.tag(TAG).d("BufferComplete for track: ${event.trackId}")
                if (!isHost && bufferingTrackId == event.trackId) {
                    bufferCompleteReceivedForTrack = event.trackId
                    applyPendingSyncIfReady()
                }
            }

            is ListenTogetherEvent.SyncStateReceived -> {
                Timber.tag(TAG).d("SyncStateReceived: playing=${event.state.isPlaying}, pos=${event.state.position}, track=${event.state.currentTrack?.id}")
                if (!isHost) {
                    handleSyncState(event.state)
                }
            }

            is ListenTogetherEvent.Kicked -> {
                Timber.tag(TAG).d("Kicked from room: ${event.reason}")
                cleanup()
            }

            is ListenTogetherEvent.Disconnected -> {
                Timber.tag(TAG).d("Disconnected from server")

            }

            is ListenTogetherEvent.Reconnecting -> {
                Timber.tag(TAG).d("Reconnecting: attempt ${event.attempt}/${event.maxAttempts}")
            }

            is ListenTogetherEvent.Reconnected -> {
                Timber.tag(TAG).d("Reconnected to room: ${event.roomCode}, isHost: ${event.isHost}")
                loadPersistedChatHistory()
                try {
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Re-added player listener after reconnect")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to re-add player listener after reconnect")
                        }
                    }

                    if (event.isHost) {
                        lastSyncedIsPlaying = player?.playWhenReady
                        lastSyncedTrackId = player?.currentMediaItem?.mediaId

                        val currentMetadata = player?.currentMetadata
                        if (currentMetadata != null) {
                            val serverTrackId = event.state.currentTrack?.id
                            if (serverTrackId != currentMetadata.id) {
                                Timber.tag(TAG).d("Reconnected as host, server track ($serverTrackId) differs from local (${currentMetadata.id}), syncing")
                                sendTrackChangeInternal(currentMetadata)
                            } else {
                                Timber.tag(TAG).d("Reconnected as host, server already has current track $serverTrackId")
                            }

                            scope.launch {
                                delay(500)
                                try {
                                    val currentPlayer = playerConnection?.player
                                    if (currentPlayer?.playWhenReady == true) {
                                        val pos = currentPlayer.currentPosition
                                        Timber.tag(TAG)
                                            .d("Reconnected host is playing, sending PLAY at $pos")
                                        client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                                    }
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Error sending play state after reconnect")
                                }
                            }
                        }
                    } else {
                        Timber.tag(TAG).d("Reconnected as guest, syncing to host's current state")
                        applyPlaybackState(
                            currentTrack = event.state.currentTrack,
                            isPlaying = event.state.isPlaying,
                            position = event.state.position,
                            queue = event.state.queue,
                            bypassBuffer = true
                        )
                        applyHostVolumeIfNeeded(event.state.volume)

                        scope.launch {
                            delay(1000)
                            if (isInRoom && !isHost && smartResyncEnabled.value) {
                                Timber.tag(TAG).d("Requesting fresh sync after reconnect (Smart Resync)")
                                requestSync()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling Reconnected event")
                }
            }

            is ListenTogetherEvent.UserReconnected -> {
                Timber.tag(TAG).d("User reconnected: ${event.username}")

            }

            is ListenTogetherEvent.UserDisconnected -> {
                Timber.tag(TAG).d("User temporarily disconnected: ${event.username}")

            }

            is ListenTogetherEvent.HostChanged -> {
                Timber.tag(TAG).d("Host changed: new host is ${event.newHostName} (${event.newHostId})")
                val wasHost = isHost
                val nowIsHost = event.newHostId == userId.value

                if (wasHost && !nowIsHost) {
                    Timber.tag(TAG).d("Local user lost host role")
                    stopQueueSyncObservation()
                    stopVolumeSyncObservation()
                    if (playerListenerRegistered) {
                        playerConnection?.player?.removeListener(playerListener)
                        playerListenerRegistered = false
                    }

                    updateGuestMuteState()
                } else if (!wasHost && nowIsHost) {
                    Timber.tag(TAG).d("Local user gained host role")
                    updateGuestMuteState()

                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Added player listener as new host")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to add player listener on host transfer")
                        }
                    }

                    startQueueSyncObservation()
                    startVolumeSyncObservation()

                    val metadata = player?.currentMetadata
                    if (metadata != null) {
                        Timber.tag(TAG).d("New host sending current track: ${metadata.title}")
                        sendTrackChangeInternal(metadata)

                        if (player.playWhenReady) {
                            val position = player.currentPosition
                            Timber.tag(TAG).d("New host is playing, sending PLAY at $position")
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                        }
                    }
                }
            }

            is ListenTogetherEvent.JoinRequestReceived -> {
                Timber.tag(TAG).d("Join request received from ${event.username}")

            }

            is ListenTogetherEvent.LocalSuggestionApproved -> {
                try {
                    val connection = playerConnection
                    if (connection == null) {
                        Timber.tag(TAG).w("Cannot apply approved suggestion - no player connection")
                        return
                    }
                    val mediaMetadata = event.payload.trackInfo.toMediaMetadata()
                    val mediaItem = mediaMetadata.toMediaItem()
                    connection.playNext(mediaItem)
                    if (event.playImmediately) {
                        val player = connection.player
                        val nextIndex = player.currentMediaItemIndex + 1
                        if (nextIndex < player.mediaItemCount &&
                            player.getMediaItemAt(nextIndex).mediaId == mediaItem.mediaId
                        ) {
                            // Full manual-skip semantics: an in-flight crossfade (or its
                            // pauseAtEnd handoff) would otherwise keep the OLD song's
                            // audio playing through the secondary player while the
                            // queue already moved on, so cancel it first — exactly what
                            // a tap on the skip button does.
                            val wasPlaying = player.playWhenReady
                            runCatching { connection.service.prepareForManualSkip() }
                            player.seekToNext()
                            player.prepare()
                            player.playWhenReady = wasPlaying
                        } else {
                            Timber.tag(TAG).w("Approved suggestion not adjacent after queue insert; leaving it queued")
                        }
                    }
                    Timber.tag(TAG).d("Approved suggestion applied: ${mediaMetadata.title} (playImmediately=${event.playImmediately})")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error applying approved suggestion")
                }
            }

            is ListenTogetherEvent.ConnectionError -> {
                Timber.tag(TAG).e("Connection error: ${event.error}")
                cleanup()
            }

            is ListenTogetherEvent.ChatMessageReceived -> {
                Timber.tag(TAG).d("Chat message received from ${event.payload.username}")

                val exists = _chatMessages.value.any { it.timestamp == event.payload.timestamp && it.userId == event.payload.userId }
                if (!exists) {
                    _chatMessages.value = _chatMessages.value + event.payload
                    if (event.payload.userId != userId.value) {
                        _unreadMessageCount.value++
                    }
                    scheduleChatPersist()
                } else {
                    Timber.tag(TAG).w("Ignoring duplicate chat message from ${event.payload.username}")
                }
            }

            is ListenTogetherEvent.ChatControlReceived -> {
                applyChatControl(event.userId, event.username, event.event)
            }

            else -> {  }
        }
    }

    private fun cleanup() {
        if (lastRole == RoomRole.GUEST) {
            restoreGuestMuteState()
        }
        if (playerListenerRegistered) {
            playerConnection?.player?.removeListener(playerListener)
            playerListenerRegistered = false
        }
        stopQueueSyncObservation()
        stopHeartbeat()
        stopVolumeSyncObservation()

        lastSyncedIsPlaying = null
        lastSyncedTrackId = null
        bufferingTrackId = null
        isSyncing = false
        bufferCompleteReceivedForTrack = null
        lastRole = RoomRole.NONE
        lastSyncActionTime = 0L
        ++currentTrackGeneration
        _chatMessages.value = emptyList()
        _unreadMessageCount.value = 0
        _typingUsers.value = emptyList()
    }

    private fun updateGuestMuteState() {

        restoreGuestMuteState()
    }

    private fun saveMuteStateOnJoin() {
    }

    private fun restoreGuestMuteState() {
    }

    private fun applyHostVolumeIfNeeded(volume: Float?) {
        if (!syncHostVolumeEnabled.value || isHost || !isInRoom) return
        val connection = playerConnection ?: return
        val target = volume?.coerceIn(0f, 1f) ?: return
        connection.service.playerVolume.value = target
    }

    private fun applyPendingSyncIfReady() {
        val pending = pendingSyncState ?: return
        val pendingTrackId = pending.currentTrack?.id ?: bufferingTrackId ?: return
        val completeForTrack = bufferCompleteReceivedForTrack

        if (completeForTrack != pendingTrackId) return

        val connection = playerConnection ?: return
        val player = connection.player

        Timber.tag(TAG).d("Applying pending sync: track=$pendingTrackId, pos=${pending.position}, play=${pending.isPlaying}")
        isSyncing = true

        val targetPos = pending.position
        val posDiff = kotlin.math.abs(player.currentPosition - targetPos)
        val willPlay = pending.isPlaying

        val tolerance = if (willPlay && player.playWhenReady) PLAYBACK_POSITION_TOLERANCE_MS else POSITION_TOLERANCE_MS

        if (posDiff > tolerance) {
            Timber.tag(TAG).d("Applying pending sync: seeking ${player.currentPosition} -> $targetPos (diff ${posDiff}ms > ${tolerance}ms)")
            player.seekTo(targetPos)
        } else {
            Timber.tag(TAG).d("Applying pending sync: skipping seek (diff ${posDiff}ms < ${tolerance}ms)")
        }

        if (willPlay && !player.playWhenReady) {
            Timber.tag(TAG).d("Applying pending sync: starting playback")
            player.playForSync()
        } else if (!willPlay && player.playWhenReady) {
            Timber.tag(TAG).d("Applying pending sync: pausing playback")
            player.pause()
        }

        scope.launch {
            delay(200)
            isSyncing = false
        }

        bufferingTrackId = null
        pendingSyncState = null
        bufferCompleteReceivedForTrack = null
    }

    private fun handlePlaybackSync(action: PlaybackActionPayload) {
        val connection = playerConnection
        if (connection == null) {
            Timber.tag(TAG).w("Cannot sync playback - no player connection")
            return
        }
        val player = connection.player

        Timber.tag(TAG).d("Handling playback sync: ${action.action}, position: ${action.position}")

        isSyncing = true

        try {

            when (action.action) {
                PlaybackActions.PLAY -> {
                    val basePos = action.position ?: 0L
                    val now = System.currentTimeMillis()
                    val adjustedPos = action.serverTime?.let { serverTime ->
                        basePos + kotlin.math.max(0L, now - serverTime)
                    } ?: basePos

                    Timber.tag(TAG).d("Guest: PLAY at position $adjustedPos, currently playing=${player.playWhenReady}")

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = true,
                            position = adjustedPos,
                            lastUpdate = now
                        )).copy(
                            isPlaying = true,
                            position = adjustedPos,
                            lastUpdate = now
                        )
                        applyPendingSyncIfReady()
                        return
                    }

                    val posDiff = kotlin.math.abs(player.currentPosition - adjustedPos)
                    val alreadyPlaying = player.playWhenReady

                    if (alreadyPlaying && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PLAY debounced - already playing and in sync (diff ${posDiff}ms)")
                        return
                    }

                    if (alreadyPlaying) {
                        if (posDiff > PLAYBACK_POSITION_TOLERANCE_MS) {
                            Timber.tag(TAG).d("Guest: PLAY seeking during playback ${player.currentPosition} -> $adjustedPos (diff ${posDiff}ms)")
                            player.seekTo(adjustedPos)
                        } else {
                            Timber.tag(TAG).d("Guest: PLAY skipping seek - already playing, drift acceptable (${posDiff}ms < ${PLAYBACK_POSITION_TOLERANCE_MS}ms)")
                        }
                    } else {
                        if (posDiff > POSITION_TOLERANCE_MS) {
                            Timber.tag(TAG).d("Guest: PLAY seeking while paused ${player.currentPosition} -> $adjustedPos (diff ${posDiff}ms)")
                            player.seekTo(adjustedPos)
                        }

                        Timber.tag(TAG).d("Guest: Starting playback")
                        player.playForSync()
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.PAUSE -> {
                    val pos = action.position ?: 0L
                    val now = System.currentTimeMillis()

                    Timber.tag(TAG).d("Guest: PAUSE at position $pos, currently playing=${player.playWhenReady}")

                    if (bufferingTrackId != null) {
                        pendingSyncState = (pendingSyncState ?: SyncStatePayload(
                            currentTrack = roomState.value?.currentTrack,
                            isPlaying = false,
                            position = pos,
                            lastUpdate = now
                        )).copy(
                            isPlaying = false,
                            position = pos,
                            lastUpdate = now
                        )
                        applyPendingSyncIfReady()
                        return
                    }

                    val posDiff = kotlin.math.abs(player.currentPosition - pos)
                    val alreadyPaused = !player.playWhenReady

                    if (alreadyPaused && posDiff < POSITION_TOLERANCE_MS && (now - lastSyncActionTime) < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: PAUSE debounced - already paused and in sync (diff ${posDiff}ms)")
                        return
                    }

                    if (player.playWhenReady) {
                        Timber.tag(TAG).d("Guest: Pausing playback")
                        player.pause()
                    }

                    if (posDiff > POSITION_TOLERANCE_MS) {
                        Timber.tag(TAG).d("Guest: PAUSE seeking ${player.currentPosition} -> $pos (diff ${posDiff}ms)")
                        player.seekTo(pos)
                    } else {
                        Timber.tag(TAG).d("Guest: PAUSE skipping seek (diff ${posDiff}ms < ${POSITION_TOLERANCE_MS}ms)")
                    }
                    lastSyncActionTime = now
                }

                PlaybackActions.SEEK -> {
                    val pos = action.position ?: 0L
                    val now = System.currentTimeMillis()

                    if (now - lastSyncActionTime < SYNC_DEBOUNCE_THRESHOLD_MS) {
                        Timber.tag(TAG).d("Guest: SEEK debounced (only ${now - lastSyncActionTime}ms since last sync)")
                        return
                    }

                    if (kotlin.math.abs(player.currentPosition - pos) > POSITION_TOLERANCE_MS) {
                        Timber.tag(TAG).d("Guest: SEEK to $pos from ${player.currentPosition} (diff > ${POSITION_TOLERANCE_MS}ms)")
                        player.seekTo(pos)
                        lastSyncActionTime = now
                    } else {
                        Timber.tag(TAG).d("Guest: SEEK ignored (position diff < ${POSITION_TOLERANCE_MS}ms)")
                    }
                }

                PlaybackActions.CHANGE_TRACK -> {
                    action.trackInfo?.let { track ->
                        Timber.tag(TAG).d("Guest: CHANGE_TRACK to ${track.title}, queue size=${action.queue?.size}")

                        lastSyncActionTime = 0L

                        if (action.queue != null && action.queue.isNotEmpty()) {
                            val queueTitle = action.queueTitle
                            applyPlaybackState(
                                currentTrack = track,
                                isPlaying = false,
                                position = 0,
                                queue = action.queue,
                                queueTitle = queueTitle
                            )
                        } else {
                            bufferingTrackId = track.id
                            syncToTrack(track, false, 0)
                        }
                    }
                }

                PlaybackActions.SKIP_NEXT -> {
                    Timber.tag(TAG).d("Guest: SKIP_NEXT")
                    connection.seekToNext()
                }

                PlaybackActions.SKIP_PREV -> {
                    Timber.tag(TAG).d("Guest: SKIP_PREV")
                    connection.seekToPrevious()
                }

                PlaybackActions.QUEUE_ADD -> {
                    val track = action.trackInfo
                    if (track == null) {
                        Timber.tag(TAG).w("QUEUE_ADD missing trackInfo")
                    } else {
                        Timber.tag(TAG).d("Guest: QUEUE_ADD ${track.title}, insertNext=${action.insertNext == true}")
                        scope.launch(Dispatchers.IO) {
                            YouTube.queue(listOf(track.id)).onSuccess { list ->
                                val mediaItem = list.firstOrNull()?.toMediaMetadata()?.copy(
                                    suggestedBy = track.suggestedBy
                                )?.toMediaItem()
                                if (mediaItem != null) {
                                    launch(Dispatchers.Main) {

                                        if (action.insertNext == true) {
                                            connection.playNext(mediaItem)
                                        } else {
                                            connection.addToQueue(mediaItem)
                                        }
                                    }
                                } else {
                                    Timber.tag(TAG).w("QUEUE_ADD failed to resolve media item for ${track.id}")
                                }
                            }.onFailure {
                                Timber.tag(TAG).e(it, "QUEUE_ADD metadata fetch failed")
                            }
                        }
                    }
                }

                PlaybackActions.QUEUE_REMOVE -> {
                    val removeId = action.trackId
                    if (removeId.isNullOrEmpty()) {
                        Timber.tag(TAG).w("QUEUE_REMOVE missing trackId")
                    } else {
                        val startIndex = player.currentMediaItemIndex + 1
                        var removeIndex = -1
                        val total = player.mediaItemCount
                        for (i in startIndex until total) {
                            val id = player.getMediaItemAt(i).mediaId
                            if (id == removeId) { removeIndex = i; break }
                        }
                        if (removeIndex >= 0) {
                            Timber.tag(TAG).d("Guest: QUEUE_REMOVE index=$removeIndex id=$removeId")
                            player.removeMediaItem(removeIndex)
                        } else {
                            Timber.tag(TAG).w("QUEUE_REMOVE id not found in queue: $removeId")
                        }
                    }
                }

                PlaybackActions.QUEUE_CLEAR -> {
                    val currentIndex = player.currentMediaItemIndex
                    val count = player.mediaItemCount
                    val itemsAfter = count - (currentIndex + 1)
                    if (itemsAfter > 0) {
                        Timber.tag(TAG).d("Guest: QUEUE_CLEAR removing $itemsAfter items after current")
                        player.removeMediaItems(currentIndex + 1, count - (currentIndex + 1))
                    }
                }

                PlaybackActions.SET_VOLUME -> {
                    applyHostVolumeIfNeeded(action.volume)
                }

                PlaybackActions.SYNC_QUEUE -> {
                    val queue = action.queue
                    val queueTitle = action.queueTitle
                    if (queue != null) {
                        Timber.tag(TAG).d("Guest: SYNC_QUEUE size=${queue.size}")

                        activeSyncJob?.cancel()

                        scope.launch(Dispatchers.Main) {
                            if (playerConnection !== connection) return@launch
                            val player = connection.player

                            val mediaItems = queue.map { track ->
                                track.toMediaMetadata().toMediaItem()
                            }

                            val currentId = player.currentMediaItem?.mediaId
                            var newIndex = -1
                            if (currentId != null) {
                                newIndex = mediaItems.indexOfFirst { it.mediaId == currentId }
                            }

                            val currentPos = player.currentPosition
                            val wasPlaying = player.isPlaying

                            if (newIndex != -1) {
                                player.setMediaItems(mediaItems, newIndex, currentPos)
                            } else {
                                player.setMediaItems(mediaItems)
                            }

                            if (wasPlaying && !player.isPlaying) {
                                player.playForSync()
                            }

                            try {
                                connection.service.queueTitle = queueTitle
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to set queue title during SYNC_QUEUE")
                            }
                        }
                    }
                }
            }
        } finally {

            scope.launch {
                delay(200)
                isSyncing = false
            }
        }
    }

    private fun handleSyncState(state: SyncStatePayload) {
        val now = System.currentTimeMillis()
        val adjustedPos = if (state.isPlaying) {
            state.position + kotlin.math.max(0L, now - state.lastUpdate)
        } else {
            state.position
        }

        Timber.tag(TAG).d("handleSyncState: playing=${state.isPlaying}, pos=${state.position} -> adj=$adjustedPos, track=${state.currentTrack?.id}")

        applyPlaybackState(
            currentTrack = state.currentTrack,
            isPlaying = state.isPlaying,
            position = adjustedPos,
            queue = state.queue,
            bypassBuffer = true
        )
        applyHostVolumeIfNeeded(state.volume)
    }

    private fun applyPlaybackState(
        currentTrack: TrackInfo?,
        isPlaying: Boolean,
        position: Long,
        queue: List<TrackInfo>?,
        queueTitle: String? = null,
        bypassBuffer: Boolean = false
    ) {
        val connection = playerConnection
        if (connection == null) {
            Timber.tag(TAG).w("Cannot apply playback state - no player")
            return
        }
        val player = connection.player

        Timber.tag(TAG).d("Applying playback state: track=${currentTrack?.id}, pos=$position, queue=${queue?.size}, bypassBuffer=$bypassBuffer")

        activeSyncJob?.cancel()

        if (currentTrack == null) {
            Timber.tag(TAG).d("No track in state, pausing")
            val generation = ++currentTrackGeneration
            scope.launch(Dispatchers.Main) {
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration")
                    return@launch
                }

                if (playerConnection !== connection) return@launch
                isSyncing = true
                if (queue != null && queue.isNotEmpty()) {
                    val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }
                    player.setMediaItems(mediaItems)
                } else if (queue != null) {
                    player.clearMediaItems()
                }
                player.pause()
                try {
                    connection.service.queueTitle = queueTitle
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to set queue title for empty state")
                }
                isSyncing = false
            }
            return
        }

        bufferingTrackId = currentTrack.id
        val generation = ++currentTrackGeneration

        scope.launch(Dispatchers.Main) {
            if (currentTrackGeneration != generation) {
                Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration (track ${currentTrack.id})")
                return@launch
            }

            if (playerConnection !== connection) return@launch
            isSyncing = true

            try {
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Stale generation detected before setMediaItems: $generation vs $currentTrackGeneration")
                    return@launch
                }

                if (queue != null && queue.isNotEmpty()) {
                    val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }

                    var startIndex = mediaItems.indexOfFirst { it.mediaId == currentTrack.id }
                    if (startIndex == -1) {
                        Timber.tag(TAG).w("Current track ${currentTrack.id} not found in queue, defaulting to 0")
                        val singleItem = currentTrack.toMediaMetadata().toMediaItem()

                        player.setMediaItems(listOf(singleItem), 0, position)
                    } else {
                        player.setMediaItems(mediaItems, startIndex, position)
                    }
                } else {

                    Timber.tag(TAG).d("No queue in state, loading single track")

                    val item = currentTrack.toMediaMetadata().toMediaItem()
                    player.setMediaItems(listOf(item), 0, position)
                }

                player.seekTo(position)

                try {
                    connection.service.queueTitle = queueTitle ?: "Listen Together"
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to set queue title during applyPlaybackState")
                }

                if (bypassBuffer) {
                    Timber.tag(TAG).d("Bypass buffer: immediately applying play=$isPlaying at pos=$position")

                    var attempts = 0
                    while (player.playbackState != Player.STATE_READY && attempts < 100) {
                        delay(50)
                        attempts++
                    }
                    if (player.playbackState == Player.STATE_READY) {
                        Timber.tag(TAG).d("Player ready after ${attempts * 50}ms, seeking to $position")
                        player.seekTo(position)
                        if (isPlaying) {
                            player.playForSync()
                            Timber.tag(TAG).d("Bypass: PLAY issued")
                        } else {
                            player.pause()
                            Timber.tag(TAG).d("Bypass: PAUSE issued")
                        }
                    } else {
                        Timber.tag(TAG).w("Player not ready after 5s timeout during bypass sync")
                    }

                    pendingSyncState = null
                    bufferingTrackId = null
                    bufferCompleteReceivedForTrack = null
                } else {
                    player.pause()
                    pendingSyncState = SyncStatePayload(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        position = position,
                        lastUpdate = System.currentTimeMillis()
                    )
                    applyPendingSyncIfReady()
                    client.sendBufferReady(currentTrack.id)
                }

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error applying playback state")
            } finally {
                delay(200)
                isSyncing = false
            }
        }
    }

    private fun syncToTrack(track: TrackInfo, shouldPlay: Boolean, position: Long) {
        Timber.tag(TAG).d("syncToTrack: ${track.title}, play: $shouldPlay, pos: $position")

        bufferingTrackId = track.id
        val generation = currentTrackGeneration

        activeSyncJob?.cancel()
        activeSyncJob = scope.launch(Dispatchers.IO) {
            try {
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Skipping stale syncToTrack for ${track.id} (generation $generation vs $currentTrackGeneration)")
                    isSyncing = false
                    return@launch
                }

                YouTube.queue(listOf(track.id)).onSuccess { queue ->
                    Timber.tag(TAG).d("Got queue for track ${track.id}")
                    launch(Dispatchers.Main) {
                        if (currentTrackGeneration != generation) {
                            Timber.tag(TAG).d("Skipping stale track application for ${track.id} (generation $generation vs $currentTrackGeneration)")
                            isSyncing = false
                            return@launch
                        }

                        val connection = playerConnection ?: run {
                            isSyncing = false
                            return@launch
                        }
                        if (playerConnection !== connection) {
                            isSyncing = false
                            return@launch
                        }
                        isSyncing = true

                        connection.playQueue(
                            YouTubeQueue(
                                endpoint = WatchEndpoint(videoId = track.id),
                                preloadItem = queue.firstOrNull()?.toMediaMetadata()
                            )
                        )
                        try {
                            connection.service.queueTitle = "Listen Together"
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to set queue title")
                        }

                        var waitCount = 0
                        while (waitCount < 40) {

                            if (currentTrackGeneration != generation) {
                                Timber.tag(TAG).d("Generation changed while waiting for player ready - aborting sync for ${track.id}")
                                isSyncing = false
                                return@launch
                            }
                            try {
                                val player = connection.player
                                if (player.playbackState == Player.STATE_READY) {
                                    Timber.tag(TAG).d("Player ready after ${waitCount * 50}ms")
                                    break
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Error checking player state")
                                break
                            }
                            delay(50)
                            waitCount++
                        }

                        connection.player.pause()

                        pendingSyncState = SyncStatePayload(
                            currentTrack = track,
                            isPlaying = shouldPlay,
                            position = position,
                            lastUpdate = System.currentTimeMillis()
                        )

                        applyPendingSyncIfReady()

                        client.sendBufferReady(track.id)
                        Timber.tag(TAG).d("Sent buffer ready for ${track.id}, pending sync stored: pos=$position, play=$shouldPlay")

                        delay(100)
                        isSyncing = false
                    }
                }.onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to load track ${track.id}")
                    isSyncing = false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error syncing to track")
                isSyncing = false
            }
        }
    }

    fun connect() {
        Timber.tag(TAG).d("Connecting to server")
        client.connect()
    }

    fun disconnect() {
        Timber.tag(TAG).d("Disconnecting from server")
        cleanup()
        client.disconnect()
    }

    fun createRoom(username: String) {
        Timber.tag(TAG).d("Creating room with username: $username")
        client.createRoom(username)
    }

    fun joinRoom(roomCode: String, username: String) {
        Timber.tag(TAG).d("Joining room $roomCode as $username")
        client.joinRoom(roomCode, username)
    }

    fun leaveRoom() {
        Timber.tag(TAG).d("Leaving room")
        cleanup()
        client.leaveRoom()
    }

    fun approveJoin(userId: String) = client.approveJoin(userId)

    fun rejectJoin(userId: String, reason: String? = null) = client.rejectJoin(userId, reason)

    fun kickUser(userId: String, reason: String? = null) = client.kickUser(userId, reason)

    fun blockUser(username: String) = client.blockUser(username)

    fun unblockUser(username: String) = client.unblockUser(username)

    fun getBlockedUsernames(): Set<String> = blockedUsernames.value

    fun transferHost(newHostId: String) = client.transferHost(newHostId)

    fun sendTrackChange(metadata: MediaMetadata) {
        if (!isHost || isSyncing) return
        sendTrackChangeInternal(metadata)
    }

    private fun sendTrackChangeInternal(metadata: MediaMetadata) {
        if (!isHost) return

        val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L

        val trackInfo = TrackInfo(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(", ") { it.name },
            album = metadata.album?.title,
            duration = durationMs,
            thumbnail = metadata.thumbnailUrl,
            suggestedBy = metadata.suggestedBy
        )

        Timber.tag(TAG).d("Sending track change: ${trackInfo.title}, duration: $durationMs")

        val currentQueue = try {
            playerConnection?.queueWindows?.value?.map { it.toTrackInfo() }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current queue")
            null
        }
        val currentTitle = try {
            playerConnection?.queueTitle?.value
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current title")
            null
        }

        client.sendPlaybackAction(
            PlaybackActions.CHANGE_TRACK,
            queueTitle = currentTitle,
            trackInfo = trackInfo,
            queue = currentQueue
        )
    }

    private fun startQueueSyncObservation() {
        if (queueObserverJob?.isActive == true) return

        Timber.tag(TAG).d("Starting queue sync observation")
        queueObserverJob = scope.launch {
            playerConnection?.queueWindows
                ?.map { windows ->
                    windows.map { it.toTrackInfo() }
                }
                ?.distinctUntilChanged()
                ?.collectLatest { tracks ->
                    if (!isHost || !isInRoom || isSyncing) return@collectLatest

                    delay(500)

                    Timber.tag(TAG).d("Sending SYNC_QUEUE with ${tracks.size} items")
                    val queueTitle = try {
                        playerConnection?.queueTitle?.value
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to get queue title")
                        null
                    }
                    client.sendPlaybackAction(
                        PlaybackActions.SYNC_QUEUE,
                        queueTitle = queueTitle,
                        queue = tracks
                    )
                }
        }
    }

    private fun startVolumeSyncObservation() {
        if (volumeObserverJob?.isActive == true) return

        Timber.tag(TAG).d("Starting volume sync observation")
        volumeObserverJob = scope.launch {
            playerConnection?.service?.playerVolume
                ?.collectLatest { volume ->
                    if (!isHost || !isInRoom || !syncHostVolumeEnabled.value) return@collectLatest

                    val normalized = volume.coerceIn(0f, 1f)
                    val last = lastSyncedVolume
                    if (last != null && kotlin.math.abs(last - normalized) < 0.01f) return@collectLatest

                    lastSyncedVolume = normalized
                    client.sendPlaybackAction(PlaybackActions.SET_VOLUME, volume = normalized)
                }
        }
    }

    private fun stopVolumeSyncObservation() {
        volumeObserverJob?.cancel()
        volumeObserverJob = null
        lastSyncedVolume = null
    }

    private var lastSuggestedTrackId: String? = null

    /**
     * Shares the locally picked custom profile picture with the room (only when the
     * avatar index says we actually use one). Piggybacks on the chat relay.
     */
    fun broadcastCustomAvatar() {
        try {
            if (!isInRoom) return
            if (context.dataStore.get(ListenTogetherAvatarIndexKey, 0) != ListenTogetherAvatar.CUSTOM_AVATAR_INDEX) return
            val bytes = ListenTogetherAvatar.loadCustomAvatarBytes(context) ?: return
            scope.launch(Dispatchers.IO) {
                client.sendCustomAvatar(bytes)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error broadcasting custom avatar")
        }
    }

    /** Custom profile pictures received from room members, keyed by user id. */
    val customAvatars: kotlinx.coroutines.flow.StateFlow<Map<String, ByteArray>> get() = client.customAvatars

    /** Custom profile picture for a member: our own file for ourselves, the received broadcast otherwise. */
    fun customAvatarFor(userId: String?): ByteArray? {
        val selfId = this.userId.value
        val isSelf = userId == null || userId == selfId
        if (isSelf) {
            if (context.dataStore.get(ListenTogetherAvatarIndexKey, 0) != ListenTogetherAvatar.CUSTOM_AVATAR_INDEX) return null
            return ListenTogetherAvatar.loadCustomAvatarBytes(context)
        }
        return client.customAvatars.value[userId]
    }

    private fun suggestLocalTrackChange(trackId: String, player: Player) {
        try {
            if (trackId == lastSuggestedTrackId) return
            val roomTrackId = roomState.value?.currentTrack?.id
            if (trackId == roomTrackId) return

            val metadata = player.currentMetadata ?: return
            lastSuggestedTrackId = trackId
            val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
            val trackInfo =
                TrackInfo(
                    id = metadata.id,
                    title = metadata.title,
                    artist = metadata.artists.joinToString(", ") { it.name },
                    album = metadata.album?.title,
                    duration = durationMs,
                    thumbnail = metadata.thumbnailUrl,
                )
            Timber.tag(TAG).d("Guest track change sent as suggestion: ${metadata.title}")
            client.suggestTrack(trackInfo)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error suggesting local track change")
        }
    }

    private fun androidx.media3.common.Timeline.Window.toTrackInfo(): TrackInfo {
        val metadata = mediaItem.metadata ?: return TrackInfo("unknown", "Unknown", "Unknown", "", 0, "")
        val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
        return TrackInfo(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(", ") { it.name },
            album = metadata.album?.title,
            duration = durationMs,
            thumbnail = metadata.thumbnailUrl,
            suggestedBy = metadata.suggestedBy
        )
    }

    private fun stopQueueSyncObservation() {
        queueObserverJob?.cancel()
        queueObserverJob = null
    }

    private fun TrackInfo.toMediaMetadata(): MediaMetadata {
        return MediaMetadata(
            id = id,
            title = title,
            artists = listOf(Artist(id = "", name = artist)),
            album = if (album != null) Album(id = "", title = album) else null,
            duration = (duration / 1000).toInt(),
            thumbnailUrl = thumbnail,
            suggestedBy = suggestedBy
        )
    }

    fun requestSync() {
        if (!isInRoom || isHost) {
            Timber.tag(TAG).d("requestSync: not applicable (isInRoom=$isInRoom, isHost=$isHost)")
            return
        }
        Timber.tag(TAG).d("Requesting sync from server")
        client.requestSync()
    }

    fun clearLogs() = client.clearLogs()

    /** The username this device joined/created the room with (chat bookkeeping). */
    val currentUsername: String? get() = client.currentUsername

    fun suggestTrack(track: TrackInfo) = client.suggestTrack(track)

    fun approveSuggestion(suggestionId: String) {
        if (!isHost) return

        client.approveSuggestion(suggestionId)
    }

    fun rejectSuggestion(suggestionId: String, reason: String? = null) = client.rejectSuggestion(suggestionId, reason)

    fun forceReconnect() {
        Timber.tag(TAG).d("Forcing reconnection")
        client.forceReconnect()
    }

    fun getPersistedRoomCode(): String? = client.getPersistedRoomCode()

    fun getSessionAge(): Long = client.getSessionAge()

    private var heartbeatJob: Job? = null

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (heartbeatJob?.isActive == true && isInRoom && isHost) {
                delay(10000L)
                playerConnection?.player?.let { player ->
                    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                        val pos = player.currentPosition
                        Timber.tag(TAG).d("Host heartbeat: sending PLAY at pos $pos")
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                    }
                }
            }
        }
        Timber.tag(TAG).d("Host heartbeat started (10s interval)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.tag(TAG).d("Host heartbeat stopped")
    }

    fun sendChatMessage(message: String, replyTo: RepliedMessage? = null) {
        if (message.isBlank()) return
        client.sendChatMessage(message, replyTo)
    }

    /**
     * Applies a reactions/edit/delete/pin/typing control event to the local chat
     * state. All applications are idempotent, so the sender can apply locally and
     * again through its own server echo without harm. Typing echoes from self are
     * ignored so the composer never shows their own indicator.
     */
    private fun applyChatControl(fromUserId: String, fromUsername: String, control: ChatControlEvent) {
        when (control.action) {
            ChatControlEvent.ACTION_TYPING -> {
                if (fromUserId == userId.value) return
                val now = System.currentTimeMillis()
                _typingUsers.value =
                    _typingUsers.value.filter { it.userId != fromUserId } +
                        TypingUser(userId = fromUserId, username = fromUsername, expiresAt = now + TYPING_TTL_MS)
            }

            ChatControlEvent.ACTION_REACT,
            ChatControlEvent.ACTION_UNREACT -> {
                val emoji = control.emoji ?: return
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    val current = message.reactions[emoji].orEmpty()
                    val updated =
                        if (control.action == ChatControlEvent.ACTION_REACT) {
                            if (current.contains(fromUsername)) current else current + fromUsername
                        } else {
                            current - fromUsername
                        }
                    val newReactions =
                        if (updated.isEmpty()) message.reactions - emoji
                        else message.reactions + (emoji to updated)
                    message.copy(reactions = newReactions)
                }
            }

            ChatControlEvent.ACTION_EDIT -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                if (targetUserId != fromUserId) return // only one's own messages
                val newText = control.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    message.copy(message = newText, edited = true)
                }
            }

            ChatControlEvent.ACTION_DELETE -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                if (targetUserId != fromUserId) return
                val before = _chatMessages.value
                _chatMessages.value =
                    before.filterNot { it.userId == targetUserId && it.timestamp == targetTimestamp }
                if (before.size != _chatMessages.value.size) scheduleChatPersist()
            }

            ChatControlEvent.ACTION_PIN,
            ChatControlEvent.ACTION_UNPIN -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                val pinned = control.action == ChatControlEvent.ACTION_PIN
                updateChatMessage(targetUserId, targetTimestamp) { message ->
                    message.copy(pinned = pinned)
                }
            }
        }
    }

    private inline fun updateChatMessage(
        targetUserId: String,
        targetTimestamp: Long,
        transform: (ChatMessagePayload) -> ChatMessagePayload,
    ) {
        val messages = _chatMessages.value
        val index = messages.indexOfFirst { it.userId == targetUserId && it.timestamp == targetTimestamp }
        if (index == -1) return
        val updated = messages.toMutableList()
        updated[index] = transform(updated[index])
        _chatMessages.value = updated
        scheduleChatPersist()
    }

    /** Toggles the local user's emoji reaction on a message, room-wide. */
    fun toggleReaction(message: ChatMessagePayload, emoji: String) {
        val me = client.currentUsername ?: return
        val action =
            if (message.reactions[emoji]?.contains(me) == true) ChatControlEvent.ACTION_UNREACT
            else ChatControlEvent.ACTION_REACT
        val control = ChatControlEvent(
            action = action,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
            emoji = emoji,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", me, control)
    }

    /** Pins or unpins a message for everyone in the room. */
    fun setPinned(message: ChatMessagePayload, pinned: Boolean) {
        val control = ChatControlEvent(
            action = if (pinned) ChatControlEvent.ACTION_PIN else ChatControlEvent.ACTION_UNPIN,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", client.currentUsername ?: "", control)
    }

    /** Edits one of the local user's own messages, room-wide. */
    fun editMessage(message: ChatMessagePayload, newText: String) {
        if (newText.isBlank() || message.userId != userId.value) return
        val control = ChatControlEvent(
            action = ChatControlEvent.ACTION_EDIT,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
            text = newText,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", client.currentUsername ?: "", control)
    }

    /** Deletes one of the local user's own messages, room-wide. */
    fun deleteMessage(message: ChatMessagePayload) {
        if (message.userId != userId.value) return
        val control = ChatControlEvent(
            action = ChatControlEvent.ACTION_DELETE,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
        )
        client.sendChatControl(control)
        applyChatControl(userId.value ?: "", client.currentUsername ?: "", control)
    }

    /** Re-announces that the local user is composing, throttled to one frame per 2.5s. */
    fun notifyTyping() {
        if (!isInRoom) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < TYPING_THROTTLE_MS) return
        lastTypingSentAt = now
        client.sendChatControl(ChatControlEvent(action = ChatControlEvent.ACTION_TYPING))
    }

    /** Debounced persistence of the chat list, keyed by the local username. */
    private fun scheduleChatPersist() {
        chatPersistJob?.cancel()
        chatPersistJob = scope.launch(Dispatchers.IO) {
            delay(600)
            val username = client.currentUsername ?: return@launch
            val trimmed = _chatMessages.value.takeLast(MAX_PERSISTED_CHAT_MESSAGES)
            if (trimmed.isEmpty()) return@launch
            runCatching {
                context.dataStore.edit { prefs ->
                    prefs[ListenTogetherChatHistoryKey] =
                        chatHistoryJson.encodeToString(
                            PersistedChatHistory.serializer(),
                            PersistedChatHistory(username = username, messages = trimmed),
                        )
                }
            }.onFailure { Timber.tag(TAG).e(it, "Failed to persist chat history") }
        }
    }

    /** Restores the per-username chat history after a join/create/reconnect. */
    private fun loadPersistedChatHistory() {
        val username = client.currentUsername
        if (username.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            val raw = runCatching { context.dataStore.data.first()[ListenTogetherChatHistoryKey] }.getOrNull()
                ?: return@launch
            val stored = runCatching {
                chatHistoryJson.decodeFromString(PersistedChatHistory.serializer(), raw)
            }.getOrNull() ?: return@launch
            if (stored.username != username) return@launch
            withContext(Dispatchers.Main) {
                if (_chatMessages.value.isEmpty()) {
                    _chatMessages.value = stored.messages
                    Timber.tag(TAG).d("Restored ${stored.messages.size} persisted chat messages for $username")
                }
            }
        }
    }
}

/** A room member that is currently typing; expires after [ListenTogetherManager]'s typing TTL. */
@kotlinx.serialization.Serializable
data class TypingUser(
    val userId: String,
    val username: String,
    val expiresAt: Long,
)

/** Locally persisted chat history, keyed by the username that produced it. */
@kotlinx.serialization.Serializable
data class PersistedChatHistory(
    val username: String,
    val messages: List<ChatMessagePayload>,
)
