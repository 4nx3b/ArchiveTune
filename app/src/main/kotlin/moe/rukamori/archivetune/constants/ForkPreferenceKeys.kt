package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

enum class DeezerAudioQuality {
    FLAC,
    MP3_320,
    MP3_128,
}

val DeezerAudioQualityOptions =
    listOf(
        DeezerAudioQuality.FLAC,
        DeezerAudioQuality.MP3_320,
        DeezerAudioQuality.MP3_128,
    )

val DeezerAudioQualityKey = stringPreferencesKey("deezerAudioQuality")

fun DeezerAudioQuality.toFormatName(): String =
    when (this) {
        DeezerAudioQuality.FLAC -> "FLAC"
        DeezerAudioQuality.MP3_320 -> "MP3_320"
        DeezerAudioQuality.MP3_128 -> "MP3_128"
    }

val ListenTogetherServerUrlKey = stringPreferencesKey("listenTogetherServerUrl")
val ListenTogetherUserIdKey = stringPreferencesKey("listenTogetherUserId")
val ListenTogetherRoomCodeKey = stringPreferencesKey("listenTogetherRoomCode")
val ListenTogetherSessionTokenKey = stringPreferencesKey("listenTogetherSessionToken")
val ListenTogetherSessionTimestampKey = longPreferencesKey("listenTogetherSessionTimestamp")
val ListenTogetherIsHostKey = booleanPreferencesKey("listenTogetherIsHost")
val ListenTogetherAvatarIndexKey = intPreferencesKey("listenTogetherAvatarIndex")
val ListenTogetherAutoApprovalKey = booleanPreferencesKey("listenTogetherAutoApproval")
val ListenTogetherSuggestionAutoApproveKey = booleanPreferencesKey("listenTogetherSuggestionAutoApprove")
val ListenTogetherCustomAvatarUriKey = stringPreferencesKey("listenTogetherCustomAvatarUri")
val ListenTogetherSyncVolumeKey = booleanPreferencesKey("listenTogetherSyncVolume")
val ListenTogetherBlockedUsersKey = stringPreferencesKey("listenTogetherBlockedUsers")
val ListenTogetherChatNotificationsKey = booleanPreferencesKey("listenTogetherChatNotifications")
val ListenTogetherChatHistoryKey = stringPreferencesKey("listenTogetherChatHistory")

/** Master switch for the connectivity resync engine: when enabled, network
 * restoration probes the socket and repairs/re-syncs the room automatically. */
val ListenTogetherResyncKey = booleanPreferencesKey("listenTogetherResync")

/** Room code -> display name map, so rooms the host named keep their name
 * across rejoins (guests adopt the broadcast name and remember it too). */
val ListenTogetherRoomNamesKey = stringPreferencesKey("listenTogetherRoomNames")

/** In-app chat notification popup: while the app is in the foreground but the
 * chat screen is closed, incoming room messages surface as a stacked heads-up
 * card with a quick reply (and mark-as-read on mentions) instead of only the
 * system shade notification. */
val ListenTogetherInAppNotificationsKey = booleanPreferencesKey("listenTogetherInAppNotifications")

/** Chat wallpaper: a content URI to an image the LOCAL user picked, rendered
 * behind the Listen Together chat. Deliberately device-local — it never syncs
 * and other members never see it. */
val ListenTogetherChatWallpaperKey = stringPreferencesKey("listenTogetherChatWallpaper")

val ListenTogetherUsernameKey = stringPreferencesKey("listenTogetherUsername")
val ListenTogetherInTopBarKey = booleanPreferencesKey("listenTogetherInTopBar")

val TikTokMainLyricsEnabledKey = booleanPreferencesKey("tiktokMainLyricsEnabled")
