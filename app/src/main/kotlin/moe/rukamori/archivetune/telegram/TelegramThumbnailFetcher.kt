/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Coil fetcher for Telegram artwork. Artwork is addressed by a
 * `tgart://track/<chatId>/<messageId>?t=<title>&a=<artist>&thumb=1` model so
 * cover art is resolved lazily and only for images actually shown.
 * Resolution order:
 *   1. a high-resolution catalogue cover looked up online by title/artist
 *      (TelegramCoverProvider),
 *   2. the album-cover thumbnail embedded in the Telegram document (downloaded
 *      from the mtcute host),
 *   3. nothing (Coil shows the placeholder).
 * This keeps the player art crisp without eagerly downloading covers for the
 * whole queue.
 */

package moe.rukamori.archivetune.telegram

import android.net.Uri as AndroidUri
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

private const val TELEGRAM_ART_SCHEME = "tgart"
private const val TELEGRAM_ART_TRACK_AUTHORITY = "track"

/**
 * Artwork model for a Telegram track. Addressed by chat + message so the
 * document thumbnail can be fetched through the mtcute host on demand.
 */
fun telegramArtworkModel(
    chatId: Long,
    messageId: Long,
    title: String?,
    artist: String?,
    hasThumb: Boolean,
): String? {
    if (chatId == 0L || messageId == 0L) return null
    if (!hasThumb && title.isNullOrBlank()) return null
    val builder =
        AndroidUri
            .Builder()
            .scheme(TELEGRAM_ART_SCHEME)
            .authority(TELEGRAM_ART_TRACK_AUTHORITY)
            .appendPath(chatId.toString())
            .appendPath(messageId.toString())
    if (hasThumb) builder.appendQueryParameter("thumb", "1")
    if (!title.isNullOrBlank()) builder.appendQueryParameter("t", title)
    if (!artist.isNullOrBlank()) builder.appendQueryParameter("a", artist)
    return builder.build().toString()
}

fun telegramArtworkModel(track: TelegramTrack): String? =
    telegramArtworkModel(
        chatId = track.chatId,
        messageId = track.messageId,
        title = track.lookupMetadata.title,
        artist = track.lookupMetadata.artist,
        hasThumb = track.hasThumbnail,
    )

class TelegramThumbnailFetcher(
    private val data: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val parsed = AndroidUri.parse(data.toString())
        val isTrackModel = parsed.authority == TELEGRAM_ART_TRACK_AUTHORITY
        val segments = parsed.pathSegments
        val chatId = if (isTrackModel) segments.getOrNull(0)?.toLongOrNull() ?: 0L else 0L
        val messageId = if (isTrackModel) segments.getOrNull(1)?.toLongOrNull() ?: 0L else 0L
        val wantThumb = parsed.getQueryParameter("thumb") == "1"
        val title = parsed.getQueryParameter("t")
        val artist = parsed.getQueryParameter("a")

        if (!title.isNullOrBlank()) {
            val coverUrl = withContext(Dispatchers.IO) { TelegramCoverProvider.coverUrl(title, artist) }
            if (coverUrl != null) {
                val remote = fetchRemote(coverUrl)
                if (remote != null) return remote
            }
        }

        if (wantThumb && chatId != 0L && messageId != 0L) {
            val bytes = TelegramClient.downloadFullFile(chatId, messageId, thumb = true) ?: return null
            return withContext(Dispatchers.IO) {
                runCatching {
                    SourceFetchResult(
                        source =
                            ImageSource(
                                source = Buffer().write(bytes),
                                fileSystem = options.fileSystem,
                            ),
                        mimeType = null,
                        dataSource = DataSource.NETWORK,
                    )
                }.getOrNull()
            }
        }
        return null
    }

    private suspend fun fetchRemote(url: String): FetchResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    SourceFetchResult(
                        source =
                            ImageSource(
                                source = Buffer().write(bytes),
                                fileSystem = options.fileSystem,
                            ),
                        mimeType = response.body?.contentType()?.toString(),
                        dataSource = DataSource.NETWORK,
                    )
                }
            }.getOrNull()
        }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (data.scheme != TELEGRAM_ART_SCHEME) return null
            return TelegramThumbnailFetcher(data, options)
        }
    }

    private companion object {
        val httpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
