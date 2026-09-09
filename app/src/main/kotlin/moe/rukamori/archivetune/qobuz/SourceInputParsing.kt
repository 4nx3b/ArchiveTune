package moe.rukamori.archivetune.qobuz

import org.json.JSONObject

object SourceInputParsing {
    private val SEPARATORS = Regex("[\\s,;]+")

    fun parseUrls(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        return input
            .split(SEPARATORS)
            .asSequence()
            .map { it.trim().trim('"', '\'', '<', '>', '(', ')', ',') }
            .filter { it.isNotEmpty() }
            .mapNotNull { normalizeUrl(it) }
            .filter { seen.add(it.lowercase()) }
            .toList()
    }

    private fun normalizeUrl(raw: String): String? {
        var url = raw
        if (!url.contains("://")) url = "https://$url"

        val host = url.substringAfter("://").substringBefore("/")
        if (!host.contains('.')) return null
        return url.trimEnd('/')
    }

    fun parseQobuzTokens(input: String): List<QobuzToken> {
        if (input.isBlank()) return emptyList()
        val blocks = mutableListOf<JSONObject>()
        var current: JSONObject? = null
        var pendingLabel = ""

        fun startBlock() {
            current = JSONObject()
            if (pendingLabel.isNotEmpty()) {
                current?.put("label", pendingLabel)
                pendingLabel = ""
            }
            blocks.add(current!!)
        }

        for (rawLine in input.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val token = matchField(line, "token")
            val userId = matchField(line, "user id", "user_id", "userid", "user")
            val subscription = matchField(line, "subscription", "plan")
            val appId = AppIdRegex.find(line)?.groupValues?.get(1)
            val appSecret = AppSecretRegex.find(line)?.groupValues?.get(1)

            when {
                token != null -> {
                    startBlock()
                    current?.put("token", token)
                }
                userId != null -> current?.put("userId", userId)
                subscription != null -> current?.put("subscription", subscription)

                !line.contains(FieldSeparatorRegex) && !line.contains("app_") ->
                    pendingLabel = line.take(40)
                else -> {}
            }

            if (appId != null) current?.put("appId", appId)
            if (appSecret != null) current?.put("appSecret", appSecret)
        }

        return blocks.mapNotNull { QobuzToken.fromJson(it) }
    }

    private fun matchField(
        line: String,
        vararg names: String,
    ): String? {
        val lower = line.lowercase()
        for (name in names) {
            if (!lower.startsWith(name)) continue
            val after = line.substring(name.length)
            val value = after.dropWhile { it == ' ' || it in "➠→➔⇒:=\t" }.trim()
            if (value.isNotEmpty()) return value.trim('"', '\'')
        }
        return null
    }

    private val AppIdRegex = Regex("app_id:?\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    private val AppSecretRegex = Regex("app_secret:?\\s*([a-f0-9]{16,})", RegexOption.IGNORE_CASE)
    private val FieldSeparatorRegex = Regex("[➠→➔⇒:]")
}
