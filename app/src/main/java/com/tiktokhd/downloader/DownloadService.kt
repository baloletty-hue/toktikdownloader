package com.tiktokhd.downloader

import android.app.Notification
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service so the download survives the user leaving the app.
 */
class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)

        startAsForeground(notification("Starting.", null))

        if (url.isNullOrBlank()) {
            fail("No TikTok URL received.")
            return START_NOT_STICKY
        }

        if (job?.isActive == true) {
            // Keep the running download; just tell the user.
            return START_NOT_STICKY
        }

        job = scope.launch {
            try {
                DownloadState.set(Status.ReceivingUrl)
                update("Receiving URL.", null)

                val resolved = Downloader.resolveUrl(url)
                val info = Downloader.videoInfo(resolved)

                if (Storage.exists(this@DownloadService, info.fileName)) {
                    val path = "Downloads/TikTok/${info.fileName}"
                    DownloadState.set(Status.AlreadyExists(path))
                    finishWith("File already exists", info.fileName)
                    return@launch
                }

                DownloadState.set(Status.GettingLink)
                update("Getting HD link.", null)

                val hd = Downloader.hdUrl(resolved)

                val target = Storage.create(this@DownloadService, info.fileName)
                try {
                    target.open().use { out ->
                        Downloader.fetchTo(hd, out) { done, total ->
                            DownloadState.set(Status.Downloading(done, total))
                            val pct = if (total > 0) ((done * 100) / total).toInt() else null
                            update("Downloading ${info.fileName}", pct)
                        }
                    }
                    target.finish()
                } catch (e: Throwable) {
                    target.abort()
                    throw e
                }

                DownloadState.set(Status.Completed(target.displayPath))
                finishWith("Completed", info.fileName)
            } catch (e: Downloader.DownloadError) {
                fail(e.message ?: "Unknown error.")
            } catch (e: Throwable) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------- notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notification(text: String, percent: Int?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
        when {
            percent != null -> b.setProgress(100, percent, false)
            else -> b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun update(text: String, percent: Int?) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification(text, percent))
        }
    }

    private fun finalNotification(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        stopForegroundCompat()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID + 1, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun finishWith(title: String, name: String) {
        finalNotification(title, name)
        stopSelf()
    }

    private fun fail(reason: String) {
        DownloadState.set(Status.Error(reason))
        finalNotification("Error", reason)
        stopSelf()
    }
}
