package com.appblocker.android_app_blocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Per-package counters the plugin maintains itself, merged with what Android's
 * [UsageStatsManager] already knows. No data leaves the device — the host app
 * reads these numbers over the method channel and decides what to do with them.
 */
internal object UsageTracker {
    private const val PREFS = "com.appblocker.android_app_blocker.usage"
    private const val KEY_PREFIX = "usage_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun mutate(context: Context, packageName: String, block: (JSONObject) -> Unit) {
        val p = prefs(context)
        val key = KEY_PREFIX + packageName
        val json = try {
            JSONObject(p.getString(key, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        block(json)
        p.edit().putString(key, json.toString()).apply()
    }

    fun recordOpened(context: Context, packageName: String) = mutate(context, packageName) {
        it.put("openCount", it.optInt("openCount", 0) + 1)
    }

    fun recordBlocked(context: Context, packageName: String) = mutate(context, packageName) {
        it.put("blockedCount", it.optInt("blockedCount", 0) + 1)
    }

    fun recordBypassed(context: Context, packageName: String) = mutate(context, packageName) {
        it.put("bypassedCount", it.optInt("bypassedCount", 0) + 1)
    }

    fun addBlockedTime(context: Context, packageName: String, durationMs: Long) {
        if (durationMs <= 0) return
        mutate(context, packageName) {
            it.put("blockedTimeMs", it.optLong("blockedTimeMs", 0) + durationMs)
        }
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun reset(context: Context, packageName: String) {
        prefs(context).edit().remove(KEY_PREFIX + packageName).apply()
    }

    fun statsFor(
        context: Context,
        packageName: String,
        lookbackDays: Int,
    ): Map<String, Any> {
        val json = try {
            JSONObject(prefs(context).getString(KEY_PREFIX + packageName, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

        var systemOpenCount = 0
        var systemForegroundMs = 0L
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - lookbackDays.coerceIn(1, 365) * 24L * 60 * 60 * 1000

            systemForegroundMs = usm
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
                ?.filter { it.packageName == packageName }
                ?.sumOf { it.totalTimeInForeground }
                ?: 0L

            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == packageName && isResumeEvent(event.eventType)) {
                    systemOpenCount++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return mapOf(
            "packageName" to packageName,
            "openCount" to systemOpenCount,
            "blockedCount" to json.optInt("blockedCount", 0),
            "bypassedCount" to json.optInt("bypassedCount", 0),
            "blockedTimeMs" to json.optLong("blockedTimeMs", 0),
            "foregroundTimeMs" to systemForegroundMs,
        )
    }

    fun statsFor(
        context: Context,
        packages: List<String>,
        lookbackDays: Int,
    ): List<Map<String, Any>> = packages.map { statsFor(context, it, lookbackDays) }

    private fun isResumeEvent(type: Int): Boolean =
        type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND
}
