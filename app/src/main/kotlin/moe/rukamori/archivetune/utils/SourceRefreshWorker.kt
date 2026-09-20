/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import timber.log.Timber

class SourceRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val instancesWanted =
            context.dataStore.get(TidalEnabledKey, true) || context.dataStore.get(QobuzEnabledKey, false)

        return try {
            if (instancesWanted) {

                val records = TidalInstanceHealthManager.refresh(context, includeDiscovery = false, staggered = true)
                Timber.tag(TAG).d("Instance refresh done: %d healthy of %d", records.count { it.isHealthy }, records.size)
            }
            PoolAccountManager.refresh(context)
            Result.success()
        } catch (error: Throwable) {

            Timber.tag(TAG).w(error, "Source refresh failed; will retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SourceRefresh"
        private const val WORK_NAME = "source_instance_refresh"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SourceRefreshWorker>(REFRESH_INTERVAL.inWholeHours, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private val REFRESH_INTERVAL = 6.hours
    }
}
