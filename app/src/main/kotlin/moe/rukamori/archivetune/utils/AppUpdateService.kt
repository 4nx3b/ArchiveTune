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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import java.io.File

class AppUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                downloadJob?.cancel()
                downloadJob = null
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
                }
                stopSelf()
            }

            else -> {
                val url = intent?.getStringExtra(EXTRA_DOWNLOAD_URL).orEmpty()
                val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
                if (url.isBlank()) {
                    stopSelf()
                } else {
                    startDownload(url, version)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        downloadJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload(url: String, version: String) {
        downloadJob?.cancel()
        createChannel(this)
        startInForegroundProgress(version, indeterminate = true, percent = 0)

        downloadJob =
            scope.launch {
                val result = AppUpdateInstaller.download(this@AppUpdateService, url) { progress ->
                    val fraction = progress.fraction
                    if (fraction != null) {
                        updateProgressNotification(version, percent = (fraction * 100).toInt(), indeterminate = false)
                    } else {
                        updateProgressNotification(version, percent = 0, indeterminate = true)
                    }
                }

                val apkFile = result.getOrNull()
                if (apkFile != null && apkFile.isFile) {
                    runCatching { AppUpdateInstaller.installApk(this@AppUpdateService, apkFile) }
                    showCompletedNotification(version, apkFile)
                } else {
                    showFailedNotification(result.exceptionOrNull()?.message)
                }
                stopSelf()
            }
    }

    private fun openUpdateScreenPendingIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("navigate_to", "settings/update")
            }
        return PendingIntent.getActivity(
            this,
            OPEN_UPDATE_SCREEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, AppUpdateService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setOnlyAlertOnce(true)

    private fun startInForegroundProgress(version: String, indeterminate: Boolean, percent: Int) {
        val notification =
            baseBuilder()
                .setContentTitle(getString(R.string.update_download_notification_title))
                .setContentText(progressText(version, percent))
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setContentIntent(openUpdateScreenPendingIntent())
                .addAction(R.drawable.close, getString(R.string.cancel_button), cancelPendingIntent())
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateProgressNotification(version: String, percent: Int, indeterminate: Boolean) {
        val notification =
            baseBuilder()
                .setContentTitle(getString(R.string.update_download_notification_title))
                .setContentText(progressText(version, percent))
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setContentIntent(openUpdateScreenPendingIntent())
                .addAction(R.drawable.close, getString(R.string.cancel_button), cancelPendingIntent())
                .build()
        notifySafely(notification)
    }

    private fun showCompletedNotification(version: String, apkFile: File) {
        val notification =
            baseBuilder()
                .setContentTitle(getString(R.string.update_download_complete_title))
                .setContentText(
                    getString(
                        R.string.update_download_complete_text,
                        version.ifBlank { getString(R.string.update_notification_channel_name) },
                    ),
                )
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(AppUpdateInstaller.installPendingIntent(this, apkFile))
                .build()
        notifySafely(notification)
    }

    private fun showFailedNotification(message: String?) {
        val notification =
            baseBuilder()
                .setContentTitle(getString(R.string.update_download_failed))
                .setContentText(message ?: getString(R.string.error_unknown))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openUpdateScreenPendingIntent())
                .build()
        notifySafely(notification)
    }

    private fun progressText(version: String, percent: Int): String =
        if (version.isBlank()) {
            getString(R.string.update_download_progress_text, percent)
        } else {
            getString(R.string.update_download_progress_text_with_version, version, percent)
        }

    private fun notifySafely(notification: android.app.Notification) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "update_download_channel"
        private const val NOTIFICATION_ID = 9999
        private const val ACTION_CANCEL = "moe.rukamori.archivetune.action.CANCEL_UPDATE_DOWNLOAD"
        private const val EXTRA_DOWNLOAD_URL = "download_url"
        private const val EXTRA_VERSION = "version"
        private const val OPEN_UPDATE_SCREEN_REQUEST_CODE = 0
        private const val CANCEL_REQUEST_CODE = 1

        fun isSupported(): Boolean = BuildConfig.UPDATER_AVAILABLE && BuildConfig.DISTRIBUTION == "gms"

        fun startPendingIntent(
            context: Context,
            downloadUrl: String,
            version: String,
        ): PendingIntent {
            val intent =
                Intent(context, AppUpdateService::class.java)
                    .putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                    .putExtra(EXTRA_VERSION, version)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    START_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    context,
                    START_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        private const val START_REQUEST_CODE = 2

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.update_download_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.update_download_channel_desc)
                    }
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }
    }
}
