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

object NewReleaseNotificationManager {
    private const val CHANNEL_ID = "new_release_notification_channel"
    private const val WORK_NAME = "new_release_check_work"
    private const val NOTIFICATION_ID_BASE = 9100

    private const val SEEN_IDS_LIMIT = 500

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

            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodicCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

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

    suspend fun writeSeenReleaseIds(
        context: Context,
        seenIds: List<String>,
    ) {
        val bounded = seenIds.filter { it.isNotBlank() }.take(SEEN_IDS_LIMIT)
        context.dataStore.edit { prefs ->
            prefs[SeenNewReleaseIdsKey] = bounded.joinToString(",")
        }
    }

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

            }
        }

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

            }
        }
    }

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
