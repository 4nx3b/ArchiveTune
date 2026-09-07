/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SfProFontCatalog {
    private const val INDEX_URL = "https://sf-pro.kouzu.in/fonts.json"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    @Serializable
    data class FontIndex(
        val baseUrl: String? = null,
        @SerialName("total_fonts") val totalFonts: Int? = null,
        val families: List<String> = emptyList(),
        val fonts: List<FontEntry> = emptyList(),
    )

    @Serializable
    data class FontEntry(
        val name: String,
        val family: String? = null,
        val style: String? = null,
        val weight: String? = null,
        @SerialName("numeric_weight") val numericWeight: Int? = null,
        val format: String? = null,
        val type: String? = null,
        @SerialName("fileName") val fileName: String? = null,
        val url: String,
    )

    suspend fun fetchCatalog(): List<FontEntry>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(INDEX_URL)
                        .header("Accept", "application/json")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    json.decodeFromString<FontIndex>(body).fonts
                }
            }.getOrNull()
        }

    suspend fun downloadFont(entry: FontEntry): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(entry.url)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    // Guard against HTML error pages masquerading as fonts.
                    if (bytes.size < 1024) null else bytes
                }
            }.getOrNull()
        }
}
