/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * "Save / Listen Later" persistence for the Pre-save & Release Countdown
 * feature: releases saved from any upcoming/new-release surface (the
 * new-release grid, the artist page, ...) survive restarts in the settings
 * DataStore. Mirrors the SpeedDialPins conventions — pure parse / serialize /
 * toggle functions over one string value plus a tiny write helper — so other
 * surfaces can adopt it without coupling to a ViewModel.
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

val ReleasePresaveKey: Preferences.Key<String> = stringPreferencesKey("releasePresaves")

data class PresavedRelease(

    val releaseId: String,
    val title: String,
    val artistName: String,

    val releaseType: String,

    val releaseAtMillis: Long,
    val thumbnailUrl: String,
) {
    fun encode(): JSONObject =
        JSONObject().apply {
            put("releaseId", releaseId)
            put("title", title)
            put("artistName", artistName)
            put("releaseType", releaseType)
            put("releaseAtMillis", releaseAtMillis)
            put("thumbnailUrl", thumbnailUrl)
        }

    companion object {
        fun decode(json: JSONObject): PresavedRelease? {
            val releaseId = json.optString("releaseId", "").trim()
            if (releaseId.isEmpty()) return null
            return PresavedRelease(
                releaseId = releaseId,
                title = json.optString("title", ""),
                artistName = json.optString("artistName", ""),
                releaseType = json.optString("releaseType", "album"),
                releaseAtMillis = json.optLong("releaseAtMillis", 0L),
                thumbnailUrl = json.optString("thumbnailUrl", ""),
            )
        }
    }
}

fun parsePresavedReleases(raw: String): List<PresavedRelease> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    return runCatching {
        val array = JSONArray(trimmed)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { PresavedRelease.decode(it) }
        }
    }.getOrDefault(emptyList())
}

fun serializePresavedReleases(list: List<PresavedRelease>): String {
    if (list.isEmpty()) return ""
    val array = JSONArray()
    list.forEach { release -> array.put(release.encode()) }
    return array.toString()
}

fun togglePresavedRelease(
    list: List<PresavedRelease>,
    release: PresavedRelease,
    maxItems: Int = 100,
): List<PresavedRelease> {
    val exists = list.any { it.releaseId == release.releaseId }
    return if (exists) {
        list.filterNot { it.releaseId == release.releaseId }
    } else {
        (list + release).distinctBy { it.releaseId }.take(maxItems)
    }
}

suspend fun Context.setPresavedReleases(list: List<PresavedRelease>) {
    dataStore.edit { it[ReleasePresaveKey] = serializePresavedReleases(list) }
}

fun PresavedRelease.isReleased(nowMillis: Long = System.currentTimeMillis()): Boolean =
    releaseAtMillis <= 0L || releaseAtMillis in 1..nowMillis
