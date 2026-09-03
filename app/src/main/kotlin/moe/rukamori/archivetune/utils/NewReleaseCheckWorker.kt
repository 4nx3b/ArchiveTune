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

/**
 * Periodic worker behind subscribed-artist new-release notifications. See
 * [NewReleaseNotificationManager] for the feature contract.
 *
 * The check is deliberately cheap to abandon: the subscribed-artist set is
 * read from Room FIRST, and a user with no subscriptions returns success
 * without a single network request. The catalogue fetch reuses
 * `YouTube.newReleaseAlbums()` — the same feed the New Releases screen
 * renders, now fully paginated past the old ~200-entry first page (which
 * also matters here: a truncated catalogue would miss the tail of the
 * subscribed artists' releases entirely).
 *
 * Entry-point method names are prefixed `newRelease` because Hilt generates
 * a single class implementing EVERY installed entry point — method names
 * shared with [moe.rukamori.archivetune.backup.ScheduledBackupWorkerEntryPoint]
 * or [moe.rukamori.archivetune.googledrive.GoogleDriveSyncWorkerEntryPoint]
 * with JVM-identical signatures would fail the build ("Found conflicting
 * entry point declarations").
 */
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
                    // Network/backend hiccup — retry on the next periodic tick
                    // rather than rescheduling a retry storm.
                    return@withContext Result.success()
                }
            if (albums.isEmpty()) return@withContext Result.success()

            val seenIds = NewReleaseNotificationManager.readSeenReleaseIds(applicationContext)

            // Releases from subscribed artists, in catalogue order (newest
            // first — the browse feed sorts that way).
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
                // First run: baseline the current catalogue WITHOUT notifying
                // (installing the feature must not dump every existing
                // release as a notification). Newest first, bounded.
                NewReleaseNotificationManager.writeSeenReleaseIds(
                    applicationContext,
                    subscribedReleases.map { it.releaseId },
                )
                return@withContext Result.success()
            }

            val fresh = subscribedReleases.filter { it.releaseId !in seenIds }
            if (fresh.isNotEmpty()) {
                NewReleaseNotificationManager.notifyNewReleases(applicationContext, fresh)
                // New ids first (they are the new head of the list), then the
                // ids we already knew, so trimming drops the oldest.
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
