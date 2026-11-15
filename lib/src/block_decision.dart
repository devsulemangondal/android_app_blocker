import 'dart:ui' as ui;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'channels.dart';

/// Decides whether [packageName] should be blocked at this moment.
///
/// Return `true` to show the block screen, `false` to let the app through, or
/// `null` when you cannot tell (for example a request failed) — the plugin
/// then applies `BlockerConfig.decisionFallback`.
///
/// This runs in a **background isolate**, separate from your UI isolate. It has
/// no access to your widgets, providers or in-memory singletons: read anything
/// it needs from disk (`SharedPreferences`, a database) or from the network.
///
/// The function must be a top-level or static function annotated with
/// `@pragma('vm:entry-point')`, otherwise it cannot be looked up after the app
/// process has been killed.
typedef BlockDecisionHandler = Future<bool?> Function(String packageName);

/// Entrypoint for the headless isolate that answers block decisions.
///
/// Registered automatically by `AndroidAppBlocker.setBlockDecisionHandler`.
@pragma('vm:entry-point')
void appBlockerDecisionDispatcher() {
  WidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel(BlockerChannels.decision);

  channel.setMethodCallHandler((MethodCall call) async {
    if (call.method != 'onShouldBlock') return null;

    final args = call.arguments as Map<Object?, Object?>?;
    if (args == null) return null;

    final rawHandle = (args['callbackHandle'] as num?)?.toInt();
    final packageName = args['packageName'] as String?;
    if (rawHandle == null || packageName == null) return null;

    final handle = ui.CallbackHandle.fromRawHandle(rawHandle);
    final callback = ui.PluginUtilities.getCallbackFromHandle(handle);
    if (callback is! BlockDecisionHandler) return null;

    try {
      return await callback(packageName);
    } catch (error, stack) {
      debugPrint('[android_app_blocker] block decision handler threw: $error');
      debugPrintStack(stackTrace: stack);
      return null;
    }
  });

  // Tell the native side the isolate is ready to receive calls.
  channel.invokeMethod<void>('dispatcherReady');
}
