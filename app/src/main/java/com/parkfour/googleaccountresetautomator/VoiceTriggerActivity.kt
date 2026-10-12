package com.parkfour.googleaccountresetautomator

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible landing spot for the "Reset my account" app shortcut, so a Google Assistant Routine
 * (Google Home app > Assistant > Routines > custom starter phrase "reset my account" > action
 * "Open app" > this shortcut) can fire the manual trigger by voice without ever showing any UI.
 *
 * There's no way for the app to register a bare "OK Google, reset my account" phrase itself -
 * Assistant's App Actions only support fixed built-in-intent templates (which need the app name
 * in the phrase, e.g. "Open Reset my account in Google Account Reset Automator"). A Routine with
 * a custom starter phrase is the only way to get the exact bare phrase, and it has to be set up
 * by hand in the Google Home app since there's no API for a third-party app to create one.
 */
class VoiceTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent(AutomatorReceiver.ACTION_MANUAL_TRIGGER).setPackage(packageName))
        finish()
    }
}
