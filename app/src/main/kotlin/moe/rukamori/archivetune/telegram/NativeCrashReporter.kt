/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

internal object NativeCrashReporter {
    private const val TAG = "NativeCrashReporter"
    private const val MAX_PERSISTED_CHARS = 24_000
    private const val LOGCAT_LINES = 4000

    private val INTERESTING =
        Regex(
            "(?i)(fatal|sigsegv|sigabrt|sigbus|sigill|abort|backtrace|tombstone|" +
                "debuggerd|crash_dump|tdjni|td_jni|tdlib|tdengine|dlopen|linker|" +
                "androidruntime|beginning of)",
        )

    private val FATAL_LINE = Regex("(?i)(fatal signal|abort message|fatalerror|jni fatal)")

    @Volatile
    private var capturedInThisProcess = false

    fun evidenceFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "tdlib-native"), "last-logcat.txt")

    suspend fun maybeCapture(context: Context) {
        if (capturedInThisProcess) return
        capturedInThisProcess = true
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = readLogcat()
                if (raw.isBlank()) return@runCatching

                val lines = raw.lines()
                val filtered = lines.filter { INTERESTING.containsMatchIn(it) }
                val lastFatalIndex = lines.indexOfLast { FATAL_LINE.containsMatchIn(it) }
                val fatalExcerpt =
                    if (lastFatalIndex >= 0) {
                        lines.drop(lastFatalIndex).take(40)
                    } else {
                        emptyList()
                    }

                val builder = StringBuilder()
                builder.append("[${System.currentTimeMillis()}] own-uid logcat, pid=${Process.myPid()}")
                builder.append(", interesting lines=${filtered.size}\n")
                filtered.takeLast(300).forEach { builder.appendLine(it) }
                if (fatalExcerpt.isNotEmpty()) {
                    builder.append("\n--- newest fatal line + context ---\n")
                    fatalExcerpt.forEach { builder.appendLine(it) }
                }
                evidenceFile(context).apply {
                    parentFile?.mkdirs()
                    writeText(builder.toString().take(MAX_PERSISTED_CHARS))
                }
                Timber
                    .tag(TAG)
                    .i("Persisted %d logcat lines as engine-crash evidence", filtered.size)
            }.onFailure {
                Timber.tag(TAG).w(it, "Reading logcat for engine-crash evidence failed")
            }
        }
    }

    private fun readLogcat(): String {
        val candidates = listOf("/system/bin/logcat", "logcat")
        for (binary in candidates) {
            runCatching {
                val proc =
                    ProcessBuilder(binary, "-d", "-v", "time", "-t", LOGCAT_LINES.toString())
                        .redirectErrorStream(true)
                        .start()
                val output =
                    proc.inputStream.bufferedReader().use { reader ->
                        reader.readText()
                    }
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0 && output.isNotBlank()) {
                    return output
                }
                proc.destroy()
            }
        }
        return ""
    }

    fun summarize(context: Context): String? =
        runCatching {
            val target = evidenceFile(context)
            if (!target.isFile) return@runCatching null
            val lines = target.readLines()
            lines
                .lastOrNull { FATAL_LINE.containsMatchIn(it) }
                ?: lines.lastOrNull { INTERESTING.containsMatchIn(it) && !it.startsWith("[") }
        }.getOrNull()
            ?.take(240)
            ?.let { "logcat: $it" }
}
