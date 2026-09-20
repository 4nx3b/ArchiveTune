/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LyricsEntity

@Serializable
data class LyricsBackupPayload(
    val version: Int = LYRICS_BACKUP_VERSION,
    val entries: List<LyricsBackupEntry> = emptyList(),
)

@Serializable
data class LyricsBackupEntry(
    val id: String,
    val lyrics: String,
    val source: String,
    val providerName: String? = null,
    val updatedAt: Long? = null,
)

private const val LYRICS_BACKUP_VERSION = 1

private val lyricsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

object LyricsBackup {
    const val ZIP_ENTRY_NAME = "lyrics/lyrics.json"

    fun encode(entities: List<LyricsEntity>): String {
        val payload =
            LyricsBackupPayload(
                entries =
                    entities.map { entity ->
                        LyricsBackupEntry(
                            id = entity.id,
                            lyrics = entity.lyrics,
                            source = entity.source,
                            providerName = entity.providerName?.takeIf { it.isNotBlank() },
                            updatedAt = entity.updatedAt,
                        )
                    },
            )
        return lyricsJson.encodeToString(LyricsBackupPayload.serializer(), payload)
    }

    fun decode(text: String): LyricsBackupPayload? =
        runCatching { lyricsJson.decodeFromString(LyricsBackupPayload.serializer(), text) }.getOrNull()
}

/**
 * Merges a lyrics backup into the database without clobbering anything the user
 * already has on this device: rows that exist (user edits, AI translations, another
 * provider's lyrics) are kept as-is, and the "not found" sentinel is never restored.
 */
suspend fun mergeLyricsIntoDatabase(
    database: MusicDatabase,
    payload: LyricsBackupPayload,
): Int {
    if (payload.entries.isEmpty()) return 0

    val valid = payload.entries.filter { it.id.isNotBlank() && it.lyrics.isNotBlank() }
    if (valid.isEmpty()) return 0

    var merged = 0
    database.withTransaction {
        valid.forEach { entry ->
            if (entry.lyrics == LyricsEntity.LYRICS_NOT_FOUND) return@forEach
            val known = database.getLyricsById(entry.id)
            if (known == null || known.lyrics == LyricsEntity.LYRICS_NOT_FOUND) {
                database.replaceLyrics(
                    id = entry.id,
                    lyrics = entry.lyrics,
                    source = entry.source,
                    providerName = entry.providerName.orEmpty(),
                )
                merged++
            }
        }
    }
    return merged
}
