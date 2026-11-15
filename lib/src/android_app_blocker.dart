import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'block_decision.dart';
import 'blocker_config.dart';
import 'channels.dart';
import 'models.dart';

/// Blocks apps on Android by watching the foreground app and covering blocked
/// ones with a full-screen block screen.
///
/// Typical setup:
///
/// ```dart
/// await AndroidAppBlocker.configure(const BlockerConfig(
///   title: 'Stay focused',
///   message: 'You blocked this app during study hours.',
/// ));
/// await AndroidAppBlocker.requestUsageStatsPermission();
/// await AndroidAppBlocker.requestOverlayPermission();
/// await AndroidAppBlocker.block(['com.example.socialapp']);
/// await AndroidAppBlocker.start();
/// ```
abstract final class AndroidAppBlocker {
  static const MethodChannel _channel = MethodChannel(BlockerChannels.methods);

  static bool get _supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static Future<T?> _invoke<T>(String method, [Map<String, Object?>? args]) {
    if (!_supported) return Future<T?>.value(null);
    return _channel.invokeMethod<T>(method, args);
  }

  // --------------------------------------------------------------- configuration

  /// Applies [config]. Safe to call at any time; a running service picks the
  /// new values up without restarting.
  static Future<void> configure(BlockerConfig config) async {
    await _invoke<bool>('configure', {'config': config.toMap()});
  }

  // --------------------------------------------------------------- block list

  /// Adds [packages] to the block list and starts the service when the
  /// required permissions are already granted.
  static Future<void> block(List<String> packages) async {
    await _invoke<bool>('block', {'packages': packages});
  }

  /// Removes [packages] from the block list.
  static Future<void> unblock(List<String> packages) async {
    await _invoke<bool>('unblock', {'packages': packages});
  }

  /// Replaces the whole block list with [packages].
  static Future<void> setBlockedApps(List<String> packages) async {
    await _invoke<bool>('setBlockedApps', {'packages': packages});
  }

  /// Empties the block list and stops the service.
  static Future<void> clearBlockedApps() async {
    await _invoke<bool>('clearBlockedApps');
  }

  /// Package names currently on the block list.
  static Future<List<String>> getBlockedApps() async {
    final result = await _invoke<List<Object?>>('getBlockedApps');
    return result?.map((e) => e.toString()).toList() ?? const <String>[];
  }

  /// Whether [packageName] is on the block list.
  static Future<bool> isBlocked(String packageName) async {
    return await _invoke<bool>('isBlocked', {'packageName': packageName}) ??
        false;
  }

  // --------------------------------------------------------------- service

  /// Starts the blocking service.
  ///
  /// Throws a [PlatformException] with code `MISSING_PERMISSION` when overlay
  /// or usage access has not been granted yet. Returns false on non-Android
  /// platforms.
  static Future<bool> start() async {
    return await _invoke<bool>('start') ?? false;
  }

  /// Stops the blocking service and removes the block screen.
  static Future<void> stop() async {
    await _invoke<bool>('stop');
  }

  /// Whether blocking is currently switched on.
  static Future<bool> isRunning() async {
    return await _invoke<bool>('isRunning') ?? false;
  }

  /// Dismisses the block screen right now, without changing the block list.
  /// Useful when your own app comes to the foreground.
  static Future<void> hideOverlay() async {
    await _invoke<bool>('hideOverlay');
  }

  // --------------------------------------------------------------- decisions

  /// Registers [handler] as the final say on whether a blocked app is actually
  /// blocked at the moment it is opened.
  ///
  /// Use it when "blocked" depends on something only your app knows — a
  /// schedule, a server response, a streak, a parent's approval. Without a
  /// handler, everything on the block list is simply blocked.
  ///
  /// [handler] must be a top-level or static function marked with
  /// `@pragma('vm:entry-point')`; it runs in a background isolate.
  ///
  /// Pass `null` to remove a previously registered handler.
  static Future<void> setBlockDecisionHandler(
    BlockDecisionHandler? handler,
  ) async {
    if (!_supported) return;

    if (handler == null) {
      await _invoke<bool>('clearDecisionHandler');
      return;
    }

    final dispatcher = ui.PluginUtilities.getCallbackHandle(
      appBlockerDecisionDispatcher,
    );
    final callback = ui.PluginUtilities.getCallbackHandle(handler);

    if (dispatcher == null || callback == null) {
      throw ArgumentError(
        'The block decision handler could not be registered. Make sure it is a '
        'top-level or static function annotated with '
        "@pragma('vm:entry-point') — closures and instance methods cannot be "
        'resolved from a background isolate.',
      );
    }

    await _invoke<bool>('setDecisionHandler', {
      'dispatcherHandle': dispatcher.toRawHandle(),
      'callbackHandle': callback.toRawHandle(),
    });
  }

  // --------------------------------------------------------------- permissions

  /// Current state of the permissions the blocker needs.
  static Future<BlockerPermissions> checkPermissions() async {
    final result = await _invoke<Map<Object?, Object?>>('checkPermissions');
    if (result == null) return BlockerPermissions.none;
    return BlockerPermissions.fromMap(result);
  }

  /// Opens the "Display over other apps" settings screen for your app.
  ///
  /// There is no callback: re-check with [checkPermissions] when your app
  /// resumes.
  static Future<void> requestOverlayPermission() async {
    await _invoke<bool>('requestOverlayPermission');
  }

  /// Opens the "Usage access" settings screen for your app.
  static Future<void> requestUsageStatsPermission() async {
    await _invoke<bool>('requestUsageStatsPermission');
  }

  /// Opens the battery optimisation settings. Worth offering on devices that
  /// aggressively kill background services.
  static Future<void> openBatteryOptimisationSettings() async {
    await _invoke<bool>('openBatteryOptimisationSettings');
  }

  // --------------------------------------------------------------- app list

  /// Apps installed on the device, so the user can pick what to block.
  ///
  /// Only launchable, non-system apps are returned by default. Icons are left
  /// out unless [includeIcons] is true, since decoding them for every app is
  /// expensive.
  ///
  /// On Android 11+ this is limited by package visibility rules. See the
  /// README if you need the complete list.
  static Future<List<InstalledApp>> getInstalledApps({
    bool includeSystemApps = false,
    bool includeIcons = false,
  }) async {
    final result = await _invoke<List<Object?>>('getInstalledApps', {
      'includeSystemApps': includeSystemApps,
      'includeIcons': includeIcons,
    });
    return result
            ?.whereType<Map<Object?, Object?>>()
            .map(InstalledApp.fromMap)
            .toList() ??
        const <InstalledApp>[];
  }

  /// Opens an installed app. Returns false when it has no launcher activity.
  static Future<bool> launchApp(String packageName) async {
    return await _invoke<bool>('launchApp', {'packageName': packageName}) ??
        false;
  }

  // --------------------------------------------------------------- usage

  /// Usage counters for [packages], or for the whole block list when omitted.
  ///
  /// All of it stays on the device; nothing is uploaded by this plugin.
  static Future<List<AppUsageStats>> getUsageStats({
    List<String>? packages,
    int lookbackDays = 30,
  }) async {
    final result = await _invoke<List<Object?>>('getUsageStats', {
      if (packages != null) 'packages': packages,
      'lookbackDays': lookbackDays,
    });
    return result
            ?.whereType<Map<Object?, Object?>>()
            .map(AppUsageStats.fromMap)
            .toList() ??
        const <AppUsageStats>[];
  }

  /// Clears the plugin's own counters, for one package or all of them.
  /// Android's own usage statistics are not affected.
  static Future<void> resetUsageStats({String? packageName}) async {
    await _invoke<bool>('resetUsageStats', {
      if (packageName != null) 'packageName': packageName,
    });
  }
}
