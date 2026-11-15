package com.appblocker.android_app_blocker

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

internal object PermissionHelper {

    fun hasOverlayPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    fun hasUsageStatsPermission(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun requestOverlayPermission(activity: Activity?, context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(context)) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return launch(activity, context, intent, BlockerConstants.REQUEST_OVERLAY_PERMISSION)
    }

    fun requestUsageStatsPermission(activity: Activity?, context: Context): Boolean {
        if (hasUsageStatsPermission(context)) return true
        // Try to deep-link straight to this app's row; fall back to the list.
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (launch(activity, context, direct, BlockerConstants.REQUEST_USAGE_STATS_PERMISSION)) {
            return true
        }
        return launch(
            activity,
            context,
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            BlockerConstants.REQUEST_USAGE_STATS_PERMISSION,
        )
    }

    /** Vendor-specific "allow background start" / autostart screens, best-effort. */
    fun openBatteryOptimisationSettings(activity: Activity?, context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return launch(activity, context, intent, 0)
    }

    private fun launch(
        activity: Activity?,
        context: Context,
        intent: Intent,
        requestCode: Int,
    ): Boolean = try {
        if (activity != null && requestCode != 0) {
            activity.startActivityForResult(intent, requestCode)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        true
    } catch (e: Exception) {
        false
    }
}
