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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object TdLibNativeLibrary {
    private const val TAG = "TdLibNative"

    const val VERSION = "1.8.56"

    private const val LIB_NAME = "tdjni"
    private const val FILE_NAME = "libtdjni.so"

    private val DIGESTS =
        mapOf(
            "arm64-v8a" to "7c1751197b35a64261e3b3f21764874c9ee8795e4b6118c23a74499426c44b91",
            "armeabi-v7a" to "56bcd646dae3442a2aeefee3ce28b72c14dc257488d267d4ed76e7e01e08f158",
            "x86" to "4c1d128b862a35c293dc96a20cb9f41ffa33144c80b9ded858028bc3f9ca93ec",
            "x86_64" to "567bb5aaccdcc1d8280577f2f9fe8e82178908c72f513436972493fd6ad6dabd",
        )

    @Volatile
    private var loaded = false

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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

            val url = "$base/libtdjni-$VERSION-$abi.so"
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
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    read += n
                                    onProgress(if (total > 0) read.toFloat() / total else -1f)
                                }
                            }
                        }
                        true
                    }
                }.getOrElse {
                    Timber.tag(TAG).e(it, "Download of %s failed", url)
                    false
                }

            if (!ok || !matchesDigest(partial)) {
                if (ok) Timber.tag(TAG).e("Downloaded %s did not match its expected digest", url)
                partial.delete()
                return@withContext false
            }

            if (!partial.renameTo(destination)) {
                Timber.tag(TAG).e("Could not move the verified library into place")
                partial.delete()
                return@withContext false
            }
            ensureLoaded(context)
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
