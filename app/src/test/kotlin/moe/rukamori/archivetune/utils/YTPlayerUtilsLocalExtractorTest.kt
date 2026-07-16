/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YTPlayerUtilsLocalExtractorTest {
    @Test
    fun localHighQualityProfileStartsWithWebRemixAndKeepsLocalFallbacks() {
        val clients =
            YTPlayerUtils.buildStreamClientOrder(
                preferredStreamClient = PlayerStreamClient.ARCHIVETUNE_EXTRACTOR,
                authState = PlaybackAuthState.EMPTY,
            )

        assertEquals(YouTubeClient.WEB_REMIX, clients.first())
        assertTrue(clients.size > 1)
        assertEquals(clients.distinct(), clients)
    }
}
