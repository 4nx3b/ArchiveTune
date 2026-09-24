/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import java.util.Locale

internal object MusixmatchTtml {
    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    /** Richsync only publishes per-word start offsets, so ends are synthesized. */
    private const val MIN_WORD_SECONDS = 0.15

    /**
     * Cap for the synthesized end of a line's final word: `te` often includes the
     * instrumental tail before the next line, which would otherwise stretch the last
     * word's karaoke sweep over several seconds of silence.
     */
    private const val MAX_LAST_WORD_SECONDS = 2.5

    fun richSyncToTtml(lines: List<RichSyncLine>): String {
        if (lines.isEmpty()) return ""

        val builder = StringBuilder()
        builder.append(XML_HEADER)
        builder.append('\n')
        builder.append("<tt xmlns=\"http://www.w3.org/ns/ttml\">\n")
        builder.append("  <body>\n")
        builder.append("    <div>\n")

        for (line in lines) {
            appendLine(builder, line)
        }

        builder.append("    </div>\n")
        builder.append("  </body>\n")
        builder.append("</tt>\n")
        return builder.toString()
    }

    private fun appendLine(builder: StringBuilder, line: RichSyncLine) {
        val lineStart = line.startTime
        val lineEnd = line.endTime
        if (lineEnd < lineStart) return

        val filtered = line.words.filter { it.text.isNotEmpty() }
        if (filtered.isEmpty()) {
            val safeText = escapeXml(line.text.orEmpty().ifBlank { "" })
            if (safeText.isBlank()) return
            builder.append("      <p begin=\"")
            builder.append(formatTime(lineStart))
            builder.append("\" end=\"")
            builder.append(formatTime(lineEnd))
            builder.append("\">")
            builder.append(safeText)
            builder.append("</p>\n")
            return
        }

        builder.append("      <p begin=\"")
        builder.append(formatTime(lineStart))
        builder.append("\" end=\"")
        builder.append(formatTime(lineEnd))
        builder.append("\">")
        for (i in filtered.indices) {
            val word = filtered[i]
            val wordStart = lineStart + word.offset
            // The end of a word is the start of the next one; when consecutive words
            // share an onset (common in richsync) the gap collapses to zero, which used
            // to produce instant 0%-to-100% karaoke jumps — floor it instead.
            val nextStart = if (i < filtered.lastIndex) lineStart + filtered[i + 1].offset else null
            val wordEnd: Double =
                when {
                    nextStart != null -> maxOf(nextStart, wordStart + MIN_WORD_SECONDS)
                    else -> {
                        val capped = minOf(lineEnd, wordStart + MAX_LAST_WORD_SECONDS)
                        maxOf(capped, wordStart + MIN_WORD_SECONDS)
                    }
                }
            builder.append("<span begin=\"")
            builder.append(formatTime(wordStart))
            builder.append("\" end=\"")
            builder.append(formatTime(wordEnd))
            builder.append("\">")

            builder.append(escapeXml(word.text))
            builder.append("</span>")
            if (i < filtered.lastIndex) {
                builder.append(' ')
            }
        }
        builder.append("</p>\n")
    }

    private fun formatTime(seconds: Double): String =
        String.format(Locale.US, "%.3fs", seconds.coerceAtLeast(0.0))

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
