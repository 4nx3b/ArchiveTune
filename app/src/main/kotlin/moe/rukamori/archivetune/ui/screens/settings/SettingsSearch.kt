/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

internal object SettingsSearch {
    private val SEPARATOR_REGEX = Regex("[^a-z0-9]+")

    private val SYNONYMS: Map<String, List<String>> =
        mapOf(
            "mode" to listOf("theme", "style"),
            "colour" to listOf("color"),
            "colours" to listOf("color"),
            "customise" to listOf("customize"),
            "organise" to listOf("organize"),
            "night" to listOf("dark"),
            "vibration" to listOf("haptic"),
            "vibrate" to listOf("haptic"),
            "vibrations" to listOf("haptic"),
            "wallpaper" to listOf("background", "backdrop"),
            "lockscreen" to listOf("aod", "always", "screen"),
            "song" to listOf("track", "music"),
            "songs" to listOf("track", "music"),
            "pic" to listOf("thumbnail", "artwork", "image", "cover"),
            "picture" to listOf("thumbnail", "artwork", "image", "cover"),
            "art" to listOf("artwork", "thumbnail", "cover"),
            "vol" to listOf("volume"),
            "eq" to listOf("equalizer", "normalization"),
            "lang" to listOf("language"),
            "translate" to listOf("translation", "translator"),
            "pass" to listOf("password"),
            "pwd" to listOf("password"),
            "login" to listOf("account", "sign"),
            "signin" to listOf("account", "login"),
            "hires" to listOf("lossless", "flac", "quality"),
            "hifi" to listOf("lossless", "flac", "quality"),
            "size" to listOf("scale", "font"),
            "speed" to listOf("limit", "bandwidth"),
            "net" to listOf("network", "internet"),
            "wifi" to listOf("network", "internet"),
            "data" to listOf("network", "internet"),
            "notification" to listOf("notify"),
            "delete" to listOf("clear", "remove"),
            "reset" to listOf("clear", "remove"),
            "folder" to listOf("directory", "location", "path"),
            "toggle" to listOf("enable", "show"),
            "sync" to listOf("synchronize", "synchronise"),
        )

    private enum class Tier { STRICT, RELAXED }

    private class Match(
        val tier: Tier,
        val score: Int,
        val item: SearchResultItem,
    )

    private class Haystack(
        val titleTokens: List<String>,
        val titleText: String,
        val strongTokens: List<String>,
        val strongText: String,
        val squashed: String,
        val contextText: String,
    )

    fun search(
        groups: List<SettingsGroup>,
        rawQuery: String,
        routeFor: (parentKey: String, scrollKey: String) -> String?,
    ): List<SearchResultItem> {
        val queryText = normalizeText(rawQuery)
        val terms = queryText.split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val querySquashed = terms.joinToString("")

        val matches = mutableListOf<Match>()

        for (group in groups) {
            for (item in group.items) {
                val parentFields =
                    buildList {
                        add(item.title)
                        item.subtitle?.let { subtitle -> add(subtitle) }
                        addAll(item.keywords)
                    }

                val childMatches =
                    item.children.mapNotNull { child ->
                        val haystack =
                            buildHaystack(
                                titleFields = listOf(child.title),
                                strongFields = child.keywords + scrollKeyFields(child.scrollKey),
                                contextFields = parentFields,
                            )
                        evaluate(
                            haystack = haystack,
                            terms = terms,
                            queryText = queryText,
                            querySquashed = querySquashed,
                            isChild = true,
                        )?.let { (tier, score) ->
                            Match(
                                tier = tier,
                                score = score,
                                item =
                                    SearchResultItem(
                                        title = child.title,
                                        parentTitle = item.title,
                                        parentIcon = item.icon,
                                        parentKey = item.key,
                                        parentAccentColor = item.accentColor,
                                        parentRoute = routeFor(item.key, child.scrollKey),
                                        scrollKey = child.scrollKey,
                                        onClick = item.onClick,
                                        switchControl = child.switchControl,
                                    ),
                            )
                        }
                    }

                if (childMatches.isNotEmpty()) {
                    matches += childMatches
                    continue
                }

                val parentHaystack =
                    buildHaystack(
                        titleFields = listOf(item.title),
                        strongFields = item.keywords + listOfNotNull(item.subtitle),
                        contextFields = emptyList(),
                    )
                evaluate(
                    haystack = parentHaystack,
                    terms = terms,
                    queryText = queryText,
                    querySquashed = querySquashed,
                    isChild = false,
                )?.let { (tier, score) ->
                    matches +=
                        Match(
                            tier = tier,
                            score = score,
                            item =
                                SearchResultItem(
                                    title = item.title,
                                    parentTitle = item.subtitle ?: "",
                                    parentIcon = item.icon,
                                    parentKey = item.key,
                                    parentAccentColor = item.accentColor,
                                    parentRoute = null,
                                    scrollKey = null,
                                    onClick = item.onClick,
                                    switchControl = item.switchControl,
                                ),
                        )
                }
            }
        }

        val strict = matches.filter { it.tier == Tier.STRICT }
        val chosen = strict.ifEmpty { matches }
        return chosen
            .sortedByDescending { it.score }
            .map { it.item }
    }

    private fun evaluate(
        haystack: Haystack,
        terms: List<String>,
        queryText: String,
        querySquashed: String,
        isChild: Boolean,
    ): Pair<Tier, Int>? {
        var strengthSum = 0
        var matchedTerms = 0
        var bestOwnStrength = 0
        val unmatched = mutableListOf<String>()
        for (term in terms) {
            val strength = termStrength(term, haystack)
            if (strength > 0) {
                matchedTerms++
                strengthSum += strength
                if (strength > bestOwnStrength) bestOwnStrength = strength
            } else {
                unmatched += term
            }
        }
        if (matchedTerms == 0) return null

        if (unmatched.isNotEmpty() && bestOwnStrength >= 3 && haystack.contextText.isNotEmpty()) {
            for (term in unmatched) {
                if (haystack.contextText.contains(term)) {
                    matchedTerms++
                    strengthSum += 1
                }
            }
        }

        val tier =
            when {
                matchedTerms == terms.size -> Tier.STRICT

                matchedTerms * 2 >= terms.size -> Tier.RELAXED
                else -> return null
            }

        var score = if (tier == Tier.STRICT) 1_000 else 300
        score += strengthSum * 10

        when {
            haystack.titleText == queryText -> score += 400
            haystack.titleText.startsWith(queryText) -> score += 250
            haystack.titleText.contains(queryText) -> score += 180
            haystack.strongText.contains(queryText) -> score += 90
            haystack.squashed.contains(querySquashed) -> score += 30
        }

        if (haystack.contextText.isNotEmpty()) {
            val contextHits = terms.count { haystack.contextText.contains(it) }
            score += contextHits * 15
        }

        if (isChild) score += 25

        return tier to score
    }

    private fun termStrength(
        term: String,
        haystack: Haystack,
    ): Int {
        val variants = buildList {
            add(term)
            SYNONYMS[term]?.let { synonyms -> addAll(synonyms) }
        }
        var best = 0
        for (variant in variants) {
            val strength =
                when {
                    haystack.titleTokens.any { it == variant } -> 6
                    haystack.titleText.contains(variant) -> 5
                    haystack.strongTokens.any { it == variant } -> 4
                    haystack.strongText.contains(variant) -> 3
                    haystack.strongTokens.any { it.startsWith(variant) } -> 3

                    variant.length >= 3 && haystack.squashed.contains(variant) -> 2

                    variant.length >= 4 &&
                        haystack.strongTokens.any { it.length >= 3 && variant.startsWith(it) } -> 1

                    haystack.strongTokens.any { fuzzyEquals(variant, it) } -> 1
                    else -> 0
                }
            if (strength > best) best = strength
            if (best == 6) break
        }
        return best
    }

    private fun fuzzyEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length < 5 || b.length < 5) return false
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a == b) return true

        if (a.length == b.length) {
            var diffs = 0
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    diffs++
                    if (diffs > 1) return false
                }
            }
            return true
        }

        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        var shortIndex = 0
        var longIndex = 0
        var skipped = false
        while (shortIndex < shorter.length && longIndex < longer.length) {
            if (shorter[shortIndex] == longer[longIndex]) {
                shortIndex++
                longIndex++
            } else {
                if (skipped) return false
                skipped = true
                longIndex++
            }
        }
        return true
    }

    private fun buildHaystack(
        titleFields: List<String>,
        strongFields: List<String>,
        contextFields: List<String>,
    ): Haystack {
        val titleTokens = tokenizeAll(titleFields)
        val strongTokens = titleTokens + tokenizeAll(strongFields)
        return Haystack(
            titleTokens = titleTokens,
            titleText = titleTokens.joinToString(" "),
            strongTokens = strongTokens,
            strongText = strongTokens.joinToString(" "),
            squashed = strongTokens.joinToString(""),
            contextText = tokenizeAll(contextFields).joinToString(" "),
        )
    }

    private fun scrollKeyFields(scrollKey: String): List<String> =
        listOf(scrollKey, scrollKey.replace('_', ' ').replace('-', ' '))

    private fun tokenizeAll(fields: List<String>): List<String> =
        fields.flatMap { field -> normalizeText(field).split(' ') }.filter { it.isNotBlank() }

    private fun normalizeText(text: String): String =
        text
            .lowercase()
            .replace(SEPARATOR_REGEX, " ")
            .trim()
}
