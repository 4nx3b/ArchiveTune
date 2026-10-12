/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import androidx.compose.runtime.Immutable

@Immutable
data class RecPlaybackSignalSnapshot(
    val songId: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val leftAtRatio: Float,
    val replay: Boolean,
)

enum class RecFeedbackKind {
    LIKE,
    DISLIKE,
    MORE_LIKE,
    EXCLUDE_ARTIST,
    EXCLUDE_TRACK,
    DISMISS,
}

@Immutable
data class RecFeedback(
    val id: Long,
    val targetSongId: String?,
    val targetArtistId: String?,
    val kind: RecFeedbackKind,
    val createdAtMs: Long,
)

@Immutable
data class RecSongStats(
    val songId: String,
    val title: String,
    val artistIds: List<String>,
    val artistNames: List<String>,
    val albumId: String?,
    val albumTitle: String?,
    val thumbnailUrl: String?,
    val libraryAvailable: Boolean,
    val playCount: Int,
    val totalListenedMs: Long,
    val avgCompletion: Float,
    val earlySkipCount: Int,
    val lastPlayedAtMs: Long,
    val liked: Boolean,
    val playlistAddCount: Int,
)

@Immutable
data class RecArtistStats(
    val artistId: String,
    val artistName: String,
    val playCount: Int,
    val totalListenedMs: Long,
    val lastPlayedAtMs: Long,
    val likedSongCount: Int,
)

@Immutable
data class RecContext(
    val currentSongId: String? = null,
    val currentArtistIds: List<String> = emptyList(),
    val currentAlbumId: String? = null,
)

@Immutable
data class RecEngineConfig(
    val recencyWeight: Float = 0.5f,
    val diversityArtistCap: Int = 2,
    val candidateLimit: Int = 400,
    val outputLimit: Int = 20,
    val minListensForPreference: Int = 2,
    val noveltyHalfLifeDays: Float = 30f,
    val recencyHalfLifeDays: Float = 21f,
    val dismissedSuppressMs: Long = 14L * 24 * 60 * 60 * 1000,
    val excludedFeedbackSuppressMs: Long = 365L * 24 * 60 * 60 * 1000,
    val recentlyRecommendedPenaltyWindowMs: Long = 7L * 24 * 60 * 60 * 1000,
    val recentlyRecommendedPenalty: Float = 0.6f,
    val maxAgeDays: Int = 180,
)

enum class RecReason {
    TOP_ARTIST,
    SAME_ARTIST_AS_CONTEXT,
    SAME_ALBUM_AS_CONTEXT,
    FORGOTTEN_FAVORITE,
    NOVEL_FROM_LIKED_ARTIST,
    DISCOVERY_KEYWORD,
    KEEP_LISTENING_ARTIST,
}

@Immutable
data class RankedRecommendation(
    val stats: RecSongStats,
    val score: Float,
    val reason: RecReason,
    val reasonSeed: String?,
)

@Immutable
data class RecommendationSection(
    val title: String,
    val items: List<RankedRecommendation>,
)

@Immutable
data class RecsState(
    val loading: Boolean = true,
    val personalized: List<RankedRecommendation> = emptyList(),
    val discover: List<RankedRecommendation> = emptyList(),
    val similarSounds: List<RankedRecommendation> = emptyList(),
    val coldStart: Boolean = true,
    val error: Boolean = false,
    val offline: Boolean = false,
)
