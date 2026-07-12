package io.github.zyrouge.symphony.services.search

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SemanticIndexingService : Service() {
    companion object {
        const val CHANNEL_ID = "semantic_indexing"
        const val NOTIFICATION_ID = 69001
        const val ACTION_CANCEL = "io.github.zyrouge.symphony.CANCEL_INDEXING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SemanticIndexingService::class.java)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            SemanticSearchEngine.activeInstance?.cancelIndexing()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification(0, 0, "Preparing…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        observeEngine()
        return START_NOT_STICKY
    }

    private fun observeEngine() {
        val engine = SemanticSearchEngine.activeInstance
        if (engine == null) {
            stopSelf()
            return
        }
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            engine.indexingState.collect { state ->
                if (!state.isActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifySafely(buildNotification(state.current, state.total, state.currentTitle))
                }
            }
        }
    }

    private fun notifySafely(notification: android.app.Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(current: Int, total: Int, title: String): android.app.Notification {
        val cancelIntent = Intent(this, SemanticIndexingService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPending = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Indexing songs for AI search")
            .setContentText(if (total > 0) "$current / $total — $title" else "Starting…")
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "Cancel", cancelPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID, "AI Indexing", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progress of semantic search indexing" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
