package com.appblocker.android_app_blocker

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

/**
 * Runs the host app's `shouldBlock` callback in a headless Dart isolate so the
 * decision can be made even when the app's UI is not running.
 *
 * The plugin never performs network calls itself; whatever the host app needs
 * to consult lives entirely in its own Dart code.
 */
internal class DecisionBridge(private val context: Context) : MethodChannel.MethodCallHandler {

    /** null = could not decide (unknown). */
    fun interface Callback {
        fun onResult(shouldBlock: Boolean?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null

    @Volatile
    private var dispatcherReady = false
    private val queued = ArrayDeque<() -> Unit>()

    fun isConfigured(): Boolean = BlockerStore.hasDecisionHandler(context)

    /** Must be called on the main thread. */
    fun start() {
        if (engine != null) return
        val dispatcherHandle = BlockerStore.dispatcherHandle(context) ?: return
        val info = FlutterCallbackInformation.lookupCallbackInformation(dispatcherHandle)
        if (info == null) {
            // Stale handle (app was reinstalled / rebuilt). Drop it so we fall
            // back to the configured failure policy instead of hanging.
            BlockerStore.clearDecisionHandles(context)
            return
        }
        try {
            val loader = FlutterInjector.instance().flutterLoader()
            if (!loader.initialized()) {
                loader.startInitialization(context.applicationContext)
            }
            loader.ensureInitializationComplete(context.applicationContext, null)

            val created = FlutterEngine(context.applicationContext)
            val methodChannel = MethodChannel(
                created.dartExecutor.binaryMessenger,
                BlockerConstants.DECISION_CHANNEL,
            )
            methodChannel.setMethodCallHandler(this)
            created.dartExecutor.executeDartCallback(
                DartExecutor.DartCallback(
                    context.assets,
                    loader.findAppBundlePath(),
                    info,
                ),
            )
            engine = created
            channel = methodChannel
        } catch (e: Exception) {
            e.printStackTrace()
            destroy()
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "dispatcherReady") {
            dispatcherReady = true
            result.success(true)
            while (queued.isNotEmpty()) {
                queued.removeFirst().invoke()
            }
        } else {
            result.notImplemented()
        }
    }

    /**
     * Ask Dart whether [packageName] should be blocked right now. [callback] is
     * always invoked exactly once, on the main thread, within [timeoutMs].
     */
    fun requestDecision(packageName: String, timeoutMs: Long, callback: Callback) {
        if (!isConfigured()) {
            callback.onResult(null)
            return
        }
        mainHandler.post {
            start()
            val target = channel
            if (target == null) {
                callback.onResult(null)
                return@post
            }

            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            fun settle(value: Boolean?) {
                if (settled.compareAndSet(false, true)) callback.onResult(value)
            }

            val timeoutRunnable = Runnable { settle(null) }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            val invoke = {
                target.invokeMethod(
                    "onShouldBlock",
                    mapOf(
                        "packageName" to packageName,
                        "callbackHandle" to BlockerStore.callbackHandle(context),
                    ),
                    object : MethodChannel.Result {
                        override fun success(value: Any?) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            settle(value as? Boolean)
                        }

                        override fun error(code: String, message: String?, details: Any?) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            settle(null)
                        }

                        override fun notImplemented() {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            settle(null)
                        }
                    },
                )
            }

            if (dispatcherReady) invoke() else queued.addLast(invoke)
        }
    }

    fun destroy() {
        try {
            channel?.setMethodCallHandler(null)
            engine?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        channel = null
        engine = null
        dispatcherReady = false
        queued.clear()
    }
}
