package io.github.zyrouge.symphony.services.radio

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.zyrouge.symphony.R
import io.github.zyrouge.symphony.Symphony

class RadioNotificationManager(val symphony: Symphony) {
    private var manager = NotificationManagerCompat.from(symphony.applicationContext)
    // notify() runs on background coroutines while service events arrive on the main thread.
    @Volatile
    private var lastNotification: Notification? = null
    /** The notification the service was last requested with; never cleared, so the service can always enter the foreground. */
    @Volatile
    private var startNotification: Notification? = null

    enum class State {
        PREPARING,
        READY,
        DESTROYED,
    }

    private var state = State.DESTROYED
    private val service: RadioNotificationService?
        get() = RadioNotificationService.instance
    private val hasService: Boolean
        get() = state == State.READY && service != null

    fun prepare() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                RadioNotification.CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW,
            ).run {
                setName(symphony.applicationContext.getString(R.string.app_name))
                setLightsEnabled(false)
                setVibrationEnabled(false)
                setShowBadge(false)
                build()
            }
        )
        RadioNotificationService.events.subscribe {
            when (it) {
                RadioNotificationService.Event.START -> onServiceStart()
                RadioNotificationService.Event.STOP -> onServiceStop()
            }
        }
    }

    fun cancel() {
        destroyNotification()
        RadioNotificationService.destroy()
    }

    fun notify(notification: Notification) {
        if (!hasService) {
            // Set before starting the service: it can start on the main thread before a later assignment
            // here would run, and would then find no notification to show.
            lastNotification = notification
            startNotification = notification
            createService()
            return
        }
        try {
            manager.notify(RadioNotification.NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // NOTE: the notification updates even without permission...
        }
    }

    private fun destroyNotification() {
        if (state == State.DESTROYED) {
            return
        }
        state = State.DESTROYED
        lastNotification = null
        manager.cancel(RadioNotification.CHANNEL_ID, RadioNotification.NOTIFICATION_ID)
    }

    private fun createService() {
        if (hasService || state == State.PREPARING) {
            return
        }
        val intent = Intent(symphony.applicationContext, RadioNotificationService::class.java)
        symphony.applicationContext.startForegroundService(intent)
        state = State.PREPARING
    }

    private fun onServiceStart() {
        // A second start (stop, then play again before the first start arrived) finds the service already
        // in the foreground; it must not be stopped then.
        val alreadyForeground = state == State.READY
        state = State.READY
        val running = service ?: return
        val pending = lastNotification
        lastNotification = null
        if (pending == null && alreadyForeground) return
        // After startForegroundService() Android requires startForeground() within a few seconds, or it kills
        // the app (ForegroundServiceDidNotStartInTimeException). If playback stopped before the service started,
        // nothing is pending: enter the foreground with the notification the service was requested with,
        // then stop straight away.
        val notification = pending ?: startNotification ?: return
        ServiceCompat.startForeground(
            running,
            RadioNotification.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        if (pending == null) {
            RadioNotificationService.destroy()
        }
    }

    private fun onServiceStop() {
        destroyNotification()
    }
}
