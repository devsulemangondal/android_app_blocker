package com.appblocker.android_app_blocker

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent state for the blocker. Deliberately SharedPreferences-only so the
 * foreground service can read everything it needs without a Flutter engine.
 */
internal object BlockerStore {
    private const val KEY_BLOCKED = "blocked_packages"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_DISPATCHER_HANDLE = "decision_dispatcher_handle"
    private const val KEY_CALLBACK_HANDLE = "decision_callback_handle"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    /** In-memory mirror of the blocked set, refreshed periodically by the service. */
    val blockedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(BlockerConstants.PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ blocked set

    fun reload(context: Context) {
        val stored = prefs(context).getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        synchronized(blockedPackages) {
            blockedPackages.clear()
            blockedPackages.addAll(stored)
        }
    }

    private fun persist(context: Context) {
        prefs(context).edit()
            .putStringSet(KEY_BLOCKED, HashSet(blockedPackages))
            .apply()
    }

    fun block(context: Context, packages: List<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        blockedPackages.addAll(cleaned)
        persist(context)
    }

    fun unblock(context: Context, packages: List<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        blockedPackages.removeAll(cleaned.toSet())
        persist(context)
    }

    fun setBlocked(context: Context, packages: List<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        synchronized(blockedPackages) {
            blockedPackages.clear()
            blockedPackages.addAll(cleaned)
        }
        persist(context)
    }

    fun clear(context: Context) {
        blockedPackages.clear()
        persist(context)
    }

    fun isBlocked(packageName: String): Boolean = blockedPackages.contains(packageName)

    fun blockedList(context: Context): List<String> {
        reload(context)
        return blockedPackages.sorted()
    }

    // ------------------------------------------------------------------ config

    fun saveConfig(context: Context, config: BlockerConfig) {
        prefs(context).edit().putString(KEY_CONFIG, config.toJson()).apply()
    }

    fun loadConfig(context: Context): BlockerConfig =
        BlockerConfig.fromJson(prefs(context).getString(KEY_CONFIG, null))

    // ------------------------------------------------------------------ decision callback

    fun saveDecisionHandles(context: Context, dispatcher: Long, callback: Long) {
        prefs(context).edit()
            .putLong(KEY_DISPATCHER_HANDLE, dispatcher)
            .putLong(KEY_CALLBACK_HANDLE, callback)
            .apply()
    }

    fun clearDecisionHandles(context: Context) {
        prefs(context).edit()
            .remove(KEY_DISPATCHER_HANDLE)
            .remove(KEY_CALLBACK_HANDLE)
            .apply()
    }

    fun dispatcherHandle(context: Context): Long? =
        prefs(context).getLong(KEY_DISPATCHER_HANDLE, 0L).takeIf { it != 0L }

    fun callbackHandle(context: Context): Long? =
        prefs(context).getLong(KEY_CALLBACK_HANDLE, 0L).takeIf { it != 0L }

    fun hasDecisionHandler(context: Context): Boolean =
        dispatcherHandle(context) != null && callbackHandle(context) != null

    // ------------------------------------------------------------------ service lifecycle

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)

    fun startService(context: Context) {
        setServiceEnabled(context, true)
        val intent = Intent(context, BlockerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopService(context: Context) {
        setServiceEnabled(context, false)
        try {
            context.stopService(Intent(context, BlockerService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Nudge a running service to re-read config without restarting it. */
    fun notifyServiceOfChange(context: Context) {
        if (!isServiceEnabled(context)) return
        val intent = Intent(context, BlockerService::class.java).apply {
            action = BlockerConstants.ACTION_REFRESH
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
