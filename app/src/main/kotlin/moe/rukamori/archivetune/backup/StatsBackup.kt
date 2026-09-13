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
import moe.rukamori.archivetune.db.entities.Event
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Serializable
data class StatsBackupPayload(
    val version: Int = STATS_BACKUP_VERSION,
    val events: List<StatsEventBackup> = emptyList(),
)

@Serializable
data class StatsEventBackup(
    val songId: String,
    val timestamp: Long,
    val playTime: Long,
)

private const val STATS_BACKUP_VERSION = 1

private val statsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

object StatsBackup {
    const val ZIP_ENTRY_NAME = "stats/events.json"

    fun encode(events: List<Event>): String {
        val payload =
            StatsBackupPayload(
                events =
                    events.map {
                        StatsEventBackup(
                            songId = it.songId,
                            timestamp =
                                it.timestamp
                                    .atZone(ZoneOffset.UTC)
                                    .toInstant()
                                    .toEpochMilli(),
                            playTime = it.playTime,
                        )
                    },
            )
        return statsJson.encodeToString(StatsBackupPayload.serializer(), payload)
    }

    fun decode(text: String): StatsBackupPayload? =
        runCatching { statsJson.decodeFromString(StatsBackupPayload.serializer(), text) }.getOrNull()
}

suspend fun mergeStatsIntoDatabase(
    database: MusicDatabase,
    payload: StatsBackupPayload,
): Int {
    if (payload.events.isEmpty()) return 0

    val knownSongIds = database.allSongIdsOnce().toHashSet()
    if (knownSongIds.isEmpty()) return 0

    val existing =
        database
            .allEventsOnce()
            .map { Triple(it.songId, it.timestamp, it.playTime) }
            .toHashSet()

    val toInsert =
        payload.events.mapNotNull { backup ->
            val timestamp: LocalDateTime =
                Instant.ofEpochMilli(backup.timestamp).atZone(ZoneOffset.UTC).toLocalDateTime()
            if (backup.songId !in knownSongIds) return@mapNotNull null
            if (Triple(backup.songId, timestamp, backup.playTime) in existing) return@mapNotNull null
            Event(
                songId = backup.songId,
                timestamp = timestamp,
                playTime = backup.playTime,
            )
        }
    if (toInsert.isEmpty()) return 0

    database.insertEvents(toInsert)

    toInsert
        .groupBy { it.songId }
        .forEach { (songId, events) ->
            database.incrementSongTotalPlayTime(songId, events.sumOf { it.playTime })
        }

    return toInsert.size
}
