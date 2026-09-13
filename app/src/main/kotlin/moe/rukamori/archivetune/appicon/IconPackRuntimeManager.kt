/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.appicon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object IconPackRuntimeManager {
    private const val TAG = "IconPackRuntime"

    const val VERSION = "icon-pack-v1"

    private const val DOWNLOAD_ATTEMPTS = 3

    private val DOWNLOAD_RETRY_BACKOFF_SECONDS = longArrayOf(1L, 3L)

    const val EXPECTED_SHA256 = "ff4cfc1114cf0a8ef0d0df45fbc1e544fd14f23efbc59a7b3fcbdb53de9c519d"

    private const val ZIP_ENTRY_PREFIX = "icon_pack/"
    private const val CATALOG_ENTRY = "icon_pack/catalog.json"
    private const val DRAWABLES_ENTRY_PREFIX = "icon_pack/drawables/"
    private const val MIN_PNG_BYTES = 1024
    private const val BUFFER = 64 * 1024

    sealed interface InstallState {
        data object NotInstalled : InstallState

        data class Downloading(
            val percent: Int,
            val indeterminate: Boolean,
        ) : InstallState

        data class Installed(
            val version: String,
            val iconCount: Int,
        ) : InstallState

        data class Failed(val message: String?) : InstallState
    }

    private val _installState = MutableStateFlow<InstallState>(InstallState.NotInstalled)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val installMutex = Mutex()
    private var lastFailure: String? = null

    private val client by lazy {
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()
    }

    fun packDirectory(context: Context): File = File(File(context.filesDir, "icon-pack"), VERSION)

    fun catalogFile(context: Context): File = File(packDirectory(context), CATALOG_ENTRY.removePrefix(ZIP_ENTRY_PREFIX))

    private fun drawablesDirectory(context: Context): File =
        File(packDirectory(context), "drawables")

    fun iconFile(
        context: Context,
        drawableResourceName: String,
    ): File = File(drawablesDirectory(context), "$drawableResourceName.png")

    fun isBundled(): Boolean = BuildConfig.ICON_PACK_BUNDLED

    fun isInstalled(context: Context): Boolean =
        !isBundled() &&
            catalogFile(context).isFile &&
            catalogFile(context).length() >= 64 &&
            drawablesDirectory(context).isDirectory

    fun needsDownload(context: Context): Boolean = !isBundled() && !isInstalled(context)

    fun lastInstallFailure(): String? = lastFailure

    suspend fun install(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                val appContext = context.applicationContext
                if (isBundled()) return@withLock true
                if (isInstalled(appContext)) {
                    refreshState(appContext)
                    return@withLock true
                }

                lastFailure = null
                _installState.value = InstallState.Downloading(percent = 0, indeterminate = true)
                onProgress(-1f)

                Timber.tag(TAG).i("Installing runtime icon pack %s (bundled=%b)", VERSION, isBundled())

                val zipFile = File(appContext.cacheDir, "icon-pack-$VERSION.part.zip")
                var ok = false
                var lastError: Throwable? = null
                for (attempt in 1..DOWNLOAD_ATTEMPTS) {
                    zipFile.delete()
                    File(zipFile.parentFile, zipFile.name + ".tmp").delete()
                    ok =
                        runCatching {
                            downloadZip(appContext, zipFile, onProgress, attempt)
                            true
                        }.getOrElse { t ->
                            lastError = t
                            Timber.tag(TAG).w(t, "Download attempt %d/%d failed", attempt, DOWNLOAD_ATTEMPTS)
                            false
                        }
                    if (ok) break
                    if (attempt < DOWNLOAD_ATTEMPTS) {
                        val backoffSeconds = DOWNLOAD_RETRY_BACKOFF_SECONDS.getOrNull(attempt - 1) ?: 3L
                        Timber.tag(TAG).i("Retrying icon pack download in %ds", backoffSeconds)
                        delay(backoffSeconds * 1000L)
                    }
                }
                if (!ok) {
                    zipFile.delete()
                    File(zipFile.parentFile, zipFile.name + ".tmp").delete()
                    lastFailure = lastError?.message ?: "download failed"
                    Timber.tag(TAG).e("Icon pack download failed after %d attempts: %s", DOWNLOAD_ATTEMPTS, lastFailure)
                    _installState.value = InstallState.Failed(lastFailure)
                    return@withLock false
                }

                val extracted =
                    runCatching {
                        extractZip(appContext, zipFile)
                        true
                    }.getOrElse { t ->
                        lastFailure = t.message
                        Timber.tag(TAG).w(t, "Icon pack extraction failed")
                        false
                    }
                zipFile.delete()
                if (!extracted) {
                    packDirectory(appContext).deleteRecursively()
                    _installState.value = InstallState.Failed(lastFailure)
                    return@withLock false
                }

                File(appContext.filesDir, "icon-pack")
                    .listFiles { f -> f.isDirectory && f.name != VERSION }
                    ?.forEach { it.deleteRecursively() }

                refreshState(appContext)
                Timber.tag(TAG).i("Icon pack installed: version=%s, icons=%d", VERSION, (installState.value as? InstallState.Installed)?.iconCount ?: -1)
                isInstalled(appContext)
            }
        }

    private fun refreshState(context: Context) {
        _installState.value =
            if (isInstalled(context)) {
                InstallState.Installed(
                    version = VERSION,
                    iconCount =
                        drawablesDirectory(context)
                            .listFiles { f -> f.isFile && f.name.endsWith(".png") }
                            ?.size ?: 0,
                )
            } else if (lastFailure != null) {
                InstallState.Failed(lastFailure)
            } else {
                InstallState.NotInstalled
            }
    }

    private fun downloadZip(
        context: Context,
        target: File,
        onProgress: (Float) -> Unit,
        attempt: Int,
    ) {
        val url = "${BuildConfig.ICON_PACK_BASE_URL.trimEnd('/')}/$VERSION.zip"
        Timber.tag(TAG).d("Downloading icon pack (attempt %d): %s", attempt, url)
        val request =
            Request
                .Builder()
                .url(url)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Icon pack download HTTP %d (redirect=%s, url=%s)", response.code, response.headers["Location"], response.request.url)
                throw IllegalStateException("Icon pack download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Icon pack download returned no body")
            val total = body.contentLength()
            Timber.tag(TAG).d("Icon pack download started: reported size=%d bytes", total)
            val digest = MessageDigest.getInstance("SHA-256")
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".tmp")
            var read = 0L
            var lastLoggedPercent = -1
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val fraction = if (total > 0) read.toFloat() / total else -1f
                        _installState.value =
                            InstallState.Downloading(
                                percent = if (fraction >= 0f) (fraction * 100).toInt() else 0,
                                indeterminate = fraction < 0f,
                            )
                        onProgress(fraction)
                        val loggedPercent = if (fraction >= 0f) (fraction * 100).toInt() / 25 else -1
                        if (loggedPercent != lastLoggedPercent && loggedPercent >= 0) {
                            lastLoggedPercent = loggedPercent
                            Timber.tag(TAG).d("Icon pack download at %d%% (%d bytes)", loggedPercent * 25, read)
                        }
                    }
                }
            }
            Timber.tag(TAG).d("Icon pack download finished: %d bytes read", read)
            if (read < MIN_PNG_BYTES) {
                partial.delete()
                throw IllegalStateException("Icon pack download too small")
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (EXPECTED_SHA256.isNotBlank() && !sha.equals(EXPECTED_SHA256, ignoreCase = true)) {
                partial.delete()
                Timber.tag(TAG).e("Icon pack digest mismatch: expected %s, got %s", EXPECTED_SHA256, sha)
                throw IllegalStateException("Icon pack digest mismatch (expected $EXPECTED_SHA256, got $sha)")
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                target.delete()
                throw IllegalStateException("Could not move downloaded icon pack into place")
            }
        }
    }

    private fun extractZip(
        context: Context,
        zipFile: File,
    ) {
        val dir = packDirectory(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val drawables = File(dir, "drawables")
        drawables.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            var sawCatalog = false
            while (entry != null) {
                val name = entry.name
                when {
                    name == CATALOG_ENTRY -> {
                        File(dir, "catalog.json").outputStream().use { zip.copyTo(it) }
                        sawCatalog = true
                    }

                    name.startsWith(DRAWABLES_ENTRY_PREFIX) && name.endsWith(".png") -> {
                        val out = File(drawables, name.removePrefix(DRAWABLES_ENTRY_PREFIX))
                        if (out.canonicalPath.startsWith(drawables.canonicalPath)) {
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                entry = zip.nextEntry
            }
            if (!sawCatalog) throw IllegalStateException("Icon pack zip is missing its catalog")
        }
    }

}
