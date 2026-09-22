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

/**
 * Minimal Giphy client for the chat's GIF picker.
 *
 * Only metadata ever crosses the wire here: the chat message itself carries
 * just the GIF's URL — the server relays the link and every receiving device
 * loads and animates the GIF locally.
 */
object GiphyApi {
    /**
     * Giphy retired the old public beta key (`dc6zaTOxFJmzC` now answers 403
     * BANNED), which made every request fail as a generic network error. These
     * are the keys Giphy's own web/mobile clients ship, tried in order — the
     * web key first, the mobile one as a fallback if the primary is ever
     * rate-limited or retired too.
     */
    private val API_KEYS =
        listOf(
            "Gc7131jiJuvI7IdN0HZ1D7nh0ow5BU6g", // giphy.com web client key
            "L8eXbxrbPETZxlvgXN9kIEzQ55Df04v0", // giphy mobile client key (fallback)
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
        /** The animated GIF actually rendered in chat bubbles. */
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

    /** Trending GIFs, paginated. */
    suspend fun trending(offset: Int = 0): Result = fetch("$BASE_URL/trending", null, offset)

    /** Search GIFs by query, paginated. */
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
                // A rejected key (401/403) or a transient failure returns null
                // and is worth retrying on the fallback key.
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
