package com.parkfour.googleaccountresetautomator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

class AutomatorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RESET_AUTOMATOR"
        const val CHANNEL_ID = "automator_alerts_channel"
        private const val ACCESSIBILITY_HINT_NOTIFICATION_ID = 4005

        // Sent explicitly by DockWatcherService once it confirms a real dock connection -
        // ACTION_DOCK_EVENT itself can't be caught here directly (see DockWatcherService).
        const val ACTION_DOCK_DETECTED  = "com.parkfour.intent.action.DOCK_DETECTED"
        const val ACTION_MANUAL_TRIGGER = "com.parkfour.intent.action.MANUAL_TRIGGER"

        /**
         * Shown when a switch can't run because the Accessibility Service isn't running: either
         * it's off in Settings, or Android stopped it and didn't restart it (which only turning it
         * off and on again fixes). Android has no way to request or restart it at runtime.
         */
        fun showServiceNotRunningNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Automator Event Prompts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Handles immediate hardware action selections for Google accounts."
                enableLights(true)
            }
            manager.createNotificationChannel(channel)

            val settingsIntent = PendingIntent.getActivity(
                context,
                4,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Accessibility Service isn't running")
                .setContentText("Couldn't switch accounts. Tap to open Settings, then turn the service off and on again.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(settingsIntent)
                .build()

            manager.notify(ACCESSIBILITY_HINT_NOTIFICATION_ID, notification)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Intercepted Action: $action")

        if (BluetoothDevice.ACTION_ACL_CONNECTED != action &&
            ACTION_DOCK_DETECTED != action &&
            ACTION_MANUAL_TRIGGER != action) {
            return
        }

        // Match by MAC address, not device name: names aren't guaranteed unique, can be
        // renamed by the user later, or can come back null on some ACL_CONNECTED extras.
        val sharedPrefs = context.getSharedPreferences("AutomatorPrefs", Context.MODE_PRIVATE)
        val savedTargetAddress = sharedPrefs.getString("TARGET_BT_ADDRESS", null)

        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        @Suppress("MissingPermission")
        val connectedDeviceAddress = device?.address

        val isTargetDevice = savedTargetAddress != null &&
            connectedDeviceAddress?.equals(savedTargetAddress, ignoreCase = true) == true

        // Verify if we should act based on your matching car rules or a simulation flag
        if (!isTargetDevice && ACTION_DOCK_DETECTED != action && ACTION_MANUAL_TRIGGER != action) {
            return
        }

        val savedPrimaryEmail = sharedPrefs.getString("PRIMARY_EMAIL", "") ?: ""
        if (savedPrimaryEmail.isEmpty()) {
            Log.e(TAG, "Auto-switch aborted: primary email not configured.")
            return
        }

        Log.d(TAG, "Hardware link verified. Auto-switching to primary account...")
        val started = AccountSwitchAccessibilityService.switchToPrimaryAccount(
            context,
            savedPrimaryEmail,
            restartAndroidAuto = action == ACTION_MANUAL_TRIGGER
        )
        if (!started) showServiceNotRunningNotification(context)
    }
}
