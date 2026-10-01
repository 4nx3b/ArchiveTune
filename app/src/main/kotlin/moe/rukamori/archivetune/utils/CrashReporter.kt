/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * On-device crash log capture — the answer to "it crashes silently and I
 * never get a log".
 *
 * Three layers:
 *  1. NATIVE: libarchivetune_crash installs async-signal-safe handlers for
 *     SIGSEGV/SIGBUS/SIGABRT/SIGFPE/SIGILL and writes a register +
 *     memory-map report into the crash directory at the moment of death.
 *     These crashes never produce a Java exception, so without this layer
 *     they are completely invisible to the app (the tombstone is private to
 *     the system).
 *  2. SESSION BREADCRUMBS: the GlobalLog ring buffer (fed by every Timber
 *     log in the app) is mirrored to session_log.txt every few seconds. The
 *     dying session's tail is attached to every report, which is what makes
 *     a bare register dump actually diagnosable ("what was the automix
 *     analyzer doing when it died?").
 *  3. JAVA: writeJavaCrashReport() writes the exception + breadcrumbs when
 *     an uncaught exception fires (App.kt's handler calls it before handing
 *     off to the DebugActivity flow).
 *
 * onStartup() (called from MainActivity) picks up anything the previous
 * session left behind: native trace files and java crash files that the user
 * has not been told about yet get copied into Download/ArchiveTune/ (no
 * permission needed via MediaStore on API 29+), a toast announces them, and
 * the directory is pruned.
 *
 * Everything here is deliberately dependency-free and defensive: crash
 * reporting must never be the thing that crashes.
 */

package moe.rukamori.archivetune.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object CrashReporter {
    private const val TAG = "CrashReporter"

    private const val CRASH_DIR_NAME = "crash"
    private const val SESSION_LOG_FILE = "session_log.txt"
    private const val PREV_SESSION_LOG_FILE = "session_log.prev.txt"
    private const val SURFACED_MARKER = ".surfaced"
    private const val PUBLIC_DIR_NAME = "ArchiveTune"

    private const val SESSION_LOG_MAX_BYTES = 512 * 1024
    private const val SESSION_LOG_KEEP_ON_ROTATE = 128 * 1024
    private const val MAX_CRASH_FILES = 10
    private const val FLUSH_INTERVAL_MS = 4_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var crashDir: File? = null

    private val flusherStarted = AtomicBoolean(false)
    private val sessionLogLock = Any()

    fun install(context: Context) {
        if (flusherStarted.getAndSet(true)) return
        appContext = context.applicationContext

        val dir = resolveCrashDir(context.applicationContext)
        crashDir = dir

        var nativeOk = false
        runCatching {
            System.loadLibrary("archivetune_crash")
            nativeOk = nativeInstall(dir.absolutePath)
        }.onFailure {
            GlobalLog.append(android.util.Log.WARN, TAG, "native crash handler unavailable: $it")
        }

        runCatching { rotateSessionLog(dir) }

        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                writeJavaCrashReport(thread, throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }

        startSessionLogFlusher(dir)

        GlobalLog.append(
            android.util.Log.INFO,
            TAG,
            "crash capture installed (dir=${dir.absolutePath}, native=$nativeOk)",
        )
    }

    private fun resolveCrashDir(context: Context): File {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        val dir = File(base, CRASH_DIR_NAME)
        runCatching { dir.mkdirs() }
        return dir
    }

    private fun sessionLogFile(dir: File): File = File(dir, SESSION_LOG_FILE)

    private fun prevSessionLogFile(dir: File): File = File(dir, PREV_SESSION_LOG_FILE)

    private fun rotateSessionLog(dir: File) {
        val current = sessionLogFile(dir)
        if (current.isFile) {
            val prev = prevSessionLogFile(dir)
            if (prev.isFile) prev.delete()
            current.renameTo(prev)
        }
    }

    private fun startSessionLogFlusher(dir: File) {
        Thread {
            var written = 0
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    GlobalLog.flush()
                    val entries = GlobalLog.logs.value
                    if (entries.isEmpty()) continue
                    val delta = when {
                        written == 0 -> entries
                        entries.size >= written -> entries.subList(written, entries.size)
                        else -> entries
                    }
                    if (delta.isEmpty()) continue
                    synchronized(sessionLogLock) {
                        appendToSessionLog(dir, delta)
                    }
                    written = entries.size
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                }
            }
        }.apply {
            name = "at-crash-log"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun appendToSessionLog(dir: File, delta: List<LogEntry>) {
        try {
            val file = sessionLogFile(dir)
            if (file.length() > SESSION_LOG_MAX_BYTES) {

                val text = file.readText()
                val keep = text.takeLast(SESSION_LOG_KEEP_ON_ROTATE).substringAfter('\n')
                file.writeText(keep)
            }
            file.appendText(delta.joinToString(separator = "\n", postfix = "\n") { GlobalLog.format(it) })
        } catch (_: IOException) {
        } catch (_: Throwable) {
        }
    }

    fun writeJavaCrashReport(thread: Thread, throwable: Throwable) {
        try {
            val dir = crashDir ?: resolveCrashDir(appContext ?: return)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_java_${stamp}.txt")
            file.writeText(buildReportHeader() + buildStackTraceSection(thread, throwable) + buildBreadcrumbsSection(dir))

            android.util.Log.e(TAG, "Java crash report written to ${file.absolutePath}")
        } catch (_: Throwable) {
        }
    }

    private fun buildReportHeader(): String {
        val ctx = appContext
        return buildString {
            appendLine("ArchiveTune crash report")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (api ${Build.VERSION.SDK_INT}, ${Build.VERSION.RELEASE})")
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            if (ctx != null) {
                appendLine("process: ${runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "?"}")
            }
        }
    }

    private fun buildStackTraceSection(thread: Thread, throwable: Throwable): String =
        buildString {
            appendLine()
            appendLine("--- uncaught exception (thread \"${thread.name}\") ---")
            appendLine(
                android.util.Log.getStackTraceString(throwable),
            )
        }

    private fun buildBreadcrumbsSection(dir: File): String =
        buildString {
            appendLine()
            appendLine("--- GlobalLog breadcrumbs (newest last) ---")
            runCatching {
                val entries = GlobalLog.logs.value
                entries.takeLast(400).forEach { appendLine(GlobalLog.format(it)) }
            }

            runCatching {
                val mirror = sessionLogFile(dir)
                if (mirror.isFile) {
                    appendLine("--- session log mirror tail ---")
                    appendLine(mirror.readText().takeLast(16 * 1024))
                }
            }
        }

    fun onStartup(context: Context) {
        try {
            val dir = crashDir ?: resolveCrashDir(context.applicationContext)
            crashDir = dir
            val marker = File(dir, SURFACED_MARKER)
            val lastSurfaced = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)

            val reports = dir
                .listFiles { f ->
                    (f.name.startsWith("native_crash_") || f.name.startsWith("crash_java_")) && f.isFile
                }
                ?.filter { it.lastModified() > lastSurfaced }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            if (reports.isEmpty()) return

            val prevSessionLog = prevSessionLogFile(dir)
            var copied = 0
            for (report in reports) {
                val ok = runCatching { copyReportToDownloads(context, report, prevSessionLog) }.getOrDefault(false)
                if (ok) copied++
            }

            runCatching {
                val newest = reports.maxOf { it.lastModified() }
                marker.writeText(newest.toString())
            }
            pruneOldReports(dir)

            if (copied > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.crash_log_saved_to_downloads, copied, PUBLIC_DIR_NAME),
                    Toast.LENGTH_LONG,
                ).show()
                GlobalLog.append(
                    android.util.Log.WARN,
                    TAG,
                    "${copied} crash report(s) from the previous session copied to Download/$PUBLIC_DIR_NAME",
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun copyReportToDownloads(
        context: Context,
        report: File,
        prevSessionLog: File,
    ): Boolean {
        val name = report.name.substringBeforeLast('.') + ".txt"
        val body = buildString {
            append(report.readText())

            if (report.name.startsWith("native_crash_") && prevSessionLog.isFile) {
                appendLine()
                appendLine("--- session breadcrumbs before the crash (newest last) ---")
                appendLine(prevSessionLog.readText().takeLast(SESSION_LOG_KEEP_ON_ROTATE))
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyViaMediaStore(context, name, body)
        } else {
            copyViaLegacyFile(context, name, body)
        }
    }

    private fun copyViaMediaStore(
        context: Context,
        name: String,
        body: String,
    ): Boolean {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DIR_NAME)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun copyViaLegacyFile(
        context: Context,
        name: String,
        body: String,
    ): Boolean {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloads, PUBLIC_DIR_NAME)
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return false
        File(targetDir, name).writeText(body)
        return true
    }

    private fun pruneOldReports(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith("native_crash_") || f.name.startsWith("crash_java_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CRASH_FILES)
                ?.forEach { it.delete() }
        }
    }

    @JvmStatic
    private external fun nativeInstall(dir: String): Boolean
}
