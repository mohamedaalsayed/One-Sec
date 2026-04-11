package com.onesec.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires on boot. The AccessibilityService is managed by Android itself
 * (it auto-restarts if enabled), but this receiver ensures any app-level
 * state is clean and ready after a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Accessibility services are re-enabled by the system automatically.
            // Nothing extra needed — just having this receiver keeps the app
            // alive in the system's "persistent apps" list on some OEMs.
        }
    }
}
