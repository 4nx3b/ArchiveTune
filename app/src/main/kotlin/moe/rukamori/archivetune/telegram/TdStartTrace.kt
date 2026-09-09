/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Persisted step-trace for TDLight engine starts.
 *
 * Every step of an engine boot (attempt -> download -> digest -> JNI
 * preflight -> library load -> log recorder -> verbosity probe -> client
 * create -> first authorization state) is appended to
 * `filesDir/tdlib-native/start-steps.log` BEFORE the next step begins, so
 * a hard native death — which leaves no Java stack trace and no TDLib
 * fatal note — still leaves the last completed step on disk. The next
 * start surfaces the tail through the RuntimeFailed detail.
 */
internal object TdStartTrace {
    private const val MAX_FILE_BYTES = 64 * 1024

    fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, "tdlib-native"), "start-steps.log")

    /** Opens a new attempt section including the pid for logcat correlation. */
    fun attempt(context: Context) {
        step(context, "=== attempt", "pid=${Process.myPid()}")
    }

    fun step(context: Context, name: String, detail: String = "") {
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() > MAX_FILE_BYTES) {
                // Keep the second half of the trace; the interesting part of
                // a crash investigation is always the tail.
                val text = target.readText()
                target.writeText(text.substring(text.length / 2).trimStart() + "\n")
            }
            target.appendText(
                "[${System.currentTimeMillis()}] $name${if (detail.isEmpty()) "" else " $detail"}\n",
            )
        }
    }

    /** Last [lines] trace lines, for surfacing next to a start failure. */
    fun tail(context: Context, lines: Int = 10): String? =
        runCatching {
            val target = file(context)
            if (!target.isFile) return@runCatching null
            target.readLines().takeLast(lines).joinToString("\n").ifBlank { null }
        }.getOrNull()
}
