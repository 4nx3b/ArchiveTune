/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SettingsAnchorRequestTest {
    private var now = 1_000L

    @Before
    fun setUp() {
        SettingsAnchorRequest.reset()
        SettingsAnchorRequest.elapsedMs = { now }
    }

    @After
    fun tearDown() {
        SettingsAnchorRequest.reset()

    }

    @Test
    fun `returns the anchor requested for that screen`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)

        assertEquals(
            SettingsAnchors.CROSSFADE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
    }

    @Test
    fun `returns null for a screen the request was not aimed at`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)

        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE))
    }

    @Test
    fun `a request aimed elsewhere is left intact for its own screen`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.STORAGE, SettingsAnchors.SMART_TRIMMER)

        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.APPEARANCE))
        assertEquals(
            SettingsAnchors.SMART_TRIMMER,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE),
        )
    }

    @Test
    fun `survives repeated reads inside the claim window`() {

        SettingsAnchorRequest.request(SettingsAnchorScreens.STORAGE, SettingsAnchors.EXPORT_DOWNLOADS)

        assertEquals(
            SettingsAnchors.EXPORT_DOWNLOADS,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE),
        )
        now += 200
        assertEquals(
            SettingsAnchors.EXPORT_DOWNLOADS,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.STORAGE),
        )
    }

    @Test
    fun `expires once the claim window has passed`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.GAPLESS)

        assertEquals(
            SettingsAnchors.GAPLESS,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )

        now += 60_000
        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER))
    }

    @Test
    fun `expiry is measured from the first claim not from the request`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.APPEARANCE, SettingsAnchors.DARK_THEME)

        now += 10_000
        assertEquals(
            SettingsAnchors.DARK_THEME,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.APPEARANCE),
        )
        now += 200
        assertEquals(
            SettingsAnchors.DARK_THEME,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.APPEARANCE),
        )
    }

    @Test
    fun `a newer request replaces an unclaimed older one`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.SKIP_SILENCE)

        assertEquals(
            SettingsAnchors.SKIP_SILENCE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
    }

    @Test
    fun `re-requesting restarts the claim window`() {
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)
        assertEquals(
            SettingsAnchors.CROSSFADE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )

        now += 60_000
        SettingsAnchorRequest.request(SettingsAnchorScreens.PLAYER, SettingsAnchors.CROSSFADE)
        assertEquals(
            SettingsAnchors.CROSSFADE,
            SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER),
        )
    }

    @Test
    fun `consume with no pending request returns null`() {
        assertNull(SettingsAnchorRequest.consume(SettingsAnchorScreens.PLAYER))
    }

    @Test
    fun `anchor ids are unique`() {
        val ids =
            listOf(
                SettingsAnchors.CROSSFADE,
                SettingsAnchors.GAPLESS,
                SettingsAnchors.SKIP_SILENCE,
                SettingsAnchors.AUDIO_NORMALIZATION,
                SettingsAnchors.PERSISTENT_QUEUE,
                SettingsAnchors.EXTERNAL_DOWNLOADER,
                SettingsAnchors.DYNAMIC_THEME,
                SettingsAnchors.DARK_THEME,
                SettingsAnchors.PURE_BLACK,
                SettingsAnchors.APP_ICON,
                SettingsAnchors.FONT,
                SettingsAnchors.HIGH_REFRESH_RATE,
                SettingsAnchors.EXPORT_DOWNLOADS,
                SettingsAnchors.CLEAR_DOWNLOADS,
                SettingsAnchors.SONG_CACHE_SIZE,
                SettingsAnchors.CLEAR_SONG_CACHE,
                SettingsAnchors.IMAGE_CACHE_SIZE,
                SettingsAnchors.SMART_TRIMMER,
            )

        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `anchor screen routes match the navigation graph`() {

        assertEquals("settings/player", SettingsAnchorScreens.PLAYER)
        assertEquals("settings/appearance", SettingsAnchorScreens.APPEARANCE)
        assertEquals("settings/storage", SettingsAnchorScreens.STORAGE)
    }
}
