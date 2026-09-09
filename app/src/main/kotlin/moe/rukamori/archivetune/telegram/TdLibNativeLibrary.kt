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
import java.lang.reflect.Modifier
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

    /** Last load-blocking failure detail, surfaced through TdEngine.lastStartError. */
    @Volatile
    var lastLoadError: String? = null
        private set

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
        if (!file.isFile) {
            lastLoadError = "the downloaded library is missing"
            return false
        }

        if (!matchesDigest(file)) {
            Timber.tag(TAG).w("Cached %s failed its digest check; deleting", file.name)
            file.delete()
            cleanupStaleVersions(context)
            lastLoadError = "the downloaded library failed its digest check"
            return false
        }

        TdStartTrace.step(context, "digest-ok", "${file.length()} bytes")

        // The tdlight library's JNI_OnLoad resolves its whole Java surface
        // through JNI lookups (tl_jni_object.cpp: FindClass/GetFieldID/
        // GetMethodID/RegisterNatives), and ANY miss calls env->FatalError —
        // an uncatachable process abort DURING System.load, with no Java
        // stack trace and no TDLib fatal note. Re-doing the identical
        // lookups from Kotlin first turns that whole crash class into a
        // readable start failure instead of a dead app.
        preflightJniSurface()?.let { problem ->
            lastLoadError = "JNI surface check failed: $problem"
            Timber.tag(TAG).e("Refusing to load %s: %s", file.name, problem)
            TdStartTrace.step(context, "preflight-failed", problem.take(160))
            return false
        }

        TdStartTrace.step(context, "load-begin", file.name)
        val loadSucceeded =
            runCatching {
                System.load(file.absolutePath)
                true
            }.getOrElse { failure ->
                Timber.tag(TAG).e(failure, "Loading %s failed", file.absolutePath)
                lastLoadError =
                    ("System.load failed: ${failure.javaClass.simpleName}: " +
                        "${failure.message.orEmpty()}").take(300)
                TdStartTrace.step(context, "load-failed", lastLoadError.orEmpty().take(160))
                false
            }
        if (loadSucceeded) {
            loaded = true
            lastLoadError = null
            TdStartTrace.step(context, "load-ok")
        }
        return loadSucceeded
    }

    /**
     * Performs every Java lookup tdlight's JNI_OnLoad and early fetch code
     * performs natively, from Kotlin. Returns null when the surface is
     * intact (safe to System.load), or a compact description of the first
     * problems found (must NOT load: the same lookup aborts the process
     * through env->FatalError inside System.load).
     */
    private fun preflightJniSurface(): String? {
        val problems = mutableListOf<String>()

        fun lookupClass(name: String): Class<*> =
            try {
                Class.forName(name)
            } catch (failure: Throwable) {
                problems += "missing class $name (${failure.javaClass.simpleName})"
                Void::class.java
            }

        fun lookupField(owner: Class<*>, name: String) {
            try {
                owner.getDeclaredField(name)
            } catch (failure: Throwable) {
                problems += "missing field ${owner.name}.$name"
            }
        }

        fun lookupNative(owner: Class<*>, name: String, vararg params: Class<*>) {
            try {
                val method = owner.getDeclaredMethod(name, *params)
                if (!Modifier.isNative(method.modifiers)) {
                    problems += "${owner.name}.$name lost its native modifier"
                }
            } catch (failure: Throwable) {
                problems += "missing method ${owner.name}.$name (${failure.javaClass.simpleName})"
            }
        }

        // --- register_native (td_jni.cpp): version check + native methods ---
        val tdApi = lookupClass("org.drinkless.tdlib.TdApi")
        lookupField(tdApi, "GIT_COMMIT_HASH")
        val client = lookupClass("org.drinkless.tdlib.Client")
        val objectArray = lookupClass("[Lorg.drinkless.tdlib.TdApi\$Object;")
        val functionClass = lookupClass("org.drinkless.tdlib.TdApi\$Function")
        lookupNative(client, "createNativeClient")
        lookupNative(
            client,
            "nativeClientSend",
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            functionClass,
        )
        lookupNative(
            client,
            "nativeClientReceive",
            IntArray::class.java,
            LongArray::class.java,
            objectArray,
            Double::class.javaPrimitiveType!!,
        )
        lookupNative(client, "nativeClientExecute", functionClass)
        lookupNative(
            client,
            "nativeClientSetLogMessageHandler",
            Int::class.javaPrimitiveType!!,
            lookupClass("org.drinkless.tdlib.Client\$LogMessageHandler"),
        )

        // --- TdApi.Object / TdApi.Function native toString + toJsonString,
        // and the getConstructor method ID init_vars caches. ---
        val objectClass = lookupClass("org.drinkless.tdlib.TdApi\$Object")
        lookupNative(objectClass, "toString")
        lookupNative(objectClass, "toJsonString")
        lookupNative(functionClass, "toString")
        lookupNative(functionClass, "toJsonString")
        runCatching { objectClass.getMethod("getConstructor") }
            .onFailure { problems += "TdApi\$Object.getConstructor is missing" }
        runCatching { functionClass.getMethod("getConstructor") }
            .onFailure { problems += "TdApi\$Function.getConstructor is missing" }

        // --- init_vars' array classes (FindClass of "[L...;"). ---
        lookupClass("[Lorg.drinkless.tdlib.TdApi\$KeyboardButton;")
        lookupClass("[Lorg.drinkless.tdlib.TdApi\$InlineKeyboardButton;")
        lookupClass("[Lorg.drinkless.tdlib.TdApi\$PageBlockTableCell;")

        // --- the first two RPCs the engine sends: their Java fields are
        // fetched natively through GetFieldID (generated td_api_jni code). ---
        lookupField(lookupClass("org.drinkless.tdlib.TdApi\$SetLogVerbosityLevel"), "newVerbosityLevel")
        lookupField(lookupClass("org.drinkless.tdlib.TdApi\$GetOption"), "name")

        return if (problems.isEmpty()) null else problems.take(4).joinToString("; ").take(300)
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
                lastLoadError = "device ABI is not supported (${Build.SUPPORTED_ABIS.joinToString()})"
                return@withContext false
            }
            val base = BuildConfig.TDLIB_NATIVE_BASE_URL.trim().trimEnd('/')
            if (base.isEmpty()) {
                Timber.tag(TAG).e("This build has no TDLIB_NATIVE_BASE_URL to download from")
                lastLoadError = "this build has no engine download URL"
                return@withContext false
            }

            val url = "$base/libtdjni-$abi.so.gz"
            TdStartTrace.step(context, "download-begin", "abi=$abi")
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
                lastLoadError = "could not move the verified library into place"
                partial.delete()
                return@withContext false
            }
            TdStartTrace.step(context, "download-ok", "${destination.length()} bytes")
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
