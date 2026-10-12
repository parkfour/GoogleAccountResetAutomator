package com.parkfour.googleaccountresetautomator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * ACTION_DOCK_EVENT is a sticky system broadcast, but it's not on Android's implicit-broadcast
 * exemption list (unlike ACTION_ACL_CONNECTED), so a manifest-declared receiver never gets it on
 * API 26+ - the registration is silently dropped. The only way to receive it is a live,
 * dynamically-registered receiver, which requires a component that's actually running - hence this
 * always-on foreground service. It just re-fires a real dock connection at AutomatorReceiver
 * through an explicit intent, reusing the same notification/decision flow as the Bluetooth trigger.
 *
 * ACTION_USER_PRESENT has the same implicit-broadcast restriction, so it rides along on the same
 * always-on receiver to resume any account switch that got deferred behind a locked screen - see
 * AccountSwitchAccessibilityService.resumePendingSwitchIfAny.
 */
class DockWatcherService : Service() {

    companion object {
        private const val TAG = "RESET_AUTOMATOR_DOCK"
        private const val CHANNEL_ID = "dock_watcher_channel"
        private const val NOTIFICATION_ID = 4006
    }

    private val dockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_DOCK_EVENT -> {
                    val dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED)
                    Log.d(TAG, "Dock state changed: $dockState")
                    if (dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                        context.sendBroadcast(
                            Intent(context, AutomatorReceiver::class.java)
                                .setAction(AutomatorReceiver.ACTION_DOCK_DETECTED)
                        )
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present (device unlocked).")
                    AccountSwitchAccessibilityService.resumePendingSwitchIfAny(context)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        ContextCompat.registerReceiver(
            this,
            dockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DOCK_EVENT)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.d(TAG, "Dock watcher started.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(dockReceiver)
        Log.d(TAG, "Dock watcher stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Dock Detection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps watching for the tablet being placed on its dock."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Waiting for connection")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}
