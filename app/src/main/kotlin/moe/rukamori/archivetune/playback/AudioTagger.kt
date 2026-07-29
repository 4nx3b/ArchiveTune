/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Writes ID3 / Vorbis-Comment / MP4 / FLAC metadata tags onto a
 * downloaded audio file using the [jaudiotagger](https://github.com/RouHim/jaudiotagger)
 * library.
 *
 * Used by [ExportDownloadedSongsScreen][moe.rukamori.archivetune.ui.screens.settings.ExportDownloadedSongsScreen]
 * when exporting cached songs to a SAF folder — the user picks a
 * destination, the cached spans are assembled into a single temp file,
 * [tag] is called to write title / artist / album / year / track tags,
 * and the tagged file is then copied to the SAF document.
 *
 * ## Why jaudiotagger?
 *
 * jaudiotagger supports every audio container ArchiveTune can download
 * (MP3, FLAC, M4A/AAC, OGG/Opus, WAV) under a single uniform API
 * ([FieldKey] enum maps to format-specific tag keys internally). The
 * alternative — writing format-specific taggers by hand — would mean
 * ~5× the code and 5× the bug surface.
 *
 * ## Text-only by design
 *
 * We deliberately only write *text* fields (title, artist, album, year,
 * track number). jaudiotagger's artwork API (`Artwork` / `StandardImageHandler`)
 * goes through `javax.imageio.ImageIO`, which does not exist on Android.
 * Reading the existing thumbnail URL from the SongEntity and asking
 * Coil to download it to a byte array, then writing that byte array
 * via `Tag.setField(Artwork)`, would technically work but is brittle —
 * any code path that touches `BufferedImage` would crash at runtime
 * with `NoClassDefFoundError`. Skipping artwork keeps the export
 * pipeline safe; the file's audio data is unaffected.
 *
 * ## Failure isolation
 *
 * Every call is wrapped in [runCatching] — if jaudiotagger throws
 * (e.g. corrupt file, unsupported format, tag-readonly), the export
 * still succeeds with the *untagged* temp file. The audio bytes are
 * never modified by [tag]; jaudiotagger only writes the tag chunk.
 */
object AudioTagger {

    /**
     * Metadata to write. All fields are optional — blank/null fields
     * are skipped so we don't overwrite existing tags with empty strings.
     */
    data class Metadata(
        val title: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val trackTotal: Int? = null,
        val discNumber: Int? = null,
        val discTotal: Int? = null,
        val genre: String? = null,
        val composer: String? = null,
        val isrc: String? = null,
        val comment: String? = null,
    )

    /**
     * Reads the existing tag (if any) from [file] and writes the
     * non-blank fields from [metadata] onto it, then persists the
     * file in place. Returns `true` on success, `false` on any error
     * (the file is left untouched on error — jaudiotagger writes to
     * a temp file and renames, so a partial write cannot corrupt
     * the source).
     *
     * Safe to call from a background thread. Not safe to call from
     * the main thread — jaudiotagger does disk I/O.
     */
    fun tag(file: File, metadata: Metadata): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val audioFile = AudioFileIO.read(file)
            // Use the explicit Java getter call instead of the synthetic
            // `tagOrCreateAndSetDefault` property access — Kotlin 2.4
            // misinterprets `audioFile.tagOrCreateAndSetDefault()` as
            // property-access + invoke(), which yields an unresolved type
            // for `tag` and cascades into "Unresolved reference 'setField'"
            // on every field-write below. Calling the Java getter directly
            // avoids the property-access synthesis and returns Tag cleanly.
            val tag = audioFile.getTagOrCreateAndSetDefault()
            metadata.title?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.TITLE, it) }
            metadata.artist?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.ARTIST, it) }
            metadata.albumArtist?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
            metadata.album?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.ALBUM, it) }
            metadata.year?.takeIf { it > 0 }?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            metadata.trackNumber?.takeIf { it > 0 }?.let { tag.setField(FieldKey.TRACK, it.toString()) }
            metadata.trackTotal?.takeIf { it > 0 }?.let { tag.setField(FieldKey.TRACK_TOTAL, it.toString()) }
            metadata.discNumber?.takeIf { it > 0 }?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
            metadata.discTotal?.takeIf { it > 0 }?.let { tag.setField(FieldKey.DISC_TOTAL, it.toString()) }
            metadata.genre?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.GENRE, it) }
            metadata.composer?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.COMPOSER, it) }
            metadata.isrc?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.ISRC, it) }
            metadata.comment?.takeIf(String::isNotBlank)?.let { tag.setField(FieldKey.COMMENT, it) }
            audioFile.commit()
            true
        }.getOrElse { false }
    }
}
