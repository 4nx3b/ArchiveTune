/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun localizedTitlesRemainSearchable() {
        listOf("外観", "外观", "Внешний вид", "भाषा", "الصوت", "Paramètres").forEach { title ->
            val results = search(listOf(item(title)), title)

            assertEquals(title, listOf(title), results.map { it.title })
        }
    }

    @Test
    fun nonLatinTermsDistinguishMixedScriptTitles() {
        val results = search(listOf(item("USB 音声"), item("USB 表示")), "音声")

        assertEquals(listOf("USB 音声"), results.map { it.title })
    }

    @Test
    fun localizedChildKeepsItsNavigationTarget() {
        val parent = item("Appearance").copy(children = listOf(SettingsChild("外観", "appearance")))

        val result = search(listOf(parent), "外観").single()

        assertEquals("appearance", result.scrollKey)
        assertEquals("settings/Appearance?scrollTo=appearance", result.parentRoute)
    }

    @Test
    fun englishSynonymsCaseAndSeparatorsStillMatch() {
        val parent = item("Appearance").copy(children = listOf(SettingsChild("Color palette", "color_palette")))

        val results = search(listOf(parent), "  COLOUR---PALETTE  ")

        assertEquals(listOf("Color palette"), results.map { it.title })
    }

    @Test
    fun blankAndPunctuationOnlyQueriesHaveNoResults() {
        listOf("", "   ", "---!?", "。！？").forEach { query ->
            assertTrue(search(listOf(item("Appearance")), query).isEmpty())
        }
    }

    private fun item(title: String) =
        SettingsItem(
            key = title,
            icon = ColorPainter(Color.Black),
            title = title,
            onClick = {},
        )

    private fun search(
        items: List<SettingsItem>,
        query: String,
    ) = SettingsSearch.search(
        groups = listOf(SettingsGroup("Settings", items)),
        rawQuery = query,
        routeFor = { parent, child -> "settings/$parent?scrollTo=$child" },
    )
}
