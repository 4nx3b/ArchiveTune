/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Telegram "stripped" minithumbnail reconstruction, byte-compatible with TDLib's
 * get_minithumbnail_object (td/telegram/PhotoSize.cpp): the server's inline
 * thumbnail is a JPEG body with the standard tables removed. The first byte is
 * a format tag (0x01), followed by height and width, then the raw JPEG body.
 * Reconstruct: fixed header (623 bytes) with the width/height patched in at
 * fixed offsets + the body + a JPEG EOI footer, producing a decodable JPEG.
 *
 * Used for channel photos and album covers of Telegram tracks.
 */

package moe.rukamori.archivetune.telegram

import android.util.Base64

internal object TgStrippedJpeg {
    private val HEADER: ByteArray =
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDACgcHiMeGSgjISMtKygwPGRBPDc3PHtYXUlkkYCZlo+AjIqgtObDoKrarYqMyP/L2u71////m8H////6/+b9//j/2wBDASstLTw1PHZBQXb4pYyl+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj4+Pj/wAARCAAAAAADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwA=",
            Base64.DEFAULT,
        )

    private val FOOTER = byteArrayOf(0xff.toByte(), 0xd9.toByte())

    fun isStripped(bytes: ByteArray): Boolean = bytes.size >= 3 && bytes[0] == 0x01.toByte()

    /** Reconstruct a displayable JPEG, or null when the input is not a stripped thumbnail. */
    fun reconstruct(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.size < 3 || bytes[0] != 0x01.toByte()) return null
        // header[0..163] (164) + height + header[165] + width + header[167..] + body + EOI
        val bodySize = bytes.size - 3
        val out = ByteArray(HEADER.size + bodySize + FOOTER.size)
        System.arraycopy(HEADER, 0, out, 0, 164)
        out[164] = bytes[1]
        out[165] = HEADER[165]
        out[166] = bytes[2]
        System.arraycopy(HEADER, 167, out, 167, HEADER.size - 167)
        System.arraycopy(bytes, 3, out, HEADER.size, bodySize)
        System.arraycopy(FOOTER, 0, out, out.size - 2, 2)
        return out
    }
}
