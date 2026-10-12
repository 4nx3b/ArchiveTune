/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2

object RecommendationEngine {
    data class Candidate(
        val stats: RecSongStats,
        val reason: RecReason,
        val reasonSeed: String?,
    )

    fun generateCandidates(
        songs: List<RecSongStats>,
        artists: List<RecArtistStats>,
        context: RecContext,
        config: RecEngineConfig,
    ): List<Candidate> {
        if (songs.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val artistStatsById = artists.associateBy { it.artistId }
        val artistStrength = HashMap<String, Float>()
        artists.forEach { artist ->
            val recency = recencyFactor(artist.lastPlayedAtMs, now, config.recencyHalfLifeDays)
            val strength =
                (log2(artist.playCount.coerceAtLeast(1).toFloat() + 1f) + ln(1f + artist.likedSongCount.toFloat())) *
                    (0.55f + 0.45f * recency)
            artistStrength[artist.artistId] = strength
        }

        val contextArtistSet = context.currentArtistIds.toSet()
        val playedSongs = songs.filter { it.playCount > 0 }

        val candidates = ArrayList<Candidate>(config.candidateLimit)

        val topArtists = artistStrength.entries
            .sortedByDescending { it.value }
            .take(12)
            .map { it.key }

        topArtists.forEach { artistId ->
            val seedName = artists.firstOrNull { it.artistId == artistId }?.artistName ?: artistId
            val novelReason =
                if ((artistStatsById[artistId]?.likedSongCount ?: 0) > 0) {
                    RecReason.NOVEL_FROM_LIKED_ARTIST
                } else {
                    RecReason.TOP_ARTIST
                }
            songs.filter { artistId in it.artistIds && it.playCount == 0 && it.libraryAvailable }
                .forEach { song ->
                    candidates.add(
                        Candidate(
                            stats = song,
                            reason = novelReason,
                            reasonSeed = seedName,
                        ),
                    )
                }
        }

        songs.filter { it.liked && it.playCount in 1..3 }.forEach { song ->
            song.artistIds.forEach { artistId ->
                candidates.add(
                    Candidate(
                        stats = song,
                        reason = RecReason.FORGOTTEN_FAVORITE,
                        reasonSeed = artistId,
                    ),
                )
            }
        }

        if (contextArtistSet.isNotEmpty()) {
            songs.filter {
                it.libraryAvailable && it.songId != context.currentSongId && it.artistIds.any { a -> a in contextArtistSet }
            }.forEach { song ->
                candidates.add(
                    Candidate(
                        stats = song,
                        reason = RecReason.SAME_ARTIST_AS_CONTEXT,
                        reasonSeed = context.currentSongId,
                    ),
                )
            }
        }

        context.currentAlbumId?.let { albumId ->
            songs.filter { it.libraryAvailable && it.albumId == albumId && it.songId != context.currentSongId }
                .forEach { song ->
                    candidates.add(
                        Candidate(
                            stats = song,
                            reason = RecReason.SAME_ALBUM_AS_CONTEXT,
                            reasonSeed = albumId,
                        ),
                    )
                }
        }

        val keepListeningArtists = artists
            .filter { it.totalListenedMs > 0L || it.playCount > 0 }
            .sortedByDescending { it.totalListenedMs }
            .take(10)
            .map { it.artistId }
        songs.filter {
            it.libraryAvailable && it.artistIds.any { a -> a in keepListeningArtists } && it.playCount > 0
        }.forEach { song ->
            candidates.add(
                Candidate(
                    stats = song,
                    reason = RecReason.KEEP_LISTENING_ARTIST,
                    reasonSeed = null,
                ),
            )
        }

        val unseen = songs.filter { it.playCount == 0 && it.libraryAvailable }
        unseen.asSequence()
            .sortedBy { stableHash(it.songId) }
            .take((config.candidateLimit - candidates.size).coerceAtLeast(0))
            .forEach { song ->
                candidates.add(
                    Candidate(
                        stats = song,
                        reason = RecReason.DISCOVERY_KEYWORD,
                        reasonSeed = null,
                    ),
                )
            }

        return dedupeCandidates(candidates, config.candidateLimit)
    }

    fun generateDiscoveryCandidates(
        songs: List<RecSongStats>,
        artists: List<RecArtistStats>,
        filters: DiscoveryFilters,
        config: RecEngineConfig,
    ): List<Candidate> {
        if (songs.isEmpty()) return emptyList()
        val artistNameToId = HashMap<String, String>()
        artists.forEach { artistNameToId[normalizeKey(it.artistName)] = it.artistId }

        val seedArtistIds = filters.seedArtistNames.mapNotNull { seed ->
            artistNameToId[normalizeKey(seed)]
        }.toSet()

        val likedArtistIds = artists.filter { it.likedSongCount > 0 }.map { it.artistId }.toSet()

        val matched = songs.filter { song ->
            if (!song.libraryAvailable) return@filter false
            if (song.songId == context.currentSongId) return@filter false
            if (filters.requireNovel && song.playCount > 0) return@filter false
            if (filters.requireLikedArtistSeed && song.artistIds.none { it in likedArtistIds }) return@filter false

            val haystack = buildString {
                append(song.title)
                song.artistNames.forEach {
                    append(' ')
                    append(it)
                }
                song.albumTitle?.let {
                    append(' ')
                    append(it)
                }
            }
            val normalizedHaystack = normalizeKey(haystack)
            if (filters.excludeKeywords.any { keyword -> normalizedHaystack.contains(normalizeKey(keyword)) }) {
                return@filter false
            }
            val matchesSeedArtists = seedArtistIds.isEmpty() || song.artistIds.any { it in seedArtistIds }
            val matchesKeywords =
                filters.keywords.isEmpty() ||
                    filters.keywords.all { keyword -> normalizedHaystack.contains(normalizeKey(keyword)) }
            if (!matchesSeedArtists && !matchesKeywords) return@filter false
            true
        }

        return matched.take(config.candidateLimit).map { song ->
            Candidate(
                stats = song,
                reason = RecReason.DISCOVERY_KEYWORD,
                reasonSeed = filters.seedArtistNames.firstOrNull(),
            )
        }
    }

    fun rank(
        candidates: List<Candidate>,
        songs: List<RecSongStats>,
        artists: List<RecArtistStats>,
        feedback: List<RecFeedback>,
        recentlyRecommended: Map<String, Long>,
        context: RecContext,
        config: RecEngineConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RankedRecommendation> {
        if (candidates.isEmpty()) return emptyList()

        val excludedSongs = HashSet<String>()
        val excludedArtists = HashSet<String>()
        val dislikedSongs = HashSet<String>()
        val dismissedSongs = HashMap<String, Long>()
        feedback.forEach { fb ->
            when (fb.kind) {
                RecFeedbackKind.EXCLUDE_TRACK -> fb.targetSongId?.let { excludedSongs.add(it) }
                RecFeedbackKind.EXCLUDE_ARTIST -> fb.targetArtistId?.let { excludedArtists.add(it) }
                RecFeedbackKind.DISLIKE -> fb.targetSongId?.let { dislikedSongs.add(it) }
                RecFeedbackKind.DISMISS -> fb.targetSongId?.let { dismissedSongs[it] = fb.createdAtMs }
                else -> Unit
            }
        }

        val artistStrength = HashMap<String, Float>()
        artists.forEach { artist ->
            val recency = recencyFactor(artist.lastPlayedAtMs, nowMs, config.recencyHalfLifeDays)
            artistStrength[artist.artistId] =
                (log2(artist.playCount.coerceAtLeast(1).toFloat() + 1f) + ln(1f + artist.likedSongCount.toFloat())) *
                    (0.55f + 0.45f * recency)
        }

        val moreLikeSeeds = HashSet<String>()
        val moreLikeArtists = HashSet<String>()
        feedback.forEach { fb ->
            if (fb.kind == RecFeedbackKind.MORE_LIKE) {
                fb.targetSongId?.let { moreLikeSeeds.add(it) }
                fb.targetArtistId?.let { moreLikeArtists.add(it) }
            }
        }
        val moreLikeSongsById = songs.filter { it.songId in moreLikeSeeds }.associateBy { it.songId }
        moreLikeSongsById.values.forEach { seed ->
            seed.artistIds.forEach { moreLikeArtists.add(it) }
            seed.albumId?.let { albumId ->
                songs.filter { it.albumId == albumId }.forEach { moreLikeArtists.addAll(it.artistIds) }
            }
        }

        val scored = candidates.mapNotNull { candidate ->
            val song = candidate.stats
            if (!song.libraryAvailable) return@mapNotNull null
            if (song.songId == context.currentSongId) return@mapNotNull null
            if (song.songId in excludedSongs || song.songId in dislikedSongs) return@mapNotNull null
            if (song.artistIds.any { it in excludedArtists }) return@mapNotNull null
            dismissedSongs[song.songId]?.let { dismissedAt ->
                if (nowMs - dismissedAt < config.dismissedSuppressMs) return@mapNotNull null
            }

            val songArtistStrength = song.artistIds.maxOfOrNull { artistStrength[it] ?: 0f } ?: 0f
            val similarity =
                when {
                    song.artistIds.any { it in moreLikeArtists } -> 1.15f
                    context.currentArtistIds.isNotEmpty() &&
                        song.artistIds.any { it in context.currentArtistIds } -> 1f
                    context.currentAlbumId != null && song.albumId == context.currentAlbumId -> 0.95f
                    else -> songArtistStrength / (artistStrength.values.maxOrNull()?.plus(1f) ?: 1f)
                }

            val listenEvidence = song.playCount >= config.minListensForPreference || song.liked
            val completion = if (song.playCount > 0) song.avgCompletion.coerceIn(0f, 1f) else 0.5f
            val earlySkipPenalty = if (song.playCount > 0) {
                1f - 0.35f * (song.earlySkipCount.toFloat() / (song.playCount + song.earlySkipCount))
            } else {
                1f
            }
            val preferenceStrength =
                (
                    log2(song.playCount.coerceAtLeast(if (song.liked) 1 else 0).toFloat() + 1f) +
                        ln(1f + song.totalListenedMs / 60_000f) +
                        ln(1f + song.playlistAddCount.toFloat())
                ).let { if (listenEvidence) it * completion * earlySkipPenalty else 0f }

            val recency = recencyFactor(song.lastPlayedAtMs, nowMs, config.recencyHalfLifeDays)

            val contextBoost =
                when {
                    song.artistIds.any { it in context.currentArtistIds } -> 0.6f
                    song.albumId != null && song.albumId == context.currentAlbumId -> 0.5f
                    else -> 0f
                }

            val novelty = if (song.playCount == 0) {
                1f
            } else {
                (1f / (1f + song.playCount)).coerceIn(0.05f, 0.5f)
            }

            val repetition =
                recentlyRecommended[song.songId]?.let { recommendedAt ->
                    if (nowMs - recommendedAt < config.recentlyRecommendedPenaltyWindowMs) {
                        config.recentlyRecommendedPenalty
                    } else {
                        1f
                    }
                } ?: 1f

            val base =
                preferenceStrength * (1f - 0.35f * config.recencyWeight) +
                    similarity * 2.2f +
                    recency * 3.2f * config.recencyWeight +
                    novelty * 1.1f * (1f - 0.5f * config.recencyWeight) +
                    (if (song.liked) 0.8f else 0f) +
                    contextBoost
            val score = base * repetition

            if (score <= -5f) return@mapNotNull null

            RankedRecommendation(
                stats = song,
                score = score,
                reason = candidate.reason,
                reasonSeed = candidate.reasonSeed,
            )
        }

        val byId = LinkedHashMap<String, RankedRecommendation>()
        scored.forEach { item ->
            val existing = byId[item.stats.songId]
            if (existing == null || item.score > existing.score) {
                byId[item.stats.songId] = item
            }
        }

        return applyDiversity(
            byId.values
                .sortedWith(
                    compareByDescending<RankedRecommendation> { it.score }
                        .thenBy { stableHash(it.stats.songId) },
                ),
            config,
        )
    }

    fun isColdStart(
        songs: List<RecSongStats>,
        artists: List<RecArtistStats>,
        config: RecEngineConfig,
    ): Boolean {
        val meaningfulListens = songs.count { it.playCount >= config.minListensForPreference }
        val likedCount = songs.count { it.liked }
        return meaningfulListens < 3 && likedCount < 3 && artists.sumOf { it.playCount } < 5
    }

    private fun applyDiversity(
        ranked: List<RankedRecommendation>,
        config: RecEngineConfig,
    ): List<RankedRecommendation> {
        val result = ArrayList<RankedRecommendation>(ranked.size.coerceAtMost(config.outputLimit * 3))
        val perArtistCount = HashMap<String, Int>()
        val overflow = ArrayList<RankedRecommendation>()

        ranked.forEach { item ->
            val primaryArtist = item.stats.artistIds.firstOrNull() ?: ""
            val count = perArtistCount[primaryArtist] ?: 0
            if (count < config.diversityArtistCap) {
                perArtistCount[primaryArtist] = count + 1
                result.add(item)
            } else {
                overflow.add(item)
            }
        }

        var index = 0
        while (result.size < ranked.size && overflow.isNotEmpty() && index < overflow.size) {
            result.add(overflow[index])
            index++
        }

        return result.take(config.outputLimit * 2)
    }

    private fun dedupeCandidates(
        candidates: List<Candidate>,
        limit: Int,
    ): List<Candidate> {
        val byId = LinkedHashMap<String, Candidate>()
        candidates.forEach { candidate ->
            byId[candidate.stats.songId] = candidate
        }
        return byId.values.take(limit)
    }

    internal fun recencyFactor(
        lastPlayedAtMs: Long,
        nowMs: Long,
        halfLifeDays: Float,
    ): Float {
        if (lastPlayedAtMs <= 0L) return 0f
        val ageDays = ((nowMs - lastPlayedAtMs).coerceAtLeast(0L)) / 86_400_000f
        if (!ageDays.isFinite()) return 0f
        val halvings = ageDays / halfLifeDays.coerceAtLeast(0.5f)
        return exp(-halvings * 0.6931472f).coerceIn(0f, 1f)
    }

    internal fun stableHash(id: String): Int {
        var hash = 0x811c9dc5.toInt()
        id.forEach { char ->
            hash = hash xor char.code
            hash = hash * 0x01000193
        }
        return hash and 0x7fffffff
    }

    private fun normalizeKey(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()
}
