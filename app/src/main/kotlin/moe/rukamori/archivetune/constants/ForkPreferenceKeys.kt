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

// Listen Together (ported from vivi-music beta — same key names and types as
// vivi's constants/PreferenceKeys.kt so persisted values carry over semantics 1:1).
val ListenTogetherServerUrlKey = stringPreferencesKey("listenTogetherServerUrl")
val ListenTogetherUserIdKey = stringPreferencesKey("listenTogetherUserId")
val ListenTogetherRoomCodeKey = stringPreferencesKey("listenTogetherRoomCode")
val ListenTogetherSessionTokenKey = stringPreferencesKey("listenTogetherSessionToken")
val ListenTogetherSessionTimestampKey = longPreferencesKey("listenTogetherSessionTimestamp")
val ListenTogetherIsHostKey = booleanPreferencesKey("listenTogetherIsHost")
val ListenTogetherAvatarIndexKey = intPreferencesKey("listenTogetherAvatarIndex")
val ListenTogetherAutoApprovalKey = booleanPreferencesKey("listenTogetherAutoApproval")
val ListenTogetherSmartResyncKey = booleanPreferencesKey("listenTogetherSmartResync")
val ListenTogetherSyncVolumeKey = booleanPreferencesKey("listenTogetherSyncVolume")
val ListenTogetherBlockedUsersKey = stringPreferencesKey("listenTogetherBlockedUsers")
// UI-side keys (T1-C): same names/types as vivi's PreferenceKeys.kt
// (stringPreferencesKey "listenTogetherUsername", booleanPreferencesKey "listenTogetherInTopBar").
val ListenTogetherUsernameKey = stringPreferencesKey("listenTogetherUsername")
val ListenTogetherInTopBarKey = booleanPreferencesKey("listenTogetherInTopBar")
