/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.listentogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object GiphyApi {
    private val API_KEYS =
        listOf(
            "Gc7131jiJuvI7IdN0HZ1D7nh0ow5BU6g",
            "L8eXbxrbPETZxlvgXN9kIEzQ55Df04v0",
        )

    private const val BASE_URL = "https://api.giphy.com/v1/gifs"

    private const val PAGE_SIZE = 30

    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

    @Serializable
    data class GifItem(
        val id: String,
        val title: String? = null,
        val images: GifImages,
    ) {
        val url: String? get() = images.fixedHeight?.url
        val width: Int get() = images.fixedHeight?.width?.toIntOrNull() ?: 200
        val height: Int get() = images.fixedHeight?.height?.toIntOrNull() ?: 200

        @Serializable
        data class GifImages(
            @SerialName("fixed_height") val fixedHeight: GifImage? = null,
        )

        @Serializable
        data class GifImage(
            val url: String,
            val width: String? = null,
            val height: String? = null,
        )
    }

    @Serializable
    private data class SearchResponse(
        val data: List<GifItem> = emptyList(),
        val pagination: Pagination? = null,
    ) {
        @Serializable
        data class Pagination(
            @SerialName("total_count") val totalCount: Int = 0,
            val count: Int = 0,
            val offset: Int = 0,
        )
    }

    sealed interface Result {
        data class Success(
            val items: List<GifItem>,
            val nextOffset: Int?,
        ) : Result

        data object Failure : Result
    }

    suspend fun trending(offset: Int = 0): Result = fetch("$BASE_URL/trending", null, offset)

    suspend fun search(query: String, offset: Int = 0): Result = fetch("$BASE_URL/search", query, offset)

    private suspend fun fetch(
        url: String,
        query: String?,
        offset: Int,
    ): Result =
        withContext(Dispatchers.IO) {
            for (key in API_KEYS) {
                val attempted =
                    try {
                        fetchWithKey(key, url, query, offset)
                    } catch (_: Exception) {
                        null
                    }

                if (attempted != null) return@withContext attempted
            }
            Result.Failure
        }

    private fun fetchWithKey(
        key: String,
        url: String,
        query: String?,
        offset: Int,
    ): Result? {
        val fullUrl =
            url
                .toHttpUrl()
                .newBuilder()
                .apply {
                    addQueryParameter("api_key", key)
                    addQueryParameter("limit", PAGE_SIZE.toString())
                    addQueryParameter("offset", offset.toString())
                    addQueryParameter("rating", "pg-13")
                    query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                }.build()
        val request =
            Request
                .Builder()
                .url(fullUrl)
                .get()
                .addHeader("Accept", "application/json")
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (body.isBlank()) return null
            val parsed = json.decodeFromString(SearchResponse.serializer(), body)
            val items = parsed.data.filter { !it.url.isNullOrBlank() }
            val total = parsed.pagination?.totalCount ?: items.size
            val next =
                if (items.isEmpty() || offset + items.size >= total) {
                    null
                } else {
                    offset + items.size
                }
            return Result.Success(items, next)
        }
    }
}
