package com.parkfour.googleaccountresetautomator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * Drives the Google app's own account switcher UI (avatar -> expand -> account row) instead of
 * clearing app data. Requires the user to enable this service once under
 * Settings > Accessibility, since Android has no runtime-permission dialog for it.
 *
 * The top-bar avatar chip (resource-id "right_icons") is a Compose view with no exposed
 * click semantics or content description, so it can't be found/clicked like a normal node -
 * it's located by resource-id and tapped with a raw gesture instead. Everything after that
 * (the bento menu's "expand account list" header and the per-account rows) is a standard,
 * properly tagged Android view that responds to a normal ACTION_CLICK.
 *
 * isAccessibilityTool="true" is required in the service's XML config: without it, Android treats
 * the account-switcher screen as accessibility-data-sensitive and reports it as empty to us, even
 * though a privileged inspector (uiautomator) sees it fine.
 */
class AccountSwitchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RESET_AUTOMATOR_A11Y"
        private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val AVATAR_RESOURCE_ID = "right_icons"
        private const val TIMEOUT_MS = 12000L
        private const val AA_RESTART_TIMEOUT_MS = 15000L
        private const val POLL_INTERVAL_MS = 400L
        private const val ACTION_RETRY_COOLDOWN_MS = 1500L
        private const val SUCCESS_NOTIFICATION_ID = 4007
        private const val FAILURE_NOTIFICATION_ID = 4008
        private const val WAITING_UNLOCK_NOTIFICATION_ID = 4009

        @Volatile
        private var instance: AccountSwitchAccessibilityService? = null

        private data class PendingSwitch(val primaryEmail: String, val restartAndroidAuto: Boolean)

        // ponytail: no expiry on this - if the phone stays locked for the whole drive, it
        // still fires on whatever unlock comes next, however much later that is. Add a max-age
        // check if a stale switch firing on a much-later unlock turns out to be annoying.
        @Volatile
        private var pendingSwitch: PendingSwitch? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Kicks off the switch. Returns false immediately if the service isn't enabled
         * (nothing to drive the automation), true once the attempt has been queued.
         * When [restartAndroidAuto] is set, a successful switch is followed by force-stopping
         * and relaunching Android Auto so it picks up the new account - only worth doing for a
         * manual/test trigger, since a real car connection is already (re)starting it itself.
         *
         * A locked device (PIN/pattern/biometric) can't be driven by the accessibility service -
         * the Google app's window sits behind the keyguard and is never actually visible, so any
         * attempt just times out. Instead, wake the screen, prompt the user to unlock, and defer
         * the real switch until [resumePendingSwitchIfAny] sees them do it.
         */
        fun switchToPrimaryAccount(context: Context, primaryEmail: String, restartAndroidAuto: Boolean = false): Boolean {
            val service = instance ?: run {
                Log.e(TAG, "Accessibility service is not enabled; cannot automate account switch.")
                return false
            }
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguard.isKeyguardLocked) {
                Log.d(TAG, "Device locked; deferring switch to $primaryEmail until unlock.")
                pendingSwitch = PendingSwitch(primaryEmail, restartAndroidAuto)
                wakeScreenAndPromptUnlock(context.applicationContext)
                return true
            }
            service.beginSwitch(context.applicationContext, primaryEmail, restartAndroidAuto)
            return true
        }

        /** Called by DockWatcherService's ACTION_USER_PRESENT receiver once the device unlocks. */
        fun resumePendingSwitchIfAny(context: Context) {
            val pending = pendingSwitch ?: return
            pendingSwitch = null
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(WAITING_UNLOCK_NOTIFICATION_ID)
            // Android can stop the service while the phone sits locked and not restart it,
            // even though it's still switched on in Settings; only toggling it brings it back.
            val service = instance ?: run {
                Log.e(TAG, "Device unlocked, but the accessibility service isn't running; dropping deferred switch.")
                AutomatorReceiver.showServiceNotRunningNotification(context)
                return
            }
            Log.d(TAG, "Device unlocked; resuming deferred switch to ${pending.primaryEmail}.")
            service.beginSwitch(context.applicationContext, pending.primaryEmail, pending.restartAndroidAuto)
        }

        /** Briefly wakes the screen (no activity is on-screen to request it) and posts the unlock prompt. */
        private fun wakeScreenAndPromptUnlock(context: Context) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "GoogleAccountResetAutomator:UnlockPrompt"
            ).acquire(5000L)

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                AutomatorReceiver.CHANNEL_ID, "Automator Event Prompts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Handles immediate hardware action selections for Google accounts."
                enableLights(true)
            }
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(context, AutomatorReceiver.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Unlock to finish switching accounts")
                .setContentText("Your phone connected to the car - unlock it to complete the account switch.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(WAITING_UNLOCK_NOTIFICATION_ID, notification)
        }
    }

    private enum class Stage {
        TAPPING_THROUGH, AA_FINDING_FORCE_STOP, AA_CONFIRMING_STOP, DONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var deadlineMs = 0L
    private var running = false
    private var targetEmail = ""
    private var stage = Stage.TAPPING_THROUGH
    private var lastActionAtMs = 0L
    private var restartAndroidAutoRequested = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected.")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        running = false
        instance = null
        Log.d(TAG, "Accessibility service destroyed.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Querying windows/rootInActiveWindow from a background timer tick can return a stale
        // snapshot; re-check on every real event too so we always see the live tree.
        if (running) checkState()
    }

    override fun onInterrupt() {}

    private fun beginSwitch(context: Context, primaryEmail: String, restartAndroidAuto: Boolean) {
        handler.removeCallbacksAndMessages(null)
        targetEmail = primaryEmail.trim().lowercase()
        stage = Stage.TAPPING_THROUGH
        deadlineMs = SystemClock.elapsedRealtime() + TIMEOUT_MS
        lastActionAtMs = 0L
        restartAndroidAutoRequested = restartAndroidAuto
        running = true

        val launchIntent = context.packageManager.getLaunchIntentForPackage(GOOGLE_APP_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (launchIntent == null) {
            Log.e(TAG, "Could not resolve a launch intent for the Google app.")
            running = false
            return
        }
        Log.d(TAG, "Launching Google app to automate switch to $targetEmail")
        context.startActivity(launchIntent)
        handler.postDelayed({ checkState() }, 1200L)
    }

    private fun checkState() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now > deadlineMs) {
            running = false
            if (stage == Stage.AA_FINDING_FORCE_STOP || stage == Stage.AA_CONFIRMING_STOP) {
                Log.e(TAG, "Account switched, but gave up restarting Android Auto (stage=$stage).")
                showResultNotification(
                    success = true,
                    contentTitle = "Switched account, but Android Auto restart failed",
                    contentText = "Signed in as $targetEmail, but couldn't force-stop Android Auto in time. Restart it manually."
                )
            } else {
                Log.e(TAG, "Gave up: never completed account switch to $targetEmail (stage=$stage).")
                showResultNotification(success = false)
            }
            return
        }

        // The account switcher can open as its own window, so rootInActiveWindow alone isn't
        // reliable - search every window's root regardless of reported package, since a dialog
        // window's root package can differ from the hosting app's.
        val googleRoots = windows.mapNotNull { it.root }
        Log.d(TAG, "Poll: ${googleRoots.size} window roots -> ${googleRoots.map { it.packageName }}")

        if (googleRoots.isEmpty()) {
            scheduleNextCheck()
            return
        }

        // Always check first: the target account row may already be visible (e.g. the
        // account list was left expanded from a previous run).
        if (stage == Stage.TAPPING_THROUGH) {
            val targetRow = googleRoots.firstNotNullOfOrNull { findNodeContainingText(it, targetEmail) }
            if (targetRow != null) {
                Log.d(TAG, "Found account row for $targetEmail, tapping it.")
                clickNode(targetRow)
                if (restartAndroidAutoRequested) {
                    Log.d(TAG, "Account switched; moving on to restart Android Auto.")
                    stage = Stage.AA_FINDING_FORCE_STOP
                    deadlineMs = now + AA_RESTART_TIMEOUT_MS
                    lastActionAtMs = 0L
                    showResultNotification(
                        success = true,
                        contentText = "Now signed in as $targetEmail. Restarting Android Auto…"
                    )
                    openAndroidAutoAppInfo()
                    scheduleNextCheck()
                } else {
                    stage = Stage.DONE
                    running = false
                    showResultNotification(success = true)
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 1000L)
                }
                return
            }
        }

        val cooledDown = now - lastActionAtMs > ACTION_RETRY_COOLDOWN_MS

        when (stage) {
            Stage.TAPPING_THROUGH -> {
                // "expand account list" only ever appears on the header once the avatar has
                // genuinely been tapped open - unlike the avatar's own content description
                // ("Signed in as X. Account and settings."), which is present at all times,
                // tapped or not, and so can't be used as a signal that the menu is open.
                val expandNode = googleRoots.firstNotNullOfOrNull { findNodeContainingText(it, "expand account list") }
                Log.d(TAG, "TAPPING_THROUGH poll: expandNode=${expandNode != null} cooledDown=$cooledDown")
                if (expandNode != null && cooledDown) {
                    Log.d(TAG, "Expanding account list.")
                    clickNode(expandNode)
                    lastActionAtMs = now
                } else if (expandNode == null && cooledDown) {
                    val avatar = googleRoots.firstNotNullOfOrNull { findNodeByResourceId(it, AVATAR_RESOURCE_ID) }
                    if (avatar != null) {
                        val bounds = Rect()
                        avatar.getBoundsInScreen(bounds)
                        Log.d(TAG, "Tapping account avatar at (${bounds.centerX()}, ${bounds.centerY()})")
                        tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        lastActionAtMs = now
                    }
                }
            }
            Stage.AA_FINDING_FORCE_STOP -> {
                val forceStopNode = googleRoots.firstNotNullOfOrNull { findNodeContainingText(it, "force stop") }
                Log.d(TAG, "AA_FINDING_FORCE_STOP poll: found=${forceStopNode != null} cooledDown=$cooledDown")
                if (forceStopNode != null && cooledDown) {
                    Log.d(TAG, "Tapping Force stop.")
                    clickNode(forceStopNode)
                    lastActionAtMs = now
                    stage = Stage.AA_CONFIRMING_STOP
                }
            }
            Stage.AA_CONFIRMING_STOP -> {
                val okNode = googleRoots.firstNotNullOfOrNull { findNodeByExactText(it, "OK") }
                Log.d(TAG, "AA_CONFIRMING_STOP poll: found=${okNode != null} cooledDown=$cooledDown")
                if (okNode != null && cooledDown) {
                    Log.d(TAG, "Confirming force stop; Android Auto will relaunch shortly.")
                    clickNode(okNode)
                    stage = Stage.DONE
                    running = false
                    handler.postDelayed({ relaunchAndroidAuto() }, 800L)
                }
            }
            Stage.DONE -> {}
        }

        scheduleNextCheck()
    }

    private fun scheduleNextCheck() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ checkState() }, POLL_INTERVAL_MS)
    }

    /** Reuses AutomatorReceiver's channel so both trigger and result notifications land together. */
    private fun showResultNotification(success: Boolean, contentTitle: String? = null, contentText: String? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            AutomatorReceiver.CHANNEL_ID,
            "Automator Event Prompts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Handles immediate hardware action selections for Google accounts."
            enableLights(true)
        }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, AutomatorReceiver.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentTitle ?: if (success) "Switched to primary account" else "Account switch failed")
            .setContentText(
                contentText ?: if (success) "Now signed in as $targetEmail."
                else "Couldn't find the account row for $targetEmail in time. Switch manually in the Google app."
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(if (success) SUCCESS_NOTIFICATION_ID else FAILURE_NOTIFICATION_ID, notification)
    }

    private fun openAndroidAutoAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", ANDROID_AUTO_PACKAGE, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.d(TAG, "Opening Android Auto's App Info page to force stop it.")
        startActivity(intent)
    }

    private fun relaunchAndroidAuto() {
        val launchIntent = packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (launchIntent == null) {
            Log.e(TAG, "Could not resolve a launch intent for Android Auto.")
            return
        }
        Log.d(TAG, "Relaunching Android Auto.")
        startActivity(launchIntent)
    }

    /** Filters out off-screen duplicate nodes (e.g. an adjacent pager page Compose keeps composed). */
    private fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val metrics = resources.displayMetrics
        return bounds.left < metrics.widthPixels && bounds.right > 0 &&
            bounds.top < metrics.heightPixels && bounds.bottom > 0
    }

    private fun findNodeByResourceId(node: AccessibilityNodeInfo, idContains: String): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName
        if (id != null && id.contains(idContains) && node.isVisibleToUser && isOnScreen(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByResourceId(child, idContains)?.let { return it }
        }
        return null
    }

    private fun findNodeContainingText(node: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        if ((text?.contains(needle) == true || desc?.contains(needle) == true) &&
            node.isVisibleToUser && isOnScreen(node)
        ) {
            return findClickableSelfOrAncestor(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeContainingText(child, needle)?.let { return it }
        }
        return null
    }

    /** Exact (not substring) match, for short system-dialog button labels like "OK" or "Cancel". */
    private fun findNodeByExactText(node: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()
        if (text != null && text.equals(needle, ignoreCase = true) && node.isVisibleToUser && isOnScreen(node)) {
            return findClickableSelfOrAncestor(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByExactText(child, needle)?.let { return it }
        }
        return null
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** The avatar chip has no exposed click semantics, so it needs a raw touch gesture. */
    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
