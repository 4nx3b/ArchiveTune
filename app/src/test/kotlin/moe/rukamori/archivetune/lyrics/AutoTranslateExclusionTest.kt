/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTranslateExclusionTest {

    private val hindi = "मैं तुझको लेकर उड़ जाऊँ"

    private val japanese = "きみのなまえはぼくのなまえ"
    private val chinese = "我的心里只有你"
    private val english = "Just a regular english line"

    @Test
    fun detectsScriptsWithTheCodesThePickerUses() {
        assertEquals("HINDI", LyricsUtils.detectDominantLanguageCode(hindi))
        assertEquals("JAPANESE", LyricsUtils.detectDominantLanguageCode(japanese))
        assertEquals("CHINESE", LyricsUtils.detectDominantLanguageCode(chinese))
    }

    @Test
    fun latinLyricsHaveNoDominantScript() {
        assertNull(LyricsUtils.detectDominantLanguageCode(english))
        assertNull(LyricsUtils.detectDominantLanguageCode(""))
    }

    @Test
    fun excludedHindiIsNotAutoTranslated() {
        assertFalse(
            LyricsUtils.shouldAutoTranslate(
                lyrics = hindi,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = setOf("HINDI"),
            ),
        )
    }

    @Test
    fun hindiIsAutoTranslatedWhenNotExcluded() {

        assertTrue(
            LyricsUtils.shouldAutoTranslate(
                lyrics = hindi,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = emptySet(),
            ),
        )
    }

    @Test
    fun excludingOneLanguageDoesNotExcludeAnother() {
        assertTrue(
            LyricsUtils.shouldAutoTranslate(
                lyrics = japanese,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = setOf("HINDI"),
            ),
        )
    }

    @Test
    fun chineseMatchesEitherPickerVariant() {

        assertTrue(LyricsUtils.matchesExcludedLanguage("CHINESE", setOf("CHINESE_SIMPLIFIED")))
        assertTrue(LyricsUtils.matchesExcludedLanguage("CHINESE", setOf("CHINESE_TRADITIONAL")))
        assertFalse(
            LyricsUtils.shouldAutoTranslate(
                lyrics = chinese,
                targetLanguage = "ENGLISH",
                excludedLanguageCodes = setOf("CHINESE_TRADITIONAL"),
            ),
        )
    }

    @Test
    fun aliasesDoNotLeakAcrossFamilies() {
        assertFalse(LyricsUtils.matchesExcludedLanguage("JAPANESE", setOf("CHINESE_SIMPLIFIED")))
        assertFalse(LyricsUtils.matchesExcludedLanguage("CHINESE", setOf("JAPANESE")))
    }

    @Test
    fun comparisonIgnoresCaseAndSurroundingSpace() {
        assertTrue(LyricsUtils.matchesExcludedLanguage("HINDI", setOf(" hindi ")))
        assertTrue(LyricsUtils.matchesExcludedLanguage("hindi", setOf("HINDI")))
    }

    @Test
    fun emptyExclusionSetMatchesNothing() {
        assertFalse(LyricsUtils.matchesExcludedLanguage("HINDI", emptySet()))
    }
}
