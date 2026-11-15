package com.appblocker.android_app_blocker

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Resolves the package currently in the foreground from [UsageStatsManager].
 *
 * Only RESUMED/MOVE_TO_FOREGROUND events are considered. PAUSED events are
 * deliberately ignored: with gesture navigation a blocked app can emit PAUSED
 * before the launcher emits RESUMED, which would otherwise leave the overlay
 * stuck on screen.
 */
internal class ForegroundAppDetector(private val context: Context) {

    companion object {
        /** Launchers, recents and system UI — never block these. */
        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.quickstep",
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )

        private val LAUNCHER_HINTS = listOf(
            "launcher",
            "home",
            "trebuchet",
        )
    }

    private var cachedLaunchers: Set<String>? = null

    fun current(shortWindow: Boolean): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val windowMs = if (shortWindow) 5_000L else 30_000L
        val events = usm.queryEvents(now - windowMs, now)
        val event = UsageEvents.Event()
        var lastResumed: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                lastResumed = event.packageName
            }
        }
        lastResumed ?: usm
            .queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000, now)
            ?.filter { it.lastTimeUsed > 0 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /** True for launchers, recents and system UI, where the overlay must hide. */
    fun isSystemSurface(packageName: String): Boolean {
        if (SYSTEM_PACKAGES.contains(packageName)) return true
        if (launchers().contains(packageName)) return true
        // Covers OEM launchers we could not enumerate (restricted package visibility).
        val lower = packageName.lowercase()
        return LAUNCHER_HINTS.any { lower.contains(it) } && lower.startsWith("com.")
    }

    private fun launchers(): Set<String> {
        cachedLaunchers?.let { return it }
        val resolved = try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
            }
            context.packageManager
                .queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        cachedLaunchers = resolved
        return resolved
    }
}
