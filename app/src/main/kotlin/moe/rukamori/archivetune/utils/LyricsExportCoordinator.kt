/*
 * ArchiveTune (2026)
 * © Rukamori — GPL-3.0 License | Contributors: see git history
 *
 * Process-scoped coordinator for the "Export lyrics" flow.
 *
 * WHY this exists: the export used to register its SAF launchers inside the
 * transient lyrics-menu composable and hold the payload in a plain `remember`.
 * Between the tap and the picker callback the system file picker keeps the
 * activity stopped — and on route changes, memory pressure or Samsung's
 * aggressive activity management the composition (or the whole process) is
 * torn down in between. The menu is gone, the launcher is disposed, the
 * remembered payload is null — while the picker has ALREADY created the
 * destination file. Result: an empty file on disk, every provider, every
 * lyrics type, with no error toast to explain it.
 *
 * This design removes every ephemeral dependency from the write path:
 *  - the payload is staged to a file in cacheDir (survives activity death,
 *    process death, composition teardown, the menu closing — everything);
 *  - the SAF launchers live at the MainActivity root (see LyricsExportHost),
 *    where they are registered for as long as the activity's composition
 *    exists — and the ActivityResultRegistry re-delivers a pending result
 *    to a re-registered launcher after process death;
 *  - the callback only needs the process, the staged file and the returned
 *    Uri — no composable state anywhere.
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import moe.rukamori.archivetune.R
import timber.log.Timber

object LyricsExportCoordinator {

    /** One pending export: where to stage it and what the picker should call it. */
    data class Pending(val fileName: String, val mime: String)

    private const val TAG = "LyricsExport"

    /** Payload staging file inside cacheDir — one export at a time by design. */
    private const val STAGED_PAYLOAD = "pending_lyrics_export.txt"

    /**
     * Requests are emitted to the MainActivity-root host, which launches the
     * SAF picker matching [Pending.mime]. No replay on purpose: a replayed
     * request would spontaneously re-open the picker after an activity
     * recreation. The buffer alone is enough — requests only originate from
     * a menu click, which can only happen while the host collector is live.
     */
    private val _requests = MutableSharedFlow<Pending>(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val requests: SharedFlow<Pending> = _requests

    /**
     * Stage the payload and ask the host to open the create-document picker.
     * Called from the lyrics menu at click time — the payload is exactly what
     * was on screen when the user tapped Export.
     */
    fun request(context: Context, payload: String, fileName: String, mime: String) {
        if (payload.isBlank()) return
        val staged = stagedFile(context)
        try {
            staged.writeText(payload, Charsets.UTF_8)
        } catch (error: IOException) {
            Timber.tag(TAG).w(error, "Could not stage lyrics payload for export")
            toast(context, R.string.export_lyrics_failed)
            return
        }
        Timber.tag(TAG).d("Lyrics export staged (%d chars, %s)", payload.length, fileName)
        _requests.tryEmit(Pending(fileName, mime))
    }

    /**
     * Writes the staged payload to the picker-provided [uri]. Called by the
     * root host's launcher callback; safe to call from any state of the UI
     * because it depends on nothing but the process and the staged file.
     */
    fun onDestinationPicked(context: Context, uri: Uri?) {
        val appContext = context.applicationContext
        if (uri == null) {
            // User backed out of the picker: no file was created, just drop
            // the staged payload.
            clearStaged(appContext)
            return
        }
        val payload = readStaged(appContext)
        if (payload.isNullOrBlank()) {
            // The staged payload is gone (process died and cache was cleared
            // between staging and the callback). Say so instead of writing an
            // empty file — the picker has already created the destination.
            Timber.tag(TAG).w("Export callback arrived with no staged payload — export was lost to a process restart")
            toast(appContext, R.string.export_lyrics_failed)
            return
        }
        val ok = try {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
            } != null
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Failed to write exported lyrics to the picked destination")
            false
        }
        clearStaged(appContext)
        toast(
            appContext,
            if (ok) R.string.export_lyrics_saved else R.string.export_lyrics_failed,
        )
    }

    /** The MIME types the host registers launchers for. */
    const val MIME_XML = "application/xml"
    const val MIME_TEXT = "text/plain"

    private fun stagedFile(context: Context): File =
        File(context.applicationContext.cacheDir, STAGED_PAYLOAD)

    private fun readStaged(context: Context): String? = try {
        val file = stagedFile(context)
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (error: Exception) {
        Timber.tag(TAG).w(error, "Could not read staged lyrics payload")
        null
    }

    private fun clearStaged(context: Context) {
        try {
            stagedFile(context).delete()
        } catch (_: Exception) {
        }
    }

    private fun toast(context: Context, @StringRes message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
