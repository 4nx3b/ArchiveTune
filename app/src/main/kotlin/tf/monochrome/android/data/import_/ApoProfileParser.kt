package tf.monochrome.android.data.import_

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.max

data class ParsedEqProfile(
    val bands: List<EqBand> = emptyList(),
    val preamp: Float = 0f,
    val warnings: List<String> = emptyList(),

    val looksLikeMeasurement: Boolean = false,
) {
    val isEmpty: Boolean get() = bands.isEmpty()

    val suggestedPreamp: Float
        get() = -max(0f, bands.filter { it.enabled }.maxOfOrNull { it.gain } ?: 0f)
}

object ApoProfileParser {
    private val FILTER_LINE = Regex(
        """^\s*Filter\s*(\d*)\s*:\s*(ON|OFF)?\s*([A-Za-z]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PREAMP_LINE = Regex(
        """^\s*Preamp\s*:\s*([-+]?[\d.,]+)\s*dB""",
        RegexOption.IGNORE_CASE,
    )
    private fun keyword(name: String) = Regex(
        """\b$name\s+([-+]?[\d.,]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val FC = keyword("Fc")
    private val GAIN = keyword("Gain")
    private val Q = keyword("Q")

    private val FILTER_TOKEN = Regex("(?i)Filter\\s*\\d+\\s*:")
    private val PREAMP_TOKEN = Regex("(?i)Preamp\\s*:")

    private val PEAKING_CODES = setOf("PK", "PEQ", "EQ", "PEAKING")
    private val LOWSHELF_CODES = setOf("LS", "LSC", "LOWSHELF", "LSQ")
    private val HIGHSHELF_CODES = setOf("HS", "HSC", "HIGHSHELF", "HSQ")

    private const val MIN_FREQ = 20f
    private const val MAX_FREQ = 20_000f
    private const val MIN_Q = 0.05f
    private const val MAX_Q = 20f

    fun parse(text: String, maxBandGainDb: Float): ParsedEqProfile {
        if (text.isBlank()) {
            return ParsedEqProfile(warnings = listOf("The file is empty."))
        }
        if (looksLikeMeasurementCsv(text)) {
            return ParsedEqProfile(looksLikeMeasurement = true)
        }
        val prepared = ensureLineStructure(text)

        val warnings = mutableListOf<String>()
        val bands = mutableListOf<EqBand>()
        var preamp = 0f
        var sawPreamp = false
        val unsupported = mutableListOf<String>()
        var clampedGain = 0
        var clampedFreq = 0

        for (raw in prepared.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue

            val preampMatch = PREAMP_LINE.find(line)
            if (preampMatch != null) {
                preamp = preampMatch.groupValues[1].toFloatOrNull2() ?: 0f
                sawPreamp = true
                continue
            }

            val header = FILTER_LINE.find(line)
            if (header != null) {
                parseFilterLine(header, line, bands.size)?.let { outcome ->
                    when (outcome) {
                        is FilterOutcome.Band -> {
                            var b = outcome.band
                            if (b.freq !in MIN_FREQ..MAX_FREQ) {
                                clampedFreq++
                                b = b.copy(freq = b.freq.coerceIn(MIN_FREQ, MAX_FREQ))
                            }
                            if (kotlin.math.abs(b.gain) > maxBandGainDb) {
                                clampedGain++
                                b = b.copy(gain = b.gain.coerceIn(-maxBandGainDb, maxBandGainDb))
                            }
                            bands += b.copy(q = b.q.coerceIn(MIN_Q, MAX_Q))
                        }
                        is FilterOutcome.Unsupported -> unsupported += outcome.label
                        FilterOutcome.Placeholder -> Unit
                    }
                }
                continue
            }

            parseCsvBand(line, bands.size)?.let { csv ->

                var b = csv
                if (kotlin.math.abs(b.gain) > maxBandGainDb) {
                    clampedGain++
                    b = b.copy(gain = b.gain.coerceIn(-maxBandGainDb, maxBandGainDb))
                }
                bands += b
            }
        }

        if (bands.isEmpty()) {
            return ParsedEqProfile(
                warnings = listOf(
                    "No filters found. Expected EqualizerAPO lines like:\n" +
                        "Filter 1: ON PK Fc 105 Hz Gain -2.9 dB Q 0.7"
                )
            )
        }

        if (unsupported.isNotEmpty()) {
            warnings += "Skipped ${unsupported.size} unsupported " +
                (if (unsupported.size == 1) "filter" else "filters") +
                " (${unsupported.joinToString(", ")}) — only peaking and shelf filters apply here."
        }
        if (clampedGain > 0) {
            warnings += "$clampedGain band gain(s) limited to ±${maxBandGainDb.toInt()} dB."
        }
        if (clampedFreq > 0) {
            warnings += "$clampedFreq frequency(ies) clamped to 20 Hz–20 kHz."
        }
        if (!sawPreamp) {
            warnings += "No preamp line — suggested ${"%.1f".format(preampFor(bands))} dB " +
                "to keep the boost from clipping."
            preamp = preampFor(bands)
        }

        return ParsedEqProfile(bands = bands, preamp = preamp, warnings = warnings)
    }

    private fun ensureLineStructure(text: String): String {
        val filters = FILTER_TOKEN.findAll(text).count()
        if (filters <= 1) return text
        val lines = text.count { it == '\n' } + 1
        if (lines >= filters) return text
        var rebuilt = FILTER_TOKEN.replace(text) { "\n" + it.value }
        rebuilt = PREAMP_TOKEN.replace(rebuilt) { "\n" + it.value }
        return rebuilt
    }

    private fun preampFor(bands: List<EqBand>): Float =
        -max(0f, bands.filter { it.enabled }.maxOfOrNull { it.gain } ?: 0f)

    private sealed interface FilterOutcome {
        data class Band(val band: EqBand) : FilterOutcome
        data class Unsupported(val label: String) : FilterOutcome

        data object Placeholder : FilterOutcome
    }

    private fun parseFilterLine(
        header: MatchResult,
        line: String,
        nextId: Int,
    ): FilterOutcome? {
        val enabled = !header.groupValues[2].equals("OFF", ignoreCase = true)
        val code = header.groupValues[3].uppercase()
        if (code == "NONE") return FilterOutcome.Placeholder

        val type = when (code) {
            in PEAKING_CODES -> FilterType.PEAKING
            in LOWSHELF_CODES -> FilterType.LOWSHELF
            in HIGHSHELF_CODES -> FilterType.HIGHSHELF
            else -> return FilterOutcome.Unsupported(code)
        }

        val freq = FC.find(line)?.groupValues?.get(1)?.toFloatOrNull2() ?: return null
        val gain = GAIN.find(line)?.groupValues?.get(1)?.toFloatOrNull2() ?: 0f

        val defaultQ = if (type == FilterType.PEAKING) 1f else 0.707f

        val q = Q.find(line)?.groupValues?.get(1)?.toFloatOrNull2() ?: defaultQ

        return FilterOutcome.Band(
            EqBand(id = nextId, type = type, freq = freq, gain = gain, q = q, enabled = enabled)
        )
    }

    private fun parseCsvBand(line: String, nextId: Int): EqBand? {
        if (!line.contains(',')) return null
        val cells = line.split(',').map { it.trim() }
        if (cells.size < 4) return null
        val type = when (cells[0].uppercase()) {
            in PEAKING_CODES -> FilterType.PEAKING
            in LOWSHELF_CODES -> FilterType.LOWSHELF
            in HIGHSHELF_CODES -> FilterType.HIGHSHELF
            else -> return null
        }
        val freq = cells[1].toFloatOrNull2() ?: return null
        val gain = cells[2].toFloatOrNull2() ?: return null
        val q = cells[3].toFloatOrNull2() ?: 1f
        return EqBand(
            id = nextId,
            type = type,
            freq = freq.coerceIn(MIN_FREQ, MAX_FREQ),
            gain = gain,
            q = q.coerceIn(MIN_Q, MAX_Q),
        )
    }

    private fun looksLikeMeasurementCsv(text: String): Boolean {
        val head = text.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase() ?: return false
        if (!head.contains(',')) return false
        return head.contains("frequency") &&
            (head.contains("raw") || head.contains("target") || head.contains("smoothed"))
    }

    private fun String.toFloatOrNull2(): Float? =
        replace(',', '.').trim().toFloatOrNull()
}
