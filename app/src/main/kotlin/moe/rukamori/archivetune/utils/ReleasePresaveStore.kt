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

/**
 * DataStore entry holding the saved releases as a JSON array string.
 * Declared here rather than in constants/PreferenceKeys.kt so parallel work
 * on that file cannot conflict with this feature.
 */
val ReleasePresaveKey: Preferences.Key<String> = stringPreferencesKey("releasePresaves")

/** One release the user saved ("Listen Later") before it dropped. */
data class PresavedRelease(
    /** Stable identity of the release within its source surface. */
    val releaseId: String,
    val title: String,
    val artistName: String,
    /** Type label of the surface it was saved from ("album"/"single"/"ep"). */
    val releaseType: String,
    /**
     * Epoch milliseconds of the announced release moment, or 0 when the
     * source surface carried no date — such entries count as available now.
     */
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

/** Tolerant parse: a blank or corrupt value simply yields an empty list. */
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

/**
 * Adds [release] when absent (keyed by releaseId) or removes it when present,
 * preserving insertion order and capping the collection at [maxItems].
 */
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

/** Persists [list] under [ReleasePresaveKey], replacing any previous value. */
suspend fun Context.setPresavedReleases(list: List<PresavedRelease>) {
    dataStore.edit { it[ReleasePresaveKey] = serializePresavedReleases(list) }
}

/**
 * A saved release counts as released once its announced moment has passed.
 * Entries with no known date (releaseAtMillis <= 0) are treated as
 * "unknown -> released/available" so they never show a bogus countdown.
 */
fun PresavedRelease.isReleased(nowMillis: Long = System.currentTimeMillis()): Boolean =
    releaseAtMillis <= 0L || releaseAtMillis in 1..nowMillis
