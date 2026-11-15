package com.appblocker.android_app_blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that watches which app is in the foreground and shows the
 * block screen over any package the host app has blocked.
 *
 * The service performs no network I/O. When a block-decision handler is
 * registered it asks the host app's Dart code (via [DecisionBridge]) and
 * otherwise relies purely on the locally stored block list.
 */
class BlockerService : Service() {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var detector: ForegroundAppDetector
    private var decisionBridge: DecisionBridge? = null
    private var config: BlockerConfig = BlockerConfig()

    private var overlayView: FrameLayout? = null
    private var overlayEngine: FlutterEngine? = null
    private var overlayFlutterView: FlutterView? = null
    private var overlayShowing = false
    private var overlayPackage = ""
    private var overlayShownAtMs = 0L

    /** One-shot bypass: cleared as soon as the user leaves the app. */
    private val bypassed = mutableSetOf<String>()
    private var lastAllowedPackage = ""
    private var lastForegroundPackage = ""

    /** Tri-state decision for [decisionPackage]: null = unknown, never blocks. */
    private var decisionPackage = ""
    private var decisionBlocked: Boolean? = null
    private var decisionInFlight = false
    private var lastDecisionAttemptMs = 0L

    private var lastBlockedReloadMs = 0L

    override fun onCreate() {
        super.onCreate()
        config = BlockerStore.loadConfig(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        detector = ForegroundAppDetector(applicationContext)
        decisionBridge = DecisionBridge(applicationContext)
        createNotificationChannel()
        startForeground(BlockerConstants.NOTIFICATION_ID, buildNotification())
        BlockerStore.reload(applicationContext)
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BlockerConstants.ACTION_HIDE_OVERLAY -> mainHandler.post { hideOverlay() }
            BlockerConstants.ACTION_REFRESH -> {
                config = BlockerStore.loadConfig(applicationContext)
                BlockerStore.reload(applicationContext)
                mainHandler.post {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(BlockerConstants.NOTIFICATION_ID, buildNotification())
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        mainHandler.post { hideOverlay() }
        decisionBridge?.destroy()
        decisionBridge = null
        destroyOverlayEngine()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            BlockerConstants.DEFAULT_CHANNEL_ID,
            config.notificationChannelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = config.notificationChannelDescription
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val smallIcon = config
            .resolveIcon(applicationContext, config.notificationIconResourceName)
            .takeIf { it != 0 }
            ?: R.drawable.aab_ic_notification

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        return NotificationCompat.Builder(this, BlockerConstants.DEFAULT_CHANNEL_ID)
            .setContentTitle(config.notificationTitle)
            .setContentText(config.notificationText)
            .setSmallIcon(smallIcon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    // ----------------------------------------------------------------- monitoring

    private fun startMonitoring() {
        val hostPackage = applicationContext.packageName
        executor.scheduleAtFixedRate(
            {
                try {
                    tick(hostPackage)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            0,
            config.pollIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun tick(hostPackage: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedReloadMs >= 3_000L) {
            BlockerStore.reload(applicationContext)
            lastBlockedReloadMs = now
        }

        val foreground = detector.current(shortWindow = overlayShowing)

        if (foreground != null &&
            foreground != lastForegroundPackage &&
            BlockerStore.isBlocked(foreground)
        ) {
            UsageTracker.recordOpened(applicationContext, foreground)
        }
        val previous = lastForegroundPackage
        lastForegroundPackage = foreground ?: ""
        if (foreground != previous && previous.isNotEmpty()) {
            bypassed.remove(previous)
            if (lastAllowedPackage == previous) lastAllowedPackage = ""
        }

        val shouldStandDown = foreground == null ||
            foreground == hostPackage ||
            detector.isSystemSurface(foreground) ||
            !BlockerStore.isBlocked(foreground)

        if (shouldStandDown) {
            resetDecision()
            if (overlayShowing) mainHandler.post { hideOverlay() }
            return
        }

        val target = foreground!!

        when {
            target in bypassed -> {
                lastAllowedPackage = target
                if (overlayShowing) mainHandler.post { hideOverlay() }
            }

            target == decisionPackage && decisionBlocked == true -> {
                if (!overlayShowing || overlayPackage != target) {
                    mainHandler.post { showOverlay(target) }
                }
            }

            target == decisionPackage && decisionBlocked == false -> {
                lastAllowedPackage = target
                if (overlayShowing) mainHandler.post { hideOverlay() }
            }

            // New package, or stuck unknown: (re)ask.
            target != decisionPackage ||
                (decisionBlocked == null && !decisionInFlight &&
                    now - lastDecisionAttemptMs >= 2_000L) -> {
                requestDecision(target)
            }

            // In flight or unknown within cooldown: do nothing, never block blind.
            else -> Unit
        }
    }

    private fun resetDecision() {
        decisionPackage = ""
        decisionBlocked = null
        decisionInFlight = false
    }

    private fun requestDecision(packageName: String) {
        decisionPackage = packageName
        decisionBlocked = null
        decisionInFlight = true
        lastDecisionAttemptMs = System.currentTimeMillis()

        val bridge = decisionBridge
        if (bridge == null || !bridge.isConfigured()) {
            // No Dart handler: the local block list is the whole truth.
            applyDecision(packageName, true)
            return
        }

        bridge.requestDecision(packageName, config.decisionTimeoutMs) { result ->
            val resolved = result ?: when (config.decisionFailurePolicy) {
                "allow" -> false
                "skip" -> null
                else -> true
            }
            applyDecision(packageName, resolved)
        }
    }

    private fun applyDecision(packageName: String, blocked: Boolean?) {
        mainHandler.post {
            if (decisionPackage != packageName) {
                decisionInFlight = false
                return@post
            }
            decisionInFlight = false
            decisionBlocked = if (packageName in bypassed) false else blocked

            when (decisionBlocked) {
                true -> {
                    val stillThere = lastForegroundPackage == packageName ||
                        detector.current(shortWindow = overlayShowing) == packageName
                    if (stillThere && packageName !in bypassed) showOverlay(packageName)
                }

                false -> {
                    lastAllowedPackage = packageName
                    if (overlayShowing) hideOverlay()
                }

                null -> Unit // unknown: leave the screen alone
            }
        }
    }

    // ----------------------------------------------------------------- overlay

    private fun overlayLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.CENTER }

    private fun showOverlay(packageName: String) {
        if (overlayShowing && overlayPackage == packageName) return
        if (overlayShowing) hideOverlay()
        if (!BlockerStore.isBlocked(packageName)) return
        if (packageName == applicationContext.packageName) return
        if (!PermissionHelper.hasOverlayPermission(applicationContext)) return

        val container = FrameLayout(this).apply {
            setBackgroundColor(config.backgroundColor)
        }
        val content: View = if (config.useFlutterOverlay) {
            buildFlutterOverlay(packageName) ?: buildNativeOverlay(packageName)
        } else {
            buildNativeOverlay(packageName)
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        try {
            windowManager.addView(container, overlayLayoutParams())
            overlayView = container
            overlayShowing = true
            overlayPackage = packageName
            overlayShownAtMs = System.currentTimeMillis()
            UsageTracker.recordBlocked(applicationContext, packageName)
        } catch (e: Exception) {
            e.printStackTrace()
            overlayView = null
            overlayShowing = false
            overlayPackage = ""
        }
    }

    private fun buildNativeOverlay(packageName: String): View = NativeOverlayView.create(
        context = this,
        config = config,
        blockedPackage = packageName,
        appLabel = appLabelFor(packageName),
        onPrimary = { onPrimaryAction() },
        onSecondary = { allowOnce(packageName) },
    )

    private fun buildFlutterOverlay(packageName: String): View? = try {
        val engine = ensureOverlayEngine()
        val view = FlutterView(this, FlutterTextureView(this))
        view.attachToFlutterEngine(engine)
        engine.lifecycleChannel.appIsResumed()
        overlayFlutterView = view
        view
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun ensureOverlayEngine(): FlutterEngine {
        overlayEngine?.let { return it }
        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            loader.startInitialization(applicationContext)
        }
        loader.ensureInitializationComplete(applicationContext, null)

        val engine = FlutterEngine(this)
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(
                loader.findAppBundlePath(),
                config.flutterOverlayEntrypoint,
            ),
        )
        MethodChannel(
            engine.dartExecutor.binaryMessenger,
            BlockerConstants.OVERLAY_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getBlockedPackage" -> result.success(overlayPackage)

                "getBlockedAppLabel" -> result.success(appLabelFor(overlayPackage))

                "getOverlayConfig" -> result.success(
                    mapOf(
                        "title" to config.title,
                        "message" to config.message,
                        "primaryButtonText" to config.primaryButtonText,
                        "secondaryButtonText" to config.secondaryButtonText,
                        "backgroundColor" to config.backgroundColor,
                        "titleColor" to config.titleColor,
                        "messageColor" to config.messageColor,
                        "accentColor" to config.accentColor,
                    ),
                )

                "close" -> {
                    mainHandler.post { hideOverlay() }
                    result.success(true)
                }

                "primaryAction" -> {
                    onPrimaryAction()
                    result.success(true)
                }

                "allowOnce" -> {
                    val pkg = call.argument<String>("packageName")
                        ?: overlayPackage.takeIf { it.isNotEmpty() }
                    if (pkg != null) {
                        allowOnce(pkg)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGS", "packageName required", null)
                    }
                }

                "unblock" -> {
                    val packages = call.argument<List<String>>("packages")
                        ?: listOfNotNull(overlayPackage.takeIf { it.isNotEmpty() })
                    BlockerStore.unblock(applicationContext, packages)
                    BlockerStore.reload(applicationContext)
                    mainHandler.post { hideOverlay() }
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }
        overlayEngine = engine
        return engine
    }

    private fun onPrimaryAction() {
        try {
            resetDecision()
            val intent = if (config.primaryOpensHostApp) {
                packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
            } else {
                null
            } ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mainHandler.post { hideOverlay() }
    }

    private fun allowOnce(packageName: String) {
        bypassed.add(packageName)
        lastAllowedPackage = packageName
        decisionPackage = packageName
        decisionBlocked = false
        UsageTracker.recordBypassed(applicationContext, packageName)
        mainHandler.post { hideOverlay() }
    }

    private fun hideOverlay() {
        try {
            if (overlayPackage.isNotEmpty() && overlayShownAtMs > 0) {
                UsageTracker.addBlockedTime(
                    applicationContext,
                    overlayPackage,
                    System.currentTimeMillis() - overlayShownAtMs,
                )
            }
            overlayFlutterView?.let {
                try {
                    overlayEngine?.lifecycleChannel?.appIsPaused()
                    it.detachFromFlutterEngine()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            overlayFlutterView = null
            overlayView?.let { view ->
                try {
                    windowManager.removeViewImmediate(view)
                } catch (e: Exception) {
                    try {
                        windowManager.removeView(view)
                    } catch (ignored: Exception) {
                        // Already detached.
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            overlayView = null
            overlayShowing = false
            overlayPackage = ""
            overlayShownAtMs = 0
        }
    }

    private fun destroyOverlayEngine() {
        try {
            overlayEngine?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayEngine = null
    }

    private fun appLabelFor(packageName: String): String? = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        null
    }
}
