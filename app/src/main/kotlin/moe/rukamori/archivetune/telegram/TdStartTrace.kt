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

internal object TdStartTrace {
    private const val MAX_FILE_BYTES = 64 * 1024

    fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, "tdlib-native"), "start-steps.log")

    fun attempt(context: Context) {
        step(context, "=== attempt", "pid=${Process.myPid()}")
    }

    fun step(context: Context, name: String, detail: String = "") {
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            if (target.isFile && target.length() > MAX_FILE_BYTES) {
                val text = target.readText()
                target.writeText(text.substring(text.length / 2).trimStart() + "\n")
            }
            target.appendText(
                "[${System.currentTimeMillis()}] $name${if (detail.isEmpty()) "" else " $detail"}\n",
            )
        }
    }

    fun tail(context: Context, lines: Int = 10): String? =
        runCatching {
            val target = file(context)
            if (!target.isFile) return@runCatching null
            target.readLines().takeLast(lines).joinToString("\n").ifBlank { null }
        }.getOrNull()
}
