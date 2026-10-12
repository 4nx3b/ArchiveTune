/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "rec_playback_signal",
    indices = [
        Index(value = ["songId"]),
        Index(value = ["startedAtMs"]),
    ],
)
data class RecPlaybackSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val leftAtRatio: Float,
    val replay: Boolean,
)

@Immutable
@Entity(
    tableName = "rec_feedback",
    indices = [
        Index(value = ["targetSongId"]),
        Index(value = ["targetArtistId"]),
    ],
)
data class RecFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetSongId: String?,
    val targetArtistId: String?,
    val kind: String,
    val createdAtMs: Long,
)
