/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhanced-LRC word extraction must keep the inter-word gap on the word text:
 * verbatim word renderers (LyricsV2 / LyricsEnhanced karaoke) lay each word out
 * as-is, so a trimmed edge gap collapsed "Hello world" into "Helloworld" for
 * every YouLyPlus song once the v2 path became the only provider path.
 */
class EnhancedLrcWordSpacingTest {
    @Test
    fun `trailing separator space is kept on word text`() {
        val lrc = "[00:12.000]<00:12.000>Hello <00:12.500>world"
        val entries = LyricsUtils.parseLyrics(lrc)
        assertEquals(1, entries.size)
        val words = entries.first().words
        assertEquals(2, words?.size)
        assertEquals("Hello ", words!![0].text)
        assertEquals("world", words[1].text)
        assertEquals("Hello world", words.joinToString("") { it.text })
    }

    @Test
    fun `compact enhanced lrc without gaps stays compact`() {
        val lrc = "[00:12.000]<00:12.000>Hello<00:12.500>world"
        val entries = LyricsUtils.parseLyrics(lrc)
        val words = entries.first().words
        assertEquals("Helloworld", words?.joinToString("") { it.text })
    }

    @Test
    fun `double space collapses to a single gap`() {
        val lrc = "[00:12.000]<00:12.000>Hello  <00:12.500>world"
        val entries = LyricsUtils.parseLyrics(lrc)
        val words = entries.first().words
        assertEquals("Hello world", words?.joinToString("") { it.text })
    }

    @Test
    fun `word timings are seconds and monotonic`() {
        val lrc = "[00:12.000]<00:12.000>Hello <00:12.500>world"
        val words = LyricsUtils.parseLyrics(lrc).first().words!!
        assertEquals(12.0, words[0].startTime, 0.001)
        assertEquals(12.5, words[1].startTime, 0.001)
        assertTrue(words[1].endTime > words[1].startTime)
    }

    @Test
    fun `plain line synced lrc keeps null words`() {
        val lrc = "[00:12.000]Hello world"
        val entries = LyricsUtils.parseLyrics(lrc)
        assertEquals("Hello world", entries.first().text)
        assertNull(entries.first().words)
    }

    @Test
    fun `line text is stripped of word stamps and keeps spaces`() {
        val lrc = "[00:12.000]<00:12.000>Hello <00:12.500>world"
        assertEquals("Hello world", LyricsUtils.parseLyrics(lrc).first().text)
    }

    @Test
    fun `youlyplus style line with many words keeps all gaps`() {
        val lrc =
            "[00:12.000]<00:12.000>I'm <00:12.200>in <00:12.400>love <00:12.600>with <00:12.800>the " +
                "<00:13.000>shape <00:13.200>of <00:13.400>you"
        val words = LyricsUtils.parseLyrics(lrc).first().words!!
        assertEquals(
            "I'm in love with the shape of you",
            words.joinToString("") { it.text },
        )
    }
}

class YrcWordTokenParsingTest {
    @Test
    fun `yrc word tokens parse with true durations`() {
        val lrc = "[00:27.395]I (27395,154)been (27549,191)call(28077,883)"
        val entries = LyricsUtils.parseLyrics(lrc)
        assertEquals(1, entries.size)
        val words = entries.first().words!!
        assertEquals(3, words.size)
        assertEquals("I ", words[0].text)
        assertEquals("been ", words[1].text)
        assertEquals("call", words[2].text)
        assertEquals(27.395, words[0].startTime, 0.001)
        assertEquals(27.549 + 0.191, words[1].endTime, 0.001)
        // The held word animates for its real 883ms, not the next-token gap.
        assertEquals(28.077 + 0.883, words[2].endTime, 0.001)
        assertEquals("I been call", words.joinToString("") { it.text })
    }

    @Test
    fun `yrc tokens are detected as word-synced`() {
        val lrc = "[00:27.395]I (27395,154)been (27549,191)call(28077,883)"
        assertTrue(LyricsUtils.hasWordSyncedLyrics(lrc))
    }

    @Test
    fun `line text is stripped of yrc markers`() {
        val lrc = "[00:27.395]I (27395,154)been (27549,191)call(28077,883)"
        assertEquals("I been call", LyricsUtils.parseLyrics(lrc).first().text)
    }

    @Test
    fun `line-final word keeps its true end when shorter than next line`() {
        val lrc =
            "[00:27.395]I (27395,154)call(28077,883)\n" +
                "[00:30.189]next (30189,200)line(30389,400)"
        val entries = LyricsUtils.parseLyrics(lrc)
        val first = entries.first()
        // 28.077+0.883 = 28.960 < next line 30.189 → no clamp.
        assertEquals(28.960, first.words!!.last().endTime, 0.001)
    }

    @Test
    fun `enhanced lrc still wins when both styles present`() {
        val lrc = "[00:12.000]<00:12.000>Hello <00:12.500>world"
        val words = LyricsUtils.parseLyrics(lrc).first().words!!
        assertEquals(2, words.size)
        assertEquals(12.0, words[0].startTime, 0.001)
    }
}
