package moe.rukamori.archivetune.listentogether

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

object GifShareApi {
    private const val TAG = "GifShareApi"
    private const val UPLOAD_ENDPOINT = "https://catbox.moe/user/api.php"
    private const val FALLBACK_ENDPOINT = "https://litterbox.catbox.moe/resources/internals/api.php"
    private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
    private const val FALLBACK_HOURS = "72h"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UploadedGif(
        val url: String,
        val width: Int,
        val height: Int,
    )

    suspend fun upload(context: Context, source: Uri): UploadedGif? = withContext(Dispatchers.IO) {
        val dims = probeDimensions(context, source) ?: (0 to 0)
        val temp = runCatching {
            val file = File(context.cacheDir, "lt_shared_${System.currentTimeMillis()}.gif")
            context.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            if (file.length() > MAX_UPLOAD_BYTES) {
                file.delete()
                Timber.tag(TAG).w("GIF too large to share: ${file.length()} bytes")
                return@withContext null
            }
            file
        }.getOrNull() ?: return@withContext null

        try {
            uploadFile(temp)?.let { url ->
                return@withContext UploadedGif(url, dims.first, dims.second)
            }

            uploadFile(temp, temporary = true)?.let { url ->
                return@withContext UploadedGif(url, dims.first, dims.second)
            }
            null
        } finally {
            temp.delete()
        }
    }

    private fun uploadFile(file: File, temporary: Boolean = false): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .apply {
                if (temporary) addFormDataPart("time", FALLBACK_HOURS)
            }
            .addFormDataPart(
                "fileToUpload",
                file.name,
                file.asRequestBody("image/gif".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(if (temporary) FALLBACK_ENDPOINT else UPLOAD_ENDPOINT)
            .post(body)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("GIF upload rejected: HTTP ${response.code}")
                    return@runCatching null
                }
                val url = response.body?.string()?.trim()
                if (url.isNullOrEmpty() || !url.startsWith("https://")) {
                    Timber.tag(TAG).w("GIF upload returned an unexpected body")
                    null
                } else {
                    Timber.tag(TAG).d("GIF uploaded: $url")
                    url
                }
            }
        }.onFailure { Timber.tag(TAG).w(it, "GIF upload failed") }.getOrNull()
    }

    private fun probeDimensions(context: Context, source: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        val width = options.outWidth
        val height = options.outHeight
        if (width > 0 && height > 0) width to height else null
    }.getOrNull()
}
