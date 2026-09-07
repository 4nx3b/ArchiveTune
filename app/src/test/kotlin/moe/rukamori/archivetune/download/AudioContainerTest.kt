/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioContainerTest {

    private fun header(vararg bytes: Int): ByteArray =
        ByteArray(AudioContainer.PROBE_BYTES).also { buf ->
            bytes.forEachIndexed { i, b -> buf[i] = b.toByte() }
        }

    private fun headerOf(
        prefix: String,
        atOffset: Int = 0,
    ): ByteArray =
        ByteArray(AudioContainer.PROBE_BYTES).also { buf ->
            prefix.forEachIndexed { i, c -> buf[atOffset + i] = c.code.toByte() }
        }

    @Test
    fun detectsFlacOggAndWav() {
        assertEquals(AudioContainer.FLAC, AudioContainer.detect(headerOf("fLaC")))
        assertEquals(AudioContainer.OGG, AudioContainer.detect(headerOf("OggS")))

        val wav = headerOf("RIFF").also { buf -> "WAVE".forEachIndexed { i, c -> buf[8 + i] = c.code.toByte() } }
        assertEquals(AudioContainer.WAV, AudioContainer.detect(wav))
    }

    @Test
    fun detectsWebmEbmlHeader() {
        assertEquals(AudioContainer.WEBM, AudioContainer.detect(header(0x1A, 0x45, 0xDF, 0xA3)))
    }

    @Test
    fun detectsMp4ByFtypAtOffsetFour() {

        assertEquals(AudioContainer.MP4, AudioContainer.detect(headerOf("ftyp", atOffset = 4)))
    }

    @Test
    fun riffWithoutWaveIsNotWav() {

        assertNull(AudioContainer.detect(headerOf("RIFF")))
    }

    @Test
    fun detectsBareMpegFrameSync() {

        assertEquals(AudioContainer.MP3, AudioContainer.detect(header(0xFF, 0xFB)))
        assertEquals(AudioContainer.MP3, AudioContainer.detect(header(0xFF, 0xE0)))
    }

    @Test
    fun mpegSyncRequiresAllElevenBits() {

        assertNull(AudioContainer.detect(header(0xFF, 0x0F)))
    }

    @Test
    fun id3TaggedStreamFallsBackToMp3() {

        assertEquals(AudioContainer.MP3, AudioContainer.detect(headerOf("ID3")))
    }

    @Test
    fun id3SizeIsReadAsSynchsafeIntegerToFindMp4() {

        val buf = headerOf("ID3")
        buf[6] = 0
        buf[7] = 0
        buf[8] = 0
        buf[9] = 20
        "ftyp".forEachIndexed { i, c -> buf[30 + 4 + i] = c.code.toByte() }

        assertEquals(AudioContainer.MP4, AudioContainer.detect(buf))
    }

    @Test
    fun id3SizeBytesNeverUseTheHighBit() {

        val buf = headerOf("ID3")
        buf[6] = 0
        buf[7] = 0
        buf[8] = 0
        buf[9] = 0x80.toByte()
        "ftyp".forEachIndexed { i, c -> buf[10 + 4 + i] = c.code.toByte() }

        assertEquals(AudioContainer.MP4, AudioContainer.detect(buf))
    }

    @Test
    fun shortHeadersAreRejectedRatherThanGuessed() {
        assertNull(AudioContainer.detect(ByteArray(0)))
        assertNull(AudioContainer.detect("fLaC".toByteArray()))
    }

    @Test
    fun unknownBytesReturnNullSoCallersCanFallBack() {
        assertNull(AudioContainer.detect(headerOf("%PDF-1.7")))
    }

    @Test
    fun mimeFallbackMapsKnownTypes() {
        assertEquals("flac", AudioContainer.extensionForMime("audio/flac"))
        assertEquals("mp3", AudioContainer.extensionForMime("audio/mpeg"))
        assertEquals("ogg", AudioContainer.extensionForMime("audio/opus"))
        assertEquals("webm", AudioContainer.extensionForMime("audio/webm"))
        assertEquals("wav", AudioContainer.extensionForMime("audio/wav"))
    }

    @Test
    fun mimeFallbackIsCaseInsensitiveAndDefaultsToM4a() {
        assertEquals("flac", AudioContainer.extensionForMime("AUDIO/X-FLAC"))
        assertEquals("m4a", AudioContainer.extensionForMime(null))
        assertEquals("m4a", AudioContainer.extensionForMime("application/octet-stream"))
    }
}
