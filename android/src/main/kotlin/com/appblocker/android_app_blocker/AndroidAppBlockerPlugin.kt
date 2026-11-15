package com.appblocker.android_app_blocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

/** Dart-facing entry point for the plugin. */
class AndroidAppBlockerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, BlockerConstants.METHOD_CHANNEL)
        channel.setMethodCallHandler(this)
        BlockerStore.reload(context)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            // ---------------------------------------------------------- block list
            "block" -> {
                val packages = packagesArg(call) ?: return result.missing("packages")
                BlockerStore.block(context, packages)
                ensureServiceRunning()
                result.success(true)
            }

            "unblock" -> {
                val packages = packagesArg(call) ?: return result.missing("packages")
                BlockerStore.unblock(context, packages)
                if (BlockerStore.blockedPackages.isEmpty()) {
                    BlockerStore.stopService(context)
                } else {
                    BlockerStore.notifyServiceOfChange(context)
                }
                result.success(true)
            }

            "setBlockedApps" -> {
                val packages = packagesArg(call) ?: return result.missing("packages")
                BlockerStore.setBlocked(context, packages)
                if (packages.isEmpty()) {
                    BlockerStore.stopService(context)
                } else {
                    ensureServiceRunning()
                }
                result.success(true)
            }

            "clearBlockedApps" -> {
                BlockerStore.clear(context)
                BlockerStore.stopService(context)
                result.success(true)
            }

            "getBlockedApps" -> result.success(BlockerStore.blockedList(context))

            "isBlocked" -> {
                val packageName = call.argument<String>("packageName")
                    ?: return result.missing("packageName")
                BlockerStore.reload(context)
                result.success(BlockerStore.isBlocked(packageName))
            }

            // ---------------------------------------------------------- service
            "start" -> {
                if (!PermissionHelper.hasOverlayPermission(context) ||
                    !PermissionHelper.hasUsageStatsPermission(context)
                ) {
                    result.error(
                        "MISSING_PERMISSION",
                        "Overlay and usage access permissions are both required " +
                            "before blocking can start.",
                        null,
                    )
                    return
                }
                BlockerStore.startService(context)
                result.success(true)
            }

            "stop" -> {
                BlockerStore.stopService(context)
                result.success(true)
            }

            "isRunning" -> result.success(BlockerStore.isServiceEnabled(context))

            "hideOverlay" -> {
                try {
                    context.startService(
                        Intent(context, BlockerService::class.java).apply {
                            action = BlockerConstants.ACTION_HIDE_OVERLAY
                        },
                    )
                    result.success(true)
                } catch (e: Exception) {
                    result.success(false)
                }
            }

            // ---------------------------------------------------------- config
            "configure" -> {
                val raw = call.argument<Map<String, Any?>>("config")
                    ?: return result.missing("config")
                BlockerStore.saveConfig(context, BlockerConfig.fromMap(raw))
                BlockerStore.notifyServiceOfChange(context)
                result.success(true)
            }

            // ---------------------------------------------------------- decision handler
            "setDecisionHandler" -> {
                val dispatcher = (call.argument<Any>("dispatcherHandle") as? Number)?.toLong()
                val callback = (call.argument<Any>("callbackHandle") as? Number)?.toLong()
                if (dispatcher == null || callback == null) {
                    return result.missing("dispatcherHandle and callbackHandle")
                }
                BlockerStore.saveDecisionHandles(context, dispatcher, callback)
                BlockerStore.notifyServiceOfChange(context)
                result.success(true)
            }

            "clearDecisionHandler" -> {
                BlockerStore.clearDecisionHandles(context)
                BlockerStore.notifyServiceOfChange(context)
                result.success(true)
            }

            // ---------------------------------------------------------- permissions
            "checkPermissions" -> result.success(
                mapOf(
                    "overlay" to PermissionHelper.hasOverlayPermission(context),
                    "usageStats" to PermissionHelper.hasUsageStatsPermission(context),
                    "notifications" to PermissionHelper.hasNotificationPermission(context),
                ),
            )

            "requestOverlayPermission" ->
                result.success(PermissionHelper.requestOverlayPermission(activity, context))

            "requestUsageStatsPermission" ->
                result.success(PermissionHelper.requestUsageStatsPermission(activity, context))

            "openBatteryOptimisationSettings" ->
                result.success(PermissionHelper.openBatteryOptimisationSettings(activity, context))

            // ---------------------------------------------------------- installed apps
            "getInstalledApps" -> {
                val includeSystem = call.argument<Boolean>("includeSystemApps") ?: false
                val includeIcons = call.argument<Boolean>("includeIcons") ?: false
                result.success(installedApps(includeSystem, includeIcons))
            }

            "launchApp" -> {
                val packageName = call.argument<String>("packageName")
                    ?: return result.missing("packageName")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent == null) {
                    result.success(false)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
            }

            // ---------------------------------------------------------- usage
            "getUsageStats" -> {
                val packages = call.argument<List<String>>("packages")
                    ?: BlockerStore.blockedList(context)
                val days = call.argument<Int>("lookbackDays") ?: 30
                result.success(UsageTracker.statsFor(context, packages, days))
            }

            "resetUsageStats" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName.isNullOrEmpty()) {
                    UsageTracker.resetAll(context)
                } else {
                    UsageTracker.reset(context, packageName)
                }
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }

    // --------------------------------------------------------------------- helpers

    private fun ensureServiceRunning() {
        if (!PermissionHelper.hasOverlayPermission(context)) return
        if (!PermissionHelper.hasUsageStatsPermission(context)) return
        if (BlockerStore.blockedPackages.isEmpty()) return
        BlockerStore.startService(context)
    }

    private fun packagesArg(call: MethodCall): List<String>? {
        call.argument<List<String>>("packages")?.let { return it }
        call.argument<String>("packageName")?.let { return listOf(it) }
        return null
    }

    private fun MethodChannel.Result.missing(name: String) =
        error("INVALID_ARGS", "$name is required", null)

    private fun installedApps(
        includeSystem: Boolean,
        includeIcons: Boolean,
    ): List<Map<String, Any?>> {
        val pm = context.packageManager
        val launchable = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { info ->
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (!includeSystem && isSystem) return@filter false
                    if (info.packageName == context.packageName) return@filter false
                    includeSystem || launchable.contains(info.packageName)
                }
                .map { info ->
                    mapOf(
                        "packageName" to info.packageName,
                        "name" to pm.getApplicationLabel(info).toString(),
                        "isSystemApp" to ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                        "icon" to if (includeIcons) {
                            iconBytes(pm.getApplicationIcon(info))
                        } else {
                            null
                        },
                    )
                }
                .sortedBy { (it["name"] as String).lowercase() }
                .toList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun iconBytes(drawable: Drawable): ByteArray? = try {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }
}
