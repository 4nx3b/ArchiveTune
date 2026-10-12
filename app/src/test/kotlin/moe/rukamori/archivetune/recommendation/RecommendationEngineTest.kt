/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    private fun song(
        id: String,
        artistId: String = "ar_$id",
        plays: Int = 0,
        listenedMs: Long = 0L,
        liked: Boolean = false,
        lastPlayedAt: Long = 0L,
        completion: Float = 1f,
        earlySkips: Int = 0,
        playlistAdds: Int = 0,
        albumId: String? = null,
    ) = RecSongStats(
        songId = id,
        title = "Song $id",
        artistIds = listOf(artistId),
        artistNames = listOf("Artist $artistId"),
        albumId = albumId,
        albumTitle = albumId?.let { "Album $it" },
        thumbnailUrl = null,
        libraryAvailable = true,
        playCount = plays,
        totalListenedMs = listenedMs,
        avgCompletion = completion,
        earlySkipCount = earlySkips,
        lastPlayedAtMs = lastPlayedAt,
        liked = liked,
        playlistAddCount = playlistAdds,
    )

    private fun artist(
        artistId: String,
        plays: Int,
        listenedMs: Long = plays * 200_000L,
        lastPlayedAt: Long = now - dayMs,
        likedSongs: Int = 0,
    ) = RecArtistStats(
        artistId = artistId,
        artistName = "Artist $artistId",
        playCount = plays,
        totalListenedMs = listenedMs,
        lastPlayedAtMs = lastPlayedAt,
        likedSongCount = likedSongs,
    )

    private val config =
        RecEngineConfig(
            recencyWeight = 0.5f,
            diversityArtistCap = 2,
            outputLimit = 10,
            minListensForPreference = 2,
        )

    @Test
    fun emptyInputProducesEmptyOutput() {
        val candidates = RecommendationEngine.generateCandidates(emptyList(), emptyList(), RecContext(), config)
        assertTrue(candidates.isEmpty())
        val ranked =
            RecommendationEngine.rank(candidates, emptyList(), emptyList(), emptyList(), emptyMap(), RecContext(), config, now)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun songsFromMostPlayedArtistRankHigher() {
        val songs =
            listOf(
                song("s_heard_1", artistId = "fav_artist", plays = 3, listenedMs = 600_000L, lastPlayedAt = now - dayMs),
                song("s_heard_2", artistId = "fav_artist", plays = 2, listenedMs = 400_000L, lastPlayedAt = now - dayMs),
                song("s_other_1", artistId = "other_artist", plays = 1, listenedMs = 100_000L, lastPlayedAt = now - dayMs),
            )
        val artists =
            listOf(
                artist("fav_artist", plays = 5, lastPlayedAt = now - dayMs),
                artist("other_artist", plays = 1, lastPlayedAt = now - 60 * dayMs),
            )
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)

        assertTrue(ranked.isNotEmpty())
        assertTrue(
            ranked.take(2).all { it.stats.artistIds.first() == "fav_artist" },
        )
    }

    @Test
    fun recencyWeightChangesRanking() {
        val oldTop = song("old_top", artistId = "a_old", plays = 8, listenedMs = 2_000_000L, lastPlayedAt = now - 90 * dayMs)
        val recent = song("recent", artistId = "a_recent", plays = 3, listenedMs = 500_000L, lastPlayedAt = now - dayMs)
        val songs = listOf(oldTop, recent)
        val artists =
            listOf(
                artist("a_old", plays = 8, lastPlayedAt = now - 90 * dayMs),
                artist("a_recent", plays = 3, lastPlayedAt = now - dayMs),
            )
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)

        val pastFocused =
            RecommendationEngine.rank(
                candidates, songs, artists, emptyList(), emptyMap(), RecContext(),
                config.copy(recencyWeight = 0.05f), now,
            )
        val recentFocused =
            RecommendationEngine.rank(
                candidates, songs, artists, emptyList(), emptyMap(), RecContext(),
                config.copy(recencyWeight = 0.95f), now,
            )

        val recentScorePast = pastFocused.first { it.stats.songId == "recent" }.score
        val oldScorePast = pastFocused.first { it.stats.songId == "old_top" }.score
        val recentScoreNow = recentFocused.first { it.stats.songId == "recent" }.score
        val oldScoreNow = recentFocused.first { it.stats.songId == "old_top" }.score

        assertTrue(recentScoreNow - oldScoreNow > recentScorePast - oldScorePast)
    }

    @Test
    fun negativeFeedbackFiltersSongsAndArtists() {
        val songs =
            listOf(
                song("s_bad", artistId = "bad_artist", plays = 3, listenedMs = 400_000L, lastPlayedAt = now - dayMs),
                song("s_bad2", artistId = "bad_artist", plays = 1, listenedMs = 100_000L, lastPlayedAt = now - dayMs),
                song("s_good", artistId = "good_artist", plays = 3, listenedMs = 400_000L, lastPlayedAt = now - dayMs),
            )
        val artists =
            listOf(
                artist("bad_artist", plays = 4, lastPlayedAt = now - dayMs),
                artist("good_artist", plays = 3, lastPlayedAt = now - dayMs),
            )
        val feedback =
            listOf(
                RecFeedback(1, "s_bad", null, RecFeedbackKind.DISLIKE, now),
                RecFeedback(2, null, "bad_artist", RecFeedbackKind.EXCLUDE_ARTIST, now),
            )
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, feedback, emptyMap(), RecContext(), config, now)

        assertTrue(ranked.none { it.stats.songId == "s_bad" || it.stats.songId == "s_bad2" })
        assertTrue(ranked.none { it.stats.artistIds.contains("bad_artist") })
        assertTrue(ranked.any { it.stats.songId == "s_good" })
    }

    @Test
    fun excludedTrackNeverAppears() {
        val songs = listOf(song("s_excluded", plays = 5, listenedMs = 900_000L, lastPlayedAt = now - dayMs))
        val artists = listOf(artist("ar_s_excluded", plays = 5, lastPlayedAt = now - dayMs))
        val feedback = listOf(RecFeedback(1, "s_excluded", null, RecFeedbackKind.EXCLUDE_TRACK, now))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, feedback, emptyMap(), RecContext(), config, now)
        assertTrue(ranked.none { it.stats.songId == "s_excluded" })
    }

    @Test
    fun dismissedSongIsSuppressedThenReturns() {
        val songs = listOf(song("s_dismissed", plays = 3, listenedMs = 500_000L, lastPlayedAt = now - dayMs))
        val artists = listOf(artist("ar_s_dismissed", plays = 3, lastPlayedAt = now - dayMs))
        val dismissAt = now - dayMs
        val feedback = listOf(RecFeedback(1, "s_dismissed", null, RecFeedbackKind.DISMISS, dismissAt))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)

        val duringSuppression =
            RecommendationEngine.rank(candidates, songs, artists, feedback, emptyMap(), RecContext(), config, now)
        assertTrue(duringSuppression.none { it.stats.songId == "s_dismissed" })

        val afterSuppression =
            RecommendationEngine.rank(
                candidates, songs, artists, feedback, emptyMap(), RecContext(),
                config, now + 20 * dayMs,
            )
        assertTrue(afterSuppression.any { it.stats.songId == "s_dismissed" })
    }

    @Test
    fun candidatesAreDeduplicated() {
        val songs =
            (1..30).map { song("dup_$it", artistId = "one_artist", plays = 1, lastPlayedAt = now - dayMs) }
        val artists = listOf(artist("one_artist", plays = 30, lastPlayedAt = now - dayMs))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        assertEquals(candidates.size, candidates.map { it.stats.songId }.distinct().size)
    }

    @Test
    fun diversityCapsPerArtistInOutput() {
        val songs =
            (1..9).map { index -> song("cap_$index", artistId = "artist_${index / 3}", plays = 0) } +
                (1..9).map { index ->
                    song(
                        "cap_played_$index",
                        artistId = "artist_${index / 3}",
                        plays = 2,
                        listenedMs = 300_000L,
                        lastPlayedAt = now - dayMs,
                    )
                }
        val artists = (0..2).map { artist("artist_$it", plays = 6, lastPlayedAt = now - dayMs) }
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(
                candidates, songs, artists, emptyList(), emptyMap(), RecContext(),
                config.copy(diversityArtistCap = 2), now,
            )

        val topSix = ranked.take(6)
        val perArtist = topSix.groupingBy { it.stats.artistIds.first() }.eachCount()
        assertTrue(perArtist.values.all { it <= 2 })
    }

    @Test
    fun coldStartDetectedWithSparseHistory() {
        val songs =
            listOf(
                song("s_one_play", plays = 1, lastPlayedAt = now - dayMs),
                song("s_liked_one", plays = 1, liked = true, lastPlayedAt = now - dayMs),
            )
        val artists = listOf(artist("a1", plays = 1), artist("a2", plays = 1))
        assertTrue(RecommendationEngine.isColdStart(songs, artists, config))

        val rich =
            (1..6).map { song("rich_$it", plays = 3, listenedMs = 500_000L, lastPlayedAt = now - dayMs) }
        val richArtists = listOf(artist("rich_artist", plays = 18))
        assertFalse(RecommendationEngine.isColdStart(rich, richArtists, config))
    }

    @Test
    fun singleListenWithoutLikeIsNotStrongPreference() {
        val barelyPlayed = song("bare", plays = 1, listenedMs = 20_000L, completion = 0.2f, lastPlayedAt = now - dayMs)
        val solid =
            song(
                "solid",
                plays = 4,
                listenedMs = 800_000L,
                completion = 0.95f,
                liked = true,
                lastPlayedAt = now - dayMs,
            )
        val songs = listOf(barelyPlayed, solid)
        val artists = listOf(artist("ar_bare", plays = 1), artist("ar_solid", plays = 4, likedSongs = 1))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)

        val solidRank = ranked.indexOfFirst { it.stats.songId == "solid" }
        val bareRank = ranked.indexOfFirst { it.stats.songId == "bare" }
        if (bareRank >= 0) {
            assertTrue(solidRank < bareRank)
        }
    }

    @Test
    fun earlySkipsReduceRanking() {
        val skipped =
            song(
                "skipped",
                plays = 3,
                listenedMs = 60_000L,
                completion = 0.1f,
                earlySkips = 3,
                lastPlayedAt = now - dayMs,
            )
        val enjoyed =
            song(
                "enjoyed",
                plays = 3,
                listenedMs = 600_000L,
                completion = 0.95f,
                lastPlayedAt = now - dayMs,
            )
        val songs = listOf(skipped, enjoyed)
        val artists = listOf(artist("ar_skipped", plays = 3), artist("ar_enjoyed", plays = 3))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)

        val skippedScore = ranked.first { it.stats.songId == "skipped" }.score
        val enjoyedScore = ranked.first { it.stats.songId == "enjoyed" }.score
        assertTrue(enjoyedScore > skippedScore)
    }

    @Test
    fun rankingIsDeterministic() {
        val songs =
            (1..12).map { index ->
                song(
                    "det_$index",
                    artistId = "det_artist_${index % 3}",
                    plays = index % 4,
                    listenedMs = (index * 50_000).toLong(),
                    liked = index % 3 == 0,
                    lastPlayedAt = now - index * dayMs,
                )
            }
        val artists = (0..2).map { artist("det_artist_$it", plays = 4, lastPlayedAt = now - dayMs) }
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)

        val first =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)
        val second =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)

        assertEquals(first.map { it.stats.songId }, second.map { it.stats.songId })
        assertEquals(first.map { it.score }, second.map { it.score })
    }

    @Test
    fun recentlyRecommendedItemsArePenalizedNotHidden() {
        val songs =
            listOf(
                song("recently_rec", plays = 3, listenedMs = 500_000L, lastPlayedAt = now - dayMs),
                song("fresh", plays = 3, listenedMs = 500_000L, lastPlayedAt = now - dayMs),
            )
        val artists = listOf(artist("ar_recently_rec", plays = 3), artist("ar_fresh", plays = 3))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)

        val recentMap = mapOf("recently_rec" to now - dayMs)
        val penalized =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), recentMap, RecContext(), config, now)
        val clean =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)

        val penalizedScore = penalized.first { it.stats.songId == "recently_rec" }.score
        val cleanScore = clean.first { it.stats.songId == "recently_rec" }.score
        assertTrue(penalizedScore < cleanScore)
        assertTrue(penalized.any { it.stats.songId == "recently_rec" })
    }

    @Test
    fun currentSongIsNeverRecommended() {
        val songs =
            listOf(
                song("current", plays = 3, listenedMs = 500_000L, lastPlayedAt = now),
                song("other_same_artist", artistId = "ar_current", plays = 1, lastPlayedAt = now - dayMs),
            )
        val artists = listOf(artist("ar_current", plays = 4))
        val context = RecContext(currentSongId = "current", currentArtistIds = listOf("ar_current"))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, context, config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), context, config, now)
        assertTrue(ranked.none { it.stats.songId == "current" })
    }

    @Test
    fun unavailableSongsAreFiltered() {
        val available = song("available", plays = 3, listenedMs = 400_000L, lastPlayedAt = now - dayMs)
        val unavailable = available.copy(songId = "unavailable", libraryAvailable = false)
        val songs = listOf(available, unavailable)
        val artists = listOf(artist("ar_available", plays = 3))
        val candidates = RecommendationEngine.generateCandidates(songs, artists, RecContext(), config)
        val ranked =
            RecommendationEngine.rank(candidates, songs, artists, emptyList(), emptyMap(), RecContext(), config, now)
        assertTrue(ranked.none { it.stats.libraryAvailable.not() })
        assertNotEquals(0, ranked.size)
    }

    @Test
    fun discoveryFiltersMatchKeywordsAndSeeds() {
        val songs =
            listOf(
                song("kw_match", plays = 0),
                song("kw_miss", plays = 0),
                song("kw_excluded", artistId = "seeded", plays = 0),
                song("kw_seed_match", artistId = "seeded", plays = 0),
            )
        val artists = listOf(artist("seeded", plays = 0), artist("ar_kw_match", plays = 0))
        val filters =
            DiscoveryFilters(
                keywords = listOf("kw_match"),
                excludeKeywords = listOf("excluded"),
                seedArtistNames = listOf("Artist seeded"),
            )
        val candidates = RecommendationEngine.generateDiscoveryCandidates(songs, artists, filters, config)
        val ids = candidates.map { it.stats.songId }
        assertTrue(ids.contains("kw_match"))
        assertFalse(ids.contains("kw_miss"))
        assertFalse(ids.contains("kw_excluded"))
        assertTrue(ids.contains("kw_seed_match"))
    }

    @Test
    fun discoveryNovelRequirementFiltersPlayedSongs() {
        val songs = listOf(song("played", plays = 2), song("unplayed", plays = 0))
        val artists = listOf(artist("ar_played", plays = 2), artist("ar_unplayed", plays = 0))
        val candidates =
            RecommendationEngine.generateDiscoveryCandidates(
                songs,
                artists,
                DiscoveryFilters(requireNovel = true),
                config,
            )
        assertTrue(candidates.none { it.stats.playCount > 0 })
    }

    @Test
    fun recencyFactorDecays() {
        val fresh = RecommendationEngine.recencyFactor(now - dayMs, now, 21f)
        val stale = RecommendationEngine.recencyFactor(now - 180 * dayMs, now, 21f)
        assertTrue(fresh > stale)
        assertTrue(fresh in 0f..1f)
        assertEquals(0f, RecommendationEngine.recencyFactor(0L, now, 21f), 0.0001f)
    }
}
