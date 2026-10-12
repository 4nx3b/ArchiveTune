/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.ai.AiTextService
import moe.rukamori.archivetune.constants.AiProvider
import org.json.JSONObject

class AiDiscoveryParser
    @Inject
    constructor() {
        suspend fun parse(
            query: String,
            aiConfig: AiServiceConfig?,
        ): DiscoveryFilters =
            withContext(Dispatchers.Default) {
                val trimmed = query.trim()
                if (trimmed.isEmpty()) return@withContext DiscoveryFilters()

                val aiResult =
                    aiConfig?.takeIf { it.canCallApi }?.let { config ->
                        runCatching {
                            val raw =
                                AiTextService.complete(
                                    config = config,
                                    systemPrompt = SYSTEM_PROMPT,
                                    userPrompt = trimmed,
                                    temperature = 0.1,
                                    maxTokens = 400,
                                )
                            parseAiResponse(raw)
                        }.getOrNull()
                    }

                aiResult ?: localFallback(trimmed)
            }

        private fun parseAiResponse(raw: String): DiscoveryFilters? {
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null
            val json = runCatching { JSONObject(raw.substring(jsonStart, jsonEnd + 1)) }.getOrNull() ?: return null

            val keywords = json.optJSONArray("keywords")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val seeds = json.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val exclude = json.optJSONArray("exclude")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val novel = json.optBoolean("novel", false)
            val likedSeed = json.optBoolean("fromLikedArtists", false)

            if (keywords.isEmpty() && seeds.isEmpty() && !novel && !likedSeed) return null
            return DiscoveryFilters(
                keywords = keywords.take(8),
                seedArtistNames = seeds.take(6),
                excludeKeywords = exclude.take(8),
                requireNovel = novel,
                requireLikedArtistSeed = likedSeed,
            )
        }

        private fun localFallback(query: String): DiscoveryFilters {
            val tokens =
                query
                    .lowercase()
                    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                    .split(Regex("\\s+"))
                    .filter { it.length > 2 }
                    .filterNot { STOP_WORDS.contains(it) }
            val keywords = tokens.take(6)
            val novel = query.lowercase().contains(Regex("\\bnew\\b|never heard|something i haven't"))
            val fromLiked = query.lowercase().contains(Regex("like my|similar to what i|my taste"))
            return DiscoveryFilters(
                keywords = keywords,
                seedArtistNames = emptyList(),
                excludeKeywords = emptyList(),
                requireNovel = novel,
                requireLikedArtistSeed = fromLiked,
            )
        }

        private companion object {
            val STOP_WORDS =
                setOf(
                    "the", "and", "for", "with", "songs", "song", "music", "track", "tracks",
                    "some", "want", "like", "give", "play", "find", "listen", "listening",
                    "recommend", "recommendations", "please", "that", "this", "from",
                )

            const val SYSTEM_PROMPT =
                "You convert a music discovery request into a JSON filter object for a local music library search. " +
                    "The library only knows song titles, artist names and album titles. " +
                    "Respond with ONLY a JSON object, no prose, with these optional fields: " +
                    "\"keywords\": array of 1-6 short lowercase words that must appear in the song, artist or album title, " +
                    "\"artists\": array of 0-6 artist names to seed from, " +
                    "\"exclude\": array of 0-6 words that must NOT appear, " +
                    "\"novel\": boolean, true when the user wants songs they have not played before, " +
                    "\"fromLikedArtists\": boolean, true when the user wants music from artists they already like. " +
                    "Only include fields you are confident about. Never invent library contents."
        }
    }
