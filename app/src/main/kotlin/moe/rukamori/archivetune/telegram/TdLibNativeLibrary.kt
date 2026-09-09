/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object TdLibNativeLibrary {
    private const val TAG = "TdLibNative"

    /** TDLight build the digests below were computed from (tdlight-team/tdlight). */
    const val VERSION = "tdlight-2b51b33"

    private const val LIB_NAME = "tdjni"
    private const val FILE_NAME = "libtdjni.so"

    // SHA-256 of the decompressed libtdjni.so per ABI, published in the
    // release's libtdjni-digests.txt asset next to the .so.gz downloads.
    private val DIGESTS =
        mapOf(
            "arm64-v8a" to "29e0ffb1e99ef30f1ae6db1a9ffc76e4bb82f6a888f91999596de237d17ea110",
            "armeabi-v7a" to "d30b446aa6906274655e68317460b485c41cac3c258a86fa99627add089bce12",
            "x86_64" to "5b114899f4e0aeefb2580131c6d3d48a9135c008328fca94368ccccc2f3550e7",
            "x86" to "3d3a67b2b0a924d3a2105fde12d852517cfd871371d94eddad9425e32622166e",
        )

    @Volatile
    private var loaded = false

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    private val abi: String?
        get() = Build.SUPPORTED_ABIS.firstOrNull { it in DIGESTS }

    private fun target(context: Context): File =
        File(File(context.filesDir, "tdlib-native"), "$VERSION-${abi.orEmpty()}-$FILE_NAME")

    val isLoaded: Boolean get() = loaded

    fun needsDownload(context: Context): Boolean =
        !loaded && !BuildConfig.TDLIB_BUNDLED && !target(context).isFile

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        if (runCatching { System.loadLibrary(LIB_NAME) }.isSuccess) {
            loaded = true
            return true
        }

        val file = target(context)
        if (!file.isFile) return false

        if (!matchesDigest(file)) {
            Timber.tag(TAG).w("Cached %s failed its digest check; deleting", file.name)
            file.delete()
            cleanupStaleVersions(context)
            return false
        }
        return runCatching {
            System.load(file.absolutePath)
            loaded = true
            true
        }.getOrElse {
            Timber.tag(TAG).e(it, "Loading %s failed", file.absolutePath)
            false
        }
    }

    /**
     * Downloads the gzip-compressed libtdjni.so for this device's ABI,
     * decompresses it, verifies the SHA-256 of the *decompressed* bytes and
     * atomically moves it into place. [onProgress] reports 0..1 against the
     * compressed transfer size.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (ensureLoaded(context)) return@withContext true

            val abi = abi
            if (abi == null) {
                Timber.tag(TAG).e("No supported ABI among %s", Build.SUPPORTED_ABIS.joinToString())
                return@withContext false
            }
            val base = BuildConfig.TDLIB_NATIVE_BASE_URL.trim().trimEnd('/')
            if (base.isEmpty()) {
                Timber.tag(TAG).e("This build has no TDLIB_NATIVE_BASE_URL to download from")
                return@withContext false
            }

            val url = "$base/libtdjni-$abi.so.gz"
            val destination = target(context)
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".part")
            partial.delete()

            val ok =
                runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.tag(TAG).e("Download of %s failed: HTTP %d", url, response.code)
                            return@use false
                        }
                        val body = response.body ?: return@use false
                        val total = body.contentLength()
                        var read = 0L
                        val computedDigest =
                            GZIPInputStream(body.byteStream()).use { gunzip ->
                                FileOutputStream(partial).use { output ->
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                    while (true) {
                                        val n = gunzip.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        digest.update(buffer, 0, n)
                                        read += n
                                        if (total > 0) {
                                            onProgress((read.toFloat() / total).coerceAtMost(1f))
                                        } else {
                                            onProgress(-1f)
                                        }
                                    }
                                    digest.digest().joinToString("") { "%02x".format(it) }
                                }
                            }
                        val expected = DIGESTS[abi]
                        if (!computedDigest.equals(expected, ignoreCase = true)) {
                            Timber.tag(TAG)
                                .e(
                                    "Downloaded %s digest mismatch: expected %s, got %s",
                                    url,
                                    expected,
                                    computedDigest,
                                )
                            return@use false
                        }
                        true
                    }
                }.getOrElse {
                    Timber.tag(TAG).e(it, "Download of %s failed", url)
                    false
                }

            if (!ok) {
                partial.delete()
                return@withContext false
            }

            if (!partial.renameTo(destination)) {
                Timber.tag(TAG).e("Could not move the verified library into place")
                partial.delete()
                return@withContext false
            }
            cleanupStaleVersions(context)
            ensureLoaded(context)
        }

    /** Deletes libtdjni.so copies from other engine versions. */
    private fun cleanupStaleVersions(context: Context) {
        val dir = target(context).parentFile ?: return
        val prefix = "$VERSION-"
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.startsWith(prefix) && file.name.endsWith(FILE_NAME)) {
                    file.delete()
                }
            }
        }
    }

    private fun matchesDigest(file: File): Boolean {
        val expected = DIGESTS[abi] ?: return false
        val digest =
            runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        md.update(buffer, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull() ?: return false
        return digest.equals(expected, ignoreCase = true)
    }

    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
}
