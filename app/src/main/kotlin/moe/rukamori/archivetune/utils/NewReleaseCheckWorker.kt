/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.innertube.YouTube

class NewReleaseCheckWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val database =
                EntryPointAccessors
                    .fromApplication(applicationContext, NewReleaseCheckWorkerEntryPoint::class.java)
                    .newReleaseDatabase()

            val subscribedArtistIds: Set<String> =
                database
                    .artistsBookmarkedByCreateDateAsc()
                    .first()
                    .mapNotNull { it.artist.id }
                    .toSet()
            if (subscribedArtistIds.isEmpty()) return@withContext Result.success()

            val albums =
                try {
                    YouTube.newReleaseAlbums().getOrThrow()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {

                    return@withContext Result.success()
                }
            if (albums.isEmpty()) return@withContext Result.success()

            val seenIds = NewReleaseNotificationManager.readSeenReleaseIds(applicationContext)

            val subscribedReleases =
                albums.mapNotNull { album ->
                    val matchedArtist =
                        album.artists?.firstOrNull { artist ->
                            artist.id != null && artist.id in subscribedArtistIds
                        } ?: return@mapNotNull null
                    NewReleaseNotificationManager.NewRelease(
                        releaseId = album.id,
                        title = album.title,
                        artistName = matchedArtist.name,
                    )
                }

            if (seenIds.isEmpty()) {

                NewReleaseNotificationManager.writeSeenReleaseIds(
                    applicationContext,
                    subscribedReleases.map { it.releaseId },
                )
                return@withContext Result.success()
            }

            val fresh = subscribedReleases.filter { it.releaseId !in seenIds }
            if (fresh.isNotEmpty()) {
                NewReleaseNotificationManager.notifyNewReleases(applicationContext, fresh)

                NewReleaseNotificationManager.writeSeenReleaseIds(
                    applicationContext,
                    fresh.map { it.releaseId } + seenIds.toList(),
                )
            }

            Result.success()
        }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NewReleaseCheckWorkerEntryPoint {
    fun newReleaseDatabase(): MusicDatabase
}
