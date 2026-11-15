package com.appblocker.android_app_blocker

import android.content.Context
import org.json.JSONObject

/**
 * Everything the host app can customise about the blocking service and the
 * default native overlay. Persisted as JSON so the service can read it after a
 * process restart, without needing the Flutter engine to be alive.
 */
internal data class BlockerConfig(
    // --- Overlay content -------------------------------------------------
    val title: String = "App blocked",
    val message: String = "You asked to stay away from this app.",
    val primaryButtonText: String = "Go back",
    val secondaryButtonText: String? = "Open anyway",
    val iconResourceName: String? = null,

    // --- Overlay styling (ARGB ints) -------------------------------------
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val titleColor: Int = 0xFF101828.toInt(),
    val messageColor: Int = 0xFF667085.toInt(),
    val accentColor: Int = 0xFF4F46E5.toInt(),
    val primaryButtonTextColor: Int = 0xFFFFFFFF.toInt(),
    val secondaryButtonTextColor: Int = 0xFF667085.toInt(),

    // --- Overlay behaviour -----------------------------------------------
    /** Render the host app's Flutter widget instead of the native layout. */
    val useFlutterOverlay: Boolean = false,
    /** Dart entrypoint used when [useFlutterOverlay] is true. */
    val flutterOverlayEntrypoint: String = "appBlockerOverlayMain",
    /** Primary button opens the host app (true) or the launcher (false). */
    val primaryOpensHostApp: Boolean = true,

    // --- Service behaviour -----------------------------------------------
    val pollIntervalMs: Long = 250L,
    val notificationTitle: String = "App blocking active",
    val notificationText: String = "Monitoring apps you chose to block.",
    val notificationChannelName: String = "App blocking",
    val notificationChannelDescription: String = "Keeps app blocking running in the background.",
    val notificationIconResourceName: String? = null,
    val restartOnBoot: Boolean = true,

    /**
     * What to do when the Dart block-decision handler cannot answer (not
     * registered, threw, or timed out). "block", "allow" or "skip".
     * "skip" means: take no action this tick, do not show the overlay.
     */
    val decisionFailurePolicy: String = "block",
    val decisionTimeoutMs: Long = 6000L,
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("message", message)
        put("primaryButtonText", primaryButtonText)
        put("secondaryButtonText", secondaryButtonText ?: JSONObject.NULL)
        put("iconResourceName", iconResourceName ?: JSONObject.NULL)
        put("backgroundColor", backgroundColor)
        put("titleColor", titleColor)
        put("messageColor", messageColor)
        put("accentColor", accentColor)
        put("primaryButtonTextColor", primaryButtonTextColor)
        put("secondaryButtonTextColor", secondaryButtonTextColor)
        put("useFlutterOverlay", useFlutterOverlay)
        put("flutterOverlayEntrypoint", flutterOverlayEntrypoint)
        put("primaryOpensHostApp", primaryOpensHostApp)
        put("pollIntervalMs", pollIntervalMs)
        put("notificationTitle", notificationTitle)
        put("notificationText", notificationText)
        put("notificationChannelName", notificationChannelName)
        put("notificationChannelDescription", notificationChannelDescription)
        put("notificationIconResourceName", notificationIconResourceName ?: JSONObject.NULL)
        put("restartOnBoot", restartOnBoot)
        put("decisionFailurePolicy", decisionFailurePolicy)
        put("decisionTimeoutMs", decisionTimeoutMs)
    }.toString()

    companion object {
        private val DEFAULT = BlockerConfig()

        fun fromJson(raw: String?): BlockerConfig {
            if (raw.isNullOrBlank()) return DEFAULT
            return try {
                val j = JSONObject(raw)
                BlockerConfig(
                    title = j.optString("title", DEFAULT.title),
                    message = j.optString("message", DEFAULT.message),
                    primaryButtonText = j.optString("primaryButtonText", DEFAULT.primaryButtonText),
                    secondaryButtonText = j.optStringOrNull("secondaryButtonText"),
                    iconResourceName = j.optStringOrNull("iconResourceName"),
                    backgroundColor = j.optInt("backgroundColor", DEFAULT.backgroundColor),
                    titleColor = j.optInt("titleColor", DEFAULT.titleColor),
                    messageColor = j.optInt("messageColor", DEFAULT.messageColor),
                    accentColor = j.optInt("accentColor", DEFAULT.accentColor),
                    primaryButtonTextColor = j.optInt(
                        "primaryButtonTextColor",
                        DEFAULT.primaryButtonTextColor,
                    ),
                    secondaryButtonTextColor = j.optInt(
                        "secondaryButtonTextColor",
                        DEFAULT.secondaryButtonTextColor,
                    ),
                    useFlutterOverlay = j.optBoolean("useFlutterOverlay", DEFAULT.useFlutterOverlay),
                    flutterOverlayEntrypoint = j.optString(
                        "flutterOverlayEntrypoint",
                        DEFAULT.flutterOverlayEntrypoint,
                    ),
                    primaryOpensHostApp = j.optBoolean(
                        "primaryOpensHostApp",
                        DEFAULT.primaryOpensHostApp,
                    ),
                    pollIntervalMs = j.optLong("pollIntervalMs", DEFAULT.pollIntervalMs)
                        .coerceIn(50L, 5000L),
                    notificationTitle = j.optString("notificationTitle", DEFAULT.notificationTitle),
                    notificationText = j.optString("notificationText", DEFAULT.notificationText),
                    notificationChannelName = j.optString(
                        "notificationChannelName",
                        DEFAULT.notificationChannelName,
                    ),
                    notificationChannelDescription = j.optString(
                        "notificationChannelDescription",
                        DEFAULT.notificationChannelDescription,
                    ),
                    notificationIconResourceName = j.optStringOrNull("notificationIconResourceName"),
                    restartOnBoot = j.optBoolean("restartOnBoot", DEFAULT.restartOnBoot),
                    decisionFailurePolicy = j.optString(
                        "decisionFailurePolicy",
                        DEFAULT.decisionFailurePolicy,
                    ),
                    decisionTimeoutMs = j.optLong("decisionTimeoutMs", DEFAULT.decisionTimeoutMs)
                        .coerceIn(500L, 30_000L),
                )
            } catch (e: Exception) {
                DEFAULT
            }
        }

        fun fromMap(map: Map<*, *>): BlockerConfig {
            fun str(key: String, fallback: String): String =
                (map[key] as? String)?.takeIf { it.isNotEmpty() } ?: fallback

            fun strOrNull(key: String): String? = (map[key] as? String)?.takeIf { it.isNotEmpty() }
            fun int(key: String, fallback: Int): Int = (map[key] as? Number)?.toInt() ?: fallback
            fun long(key: String, fallback: Long): Long = (map[key] as? Number)?.toLong() ?: fallback
            fun bool(key: String, fallback: Boolean): Boolean = map[key] as? Boolean ?: fallback

            return BlockerConfig(
                title = str("title", DEFAULT.title),
                message = str("message", DEFAULT.message),
                primaryButtonText = str("primaryButtonText", DEFAULT.primaryButtonText),
                secondaryButtonText = strOrNull("secondaryButtonText"),
                iconResourceName = strOrNull("iconResourceName"),
                backgroundColor = int("backgroundColor", DEFAULT.backgroundColor),
                titleColor = int("titleColor", DEFAULT.titleColor),
                messageColor = int("messageColor", DEFAULT.messageColor),
                accentColor = int("accentColor", DEFAULT.accentColor),
                primaryButtonTextColor = int(
                    "primaryButtonTextColor",
                    DEFAULT.primaryButtonTextColor,
                ),
                secondaryButtonTextColor = int(
                    "secondaryButtonTextColor",
                    DEFAULT.secondaryButtonTextColor,
                ),
                useFlutterOverlay = bool("useFlutterOverlay", DEFAULT.useFlutterOverlay),
                flutterOverlayEntrypoint = str(
                    "flutterOverlayEntrypoint",
                    DEFAULT.flutterOverlayEntrypoint,
                ),
                primaryOpensHostApp = bool("primaryOpensHostApp", DEFAULT.primaryOpensHostApp),
                pollIntervalMs = long("pollIntervalMs", DEFAULT.pollIntervalMs)
                    .coerceIn(50L, 5000L),
                notificationTitle = str("notificationTitle", DEFAULT.notificationTitle),
                notificationText = str("notificationText", DEFAULT.notificationText),
                notificationChannelName = str(
                    "notificationChannelName",
                    DEFAULT.notificationChannelName,
                ),
                notificationChannelDescription = str(
                    "notificationChannelDescription",
                    DEFAULT.notificationChannelDescription,
                ),
                notificationIconResourceName = strOrNull("notificationIconResourceName"),
                restartOnBoot = bool("restartOnBoot", DEFAULT.restartOnBoot),
                decisionFailurePolicy = str("decisionFailurePolicy", DEFAULT.decisionFailurePolicy),
                decisionTimeoutMs = long("decisionTimeoutMs", DEFAULT.decisionTimeoutMs)
                    .coerceIn(500L, 30_000L),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key, "").takeIf { it.isNotEmpty() }
        }
    }

    /** Resolve a host-app drawable/mipmap by name, or 0 when unavailable. */
    fun resolveIcon(context: Context, name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val pkg = context.packageName
        val drawable = context.resources.getIdentifier(name, "drawable", pkg)
        if (drawable != 0) return drawable
        return context.resources.getIdentifier(name, "mipmap", pkg)
    }
}
