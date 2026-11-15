package com.appblocker.android_app_blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the blocking service after a reboot, but only if the host app had it
 * running and the user has not revoked the required permissions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val app = context.applicationContext
        if (!BlockerStore.isServiceEnabled(app)) return
        if (!BlockerStore.loadConfig(app).restartOnBoot) return
        if (!PermissionHelper.hasUsageStatsPermission(app)) return
        if (!PermissionHelper.hasOverlayPermission(app)) return

        BlockerStore.reload(app)
        if (BlockerStore.blockedPackages.isEmpty()) return

        BlockerStore.startService(app)
    }
}
