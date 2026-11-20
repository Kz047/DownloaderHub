package com.kyz.downloaderhub

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.ConcurrentHashMap

// Remembers which downloads are running and how far they are.
// MainActivity reports to it; the notification reads from it.
object DownloadTracker {
    private val active = ConcurrentHashMap<String, Float>()          // processId -> percent (-1 = not known yet)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var serviceRunning = false
    @Volatile private var lastPush = 0L

    fun begin(ctx: Context, processId: String) {
        active[processId] = -1f
        if (!serviceRunning) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
                serviceRunning = true
            } catch (e: Exception) {
                // Downloads still work, there just is no notification
                Log.e("DLHUB", "could not start the download service", e)
            }
        } else {
            DownloadService.update(ctx)
        }
    }

    fun progress(ctx: Context, processId: String, percent: Float) {
        active[processId] = percent
        val now = System.currentTimeMillis()
        if (now - lastPush < 800) return
        lastPush = now
        DownloadService.update(ctx)
    }

    fun end(ctx: Context, processId: String) {
        active.remove(processId)
        if (active.isEmpty()) {
            // If the service has not reached startForeground() yet, stopping it now would crash the app
            // ("did not then call Service.startForeground()"). In that case the service stops itself as
            // soon as it starts and sees that nothing is running.
            if (DownloadService.inForeground) {
                ctx.stopService(Intent(ctx, DownloadService::class.java))
                serviceRunning = false
            }
            cancelled.clear()
        } else {
            DownloadService.update(ctx)
        }
    }

    fun cancelAll() {
        for (id in active.keys.toList()) {
            cancelled.add(id)
            try {
                YoutubeDL.getInstance().destroyProcessById(id)
            } catch (e: Exception) {
                Log.e("DLHUB", "cancel failed", e)
            }
        }
    }

    // Cancels a single download (the in-app progress card's cancel button)
    fun cancelOne(processId: String) {
        cancelled.add(processId)
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            Log.e("DLHUB", "cancel failed", e)
        }
    }

    fun wasCancelled(processId: String) = cancelled.contains(processId)
    fun count() = active.size

    // -1 when no running download has reported a percentage yet
    fun overallPercent(): Int {
        val known = active.values.filter { it >= 0f }
        if (known.isEmpty()) return -1
        return known.average().toInt().coerceIn(0, 100)
    }

    fun serviceStopped() { serviceRunning = false }
}

// Shows "Downloading..." with a progress bar and a Cancel button, and keeps the app alive meanwhile.
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadTracker.cancelAll()
            if (DownloadTracker.count() == 0) stopSelf()      // nothing left to cancel: do not leave an idle service behind
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification = build(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        inForeground = true
        if (DownloadTracker.count() == 0) stopSelf()          // the download already ended while we were starting
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inForeground = false
        DownloadTracker.serviceStopped()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 4201
        const val ACTION_CANCEL = "com.kyz.downloaderhub.CANCEL_DOWNLOADS"
        @Volatile var inForeground = false

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun build(ctx: Context): Notification {
            val n = DownloadTracker.count()
            val pct = DownloadTracker.overallPercent()

            val cancel = PendingIntent.getService(
                ctx, 0,
                Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val open = PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (n > 1) "Downloading $n files" else "Downloading")
                .setContentText(if (pct >= 0) "$pct%" else "Starting...")
                .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
                .build()
        }

        // Refreshes the notification while the service is running
        fun update(ctx: Context) {
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx))
            } catch (e: Exception) {
                Log.e("DLHUB", "notification update failed", e)
            }
        }
    }
}