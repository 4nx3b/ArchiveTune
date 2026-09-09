/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMediaIdTest {
    @Test
    fun roundTripsWithUniqueId() {
        val id =
            TelegramMediaId(
                chatId = -1001234567890L,
                messageId = 52428800L,
                fileUniqueId = "4711:2",
            )
        assertEquals(id, TelegramMediaId.decode(id.encode()))
    }

    @Test
    fun roundTripsWithoutUniqueId() {
        val id = TelegramMediaId(chatId = -100987L, messageId = 12L)
        val encoded = id.encode()
        assertEquals("telegram://track/v2/-100987/12", encoded)
        assertEquals(id, TelegramMediaId.decode(encoded))
    }

    @Test
    fun encodesV2WithUniqueId() {
        val id = TelegramMediaId(chatId = -100L, messageId = 5L, fileUniqueId = "9:4")
        assertEquals("telegram://track/v2/-100/5/9:4", id.encode())
    }

    @Test
    fun decodesLegacyV1Ids() {
        // TDLib-era ids: chat + message survive, TDLib-local file ids are ignored
        val id = TelegramMediaId.decode("telegram://track/-1001234567890/52428800/4711/AgADBQADr6cxGw")
        assertEquals(-1001234567890L, id?.chatId)
        assertEquals(52428800L, id?.messageId)

        val minimal = TelegramMediaId.decode("telegram://track/-100987/12/3")
        assertEquals(-100987L, minimal?.chatId)
        assertEquals(12L, minimal?.messageId)
    }

    @Test
    fun recognisesTelegramMediaIds() {
        assertTrue("telegram://track/-100987/12/3".isTelegramMediaId())
        assertTrue("telegram://track/v2/-100987/12".isTelegramMediaId())
        assertTrue(
            TelegramMediaId(-1L, 2L, "u").encode().isTelegramMediaId(),
        )
    }

    @Test
    fun rejectsForeignIds() {
        assertFalse("dQw4w9WgXcQ".isTelegramMediaId())
        assertFalse("content://media/external/audio/1".isTelegramMediaId())
        assertFalse("https://t.me/somechannel".isTelegramMediaId())
        assertFalse("telegram://track/notanumber/12/3".isTelegramMediaId())
        assertFalse("telegram://track/0/12".isTelegramMediaId())
        assertFalse("telegram://track/1/0".isTelegramMediaId())
    }

    @Test
    fun decodeRejectsMalformedIds() {
        assertNull(TelegramMediaId.decode("telegram://track/v2/1"))
        assertNull(TelegramMediaId.decode("telegram://chat/1/2/3"))
        assertNull(TelegramMediaId.decode(""))
    }
}
