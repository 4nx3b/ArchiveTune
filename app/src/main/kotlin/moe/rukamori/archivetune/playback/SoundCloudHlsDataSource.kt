/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import moe.rukamori.archivetune.soundcloud.SoundCloudAudioProvider
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.min

internal class SoundCloudHlsDataSource(
    private val httpClient: OkHttpClient,
) : BaseDataSource(true) {
    private var currentUri: Uri? = null
    private var segmentUrls: List<String> = emptyList()
    private var nextSegmentIndex = 0
    private var pendingBytes = ByteArray(0)
    private var pendingOffset = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val playlistUrl = decodePlaylistUrl(dataSpec.uri)
            ?: throw IOException("SoundCloud HLS stream did not include a playlist URL")
        val mediaPlaylist = loadMediaPlaylist(playlistUrl)
            ?: throw IOException("SoundCloud HLS playlist could not be loaded")
        segmentUrls = mediaPlaylist.segmentUrls
        if (segmentUrls.isEmpty()) {
            throw IOException("SoundCloud HLS playlist contained no media segments")
        }
        nextSegmentIndex = 0
        pendingOffset = 0
        pendingBytes =
            if (mediaPlaylist.initSegmentUrl != null) {
                fetchFully(mediaPlaylist.initSegmentUrl)
            } else {
                ByteArray(0)
            }
        currentUri = dataSpec.uri
        bytesRemaining = C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)

        try {
            if (dataSpec.position > 0L) skipFully(dataSpec.position)
            bytesRemaining = dataSpec.length
            return bytesRemaining
        } catch (error: Throwable) {
            close()
            if (error is IOException) throw error
            throw IOException("SoundCloud HLS stream seek failed", error)
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("SoundCloud HLS stream is not open")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (pendingOffset >= pendingBytes.size && !loadNextSegment()) {
            return C.RESULT_END_OF_INPUT
        }

        var available = pendingBytes.size - pendingOffset
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            available = min(available.toLong(), bytesRemaining).toInt()
        }
        if (available <= 0) return C.RESULT_END_OF_INPUT

        val toCopy = min(length, available)
        System.arraycopy(pendingBytes, pendingOffset, buffer, offset, toCopy)
        pendingOffset += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        pendingBytes = ByteArray(0)
        pendingOffset = 0
        segmentUrls = emptyList()
        nextSegmentIndex = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
        currentUri = null
    }

    private fun loadNextSegment(): Boolean {
        if (nextSegmentIndex >= segmentUrls.size) return false
        val url = segmentUrls[nextSegmentIndex]
        nextSegmentIndex += 1
        pendingBytes = fetchFully(url)
        pendingOffset = 0
        return pendingBytes.isNotEmpty()
    }

    private fun skipFully(count: Long) {
        var left = count
        while (left > 0L) {
            if (pendingOffset >= pendingBytes.size && !loadNextSegment()) {
                throw IOException("SoundCloud HLS stream ended while seeking to $count")
            }
            val skip = min(left, (pendingBytes.size - pendingOffset).toLong()).toInt()
            pendingOffset += skip
            left -= skip
        }
    }

    private fun fetchFully(url: String): ByteArray {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SoundCloud HLS segment HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun loadMediaPlaylist(playlistUrl: String): MediaPlaylist? {
        var url = playlistUrl
        for (attempt in 0 until MAX_PLAYLIST_HOPS) {
            val text = runCatching { fetchText(url) }.getOrNull() ?: return null
            val parsed = parseHlsUriList(text, url) ?: return null
            if (!parsed.isMasterPlaylist) {
                return MediaPlaylist(
                    segmentUrls = parsed.uris,
                    initSegmentUrl = parsed.initUri,
                )
            }
            val variant = parsed.uris.firstOrNull() ?: return null
            url = variant
        }
        return null
    }

    private fun fetchText(url: String): String {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SoundCloud HLS playlist HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private data class ParsedHls(
        val uris: List<String>,
        val initUri: String?,
        val isMasterPlaylist: Boolean,
    )

    private fun parseHlsUriList(
        text: String,
        baseUrl: String,
    ): ParsedHls? {
        val uris = mutableListOf<String>()
        var initUri: String? = null
        var isMaster = false
        val lines = text.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-STREAM-INF") -> isMaster = true
                line.startsWith("#EXT-X-MAP") -> {
                    Regex("""URI="([^"]+)""").find(line)?.groupValues?.get(1)?.let { uri ->
                        initUri = resolveUrl(uri, baseUrl)
                    }
                }
                line.startsWith("#") -> {}
                else -> uris.add(resolveUrl(line, baseUrl))
            }
        }
        if (uris.isEmpty() && initUri == null) return null
        return ParsedHls(uris, initUri, isMaster)
    }

    private fun resolveUrl(
        candidate: String,
        baseUrl: String,
    ): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        val base = Uri.parse(baseUrl)
        return if (candidate.startsWith("//")) {
            "${base.scheme}:$candidate"
        } else if (candidate.startsWith('/')) {
            "${base.scheme}://${base.host}$candidate"
        } else {
            base.toString().substringBeforeLast('/') + "/$candidate"
        }
    }

    private fun decodePlaylistUrl(uri: Uri): String? {
        if (!uri.scheme.equals(SoundCloudAudioProvider.HLS_SCHEME, ignoreCase = true)) return null
        val encoded = uri.schemeSpecificPart ?: return null
        if (encoded.isBlank()) return null
        return runCatching {
            String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private data class MediaPlaylist(
        val segmentUrls: List<String>,
        val initSegmentUrl: String?,
    )

    class Factory(
        private val httpClient: OkHttpClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SoundCloudHlsDataSource(httpClient)
    }

    private companion object {
        const val MAX_PLAYLIST_HOPS = 3
    }
}
