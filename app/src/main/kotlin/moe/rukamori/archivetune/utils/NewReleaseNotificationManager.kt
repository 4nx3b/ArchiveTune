/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SeenNewReleaseIdsKey
import java.util.concurrent.TimeUnit

/**
 * New-release notifications for subscribed artists (user request 2026-09-03:
 * "If i subscrib to an artist and a new album or song or ep or anything new
 * from that specific artist drops i should get a notification").
 *
 * The whole feature rides on the app's existing substrate, deliberately:
 *  - the subscription state is the SAME Room `artist.bookmarkedAt` row the
 *    artist page and the TikTok rail's follow badge toggle;
 *  - the release catalogue is the SAME `YouTube.newReleaseAlbums()` feed the
 *    New Releases screen lists (now fully paginated, so the catalogue is not
 *    truncated at the first ~200 entries);
 *  - the notify-once bookkeeping lives in DataStore as a bounded CSV of seen
 *    release IDs;
 *  - the schedule is a unique periodic WorkManager job with network + battery
 *    constraints, exactly like the app-update checker.
 *
 * First run is a silent BASELINE: every release currently in the catalogue is
 * marked seen without notifying, so installing the feature does not dump
 * dozens of notifications for releases that already existed. From the second
 * run on, only releases whose artist is subscribed AND whose ID has never
 * been seen produce a notification.
 */
object NewReleaseNotificationManager {
    private const val CHANNEL_ID = "new_release_notification_channel"
    private const val WORK_NAME = "new_release_check_work"
    private const val NOTIFICATION_ID_BASE = 9100

    /** Bounded size of the seen-ID CSV (newest first). */
    private const val SEEN_IDS_LIMIT = 500

    /** Never post more than this many notifications in one check. */
    private const val MAX_NOTIFICATIONS_PER_CHECK = 8

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.new_release_notification_channel_name)
            val descriptionText = context.getString(R.string.new_release_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel =
                NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun schedulePeriodicCheck(context: Context) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val request =
            PeriodicWorkRequestBuilder<NewReleaseCheckWorker>(
                12,
                TimeUnit.HOURS,
                6,
                TimeUnit.HOURS,
            ).setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // Same policy as the app-update checker: a changed interval (from
            // an app update) replaces the stored schedule; identical requests
            // are no-ops, and the periodic clock itself is never reset by
            // repeated enqueues.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodicCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * One release to surface. The artist match is pre-resolved by the worker
     * (the artist whose subscription matched, not necessarily the first
     * credited artist).
     */
    data class NewRelease(
        val releaseId: String,
        val title: String,
        val artistName: String,
    )

    suspend fun readSeenReleaseIds(context: Context): Set<String> {
        val raw = context.dataStore.data.map { it[SeenNewReleaseIdsKey] ?: "" }.first()
        if (raw.isBlank()) return emptySet()
        return raw.splitToSequence(',').filter { it.isNotBlank() }.toSet()
    }

    /**
     * Persists the seen set as a bounded, newest-first CSV. Newest first so
     * trimming drops the OLDEST ids — the ones least likely to still be in
     * the rotating catalogue.
     */
    suspend fun writeSeenReleaseIds(
        context: Context,
        seenIds: List<String>,
    ) {
        val bounded = seenIds.filter { it.isNotBlank() }.take(SEEN_IDS_LIMIT)
        context.dataStore.edit { prefs ->
            prefs[SeenNewReleaseIdsKey] = bounded.joinToString(",")
        }
    }

    /**
     * Posts one notification per release (deduped upstream by the worker).
     * Notification IDs derive from the release id hash so re-notifying the
     * same release replaces rather than stacks.
     */
    suspend fun notifyNewReleases(
        context: Context,
        releases: List<NewRelease>,
    ) {
        if (releases.isEmpty()) return
        createNotificationChannel(context)

        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("navigate_to", "new_release")
            }
        val openAppPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        releases.take(MAX_NOTIFICATIONS_PER_CHECK).forEach { release ->
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(
                        context.getString(
                            R.string.new_release_notification_title,
                            release.artistName,
                        ),
                    )
                    .setContentText(release.title)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openAppPendingIntent)
                    .setAutoCancel(true)
                    .build()

            val notificationId = NOTIFICATION_ID_BASE + (release.releaseId.hashCode() and 0xFFF)
            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
            } catch (security: SecurityException) {
                // Missing POST_NOTIFICATIONS permission — the feature degrades
                // to silent, the same way the update notification does.
            }
        }

        // More releases than the per-check budget: a summary notification so
        // nothing is silently dropped.
        if (releases.size > MAX_NOTIFICATIONS_PER_CHECK) {
            val summary =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(
                        context.getString(R.string.new_release_notification_more_title),
                    )
                    .setContentText(
                        context.getString(
                            R.string.new_release_notification_more_text,
                            releases.size - MAX_NOTIFICATIONS_PER_CHECK,
                        ),
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openAppPendingIntent)
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + 0xFFF, summary)
            } catch (security: SecurityException) {
                // Missing POST_NOTIFICATIONS permission.
            }
        }
    }

    /**
     * Cancels the posted notifications for the given release ids (the same
     * id derivation [notifyNewReleases] uses). Called when the user marks
     * releases read on the New Releases page (2026-09-05) so the system
     * notifications for those releases clear together with the page.
     */
    fun cancelNotifications(
        context: Context,
        releaseIds: Collection<String>,
    ) {
        if (releaseIds.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        releaseIds.forEach { releaseId ->
            val notificationId = NOTIFICATION_ID_BASE + (releaseId.hashCode() and 0xFFF)
            runCatching { manager.cancel(notificationId) }
        }
    }
}
