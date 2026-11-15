import 'package:flutter/services.dart';

import 'channels.dart';

/// Controls the Flutter block screen **from inside the overlay isolate**.
///
/// Only use this from the widget tree you run in
/// `BlockerConfig.flutterOverlayEntrypoint`. Calling it from your normal app
/// isolate does nothing, because the overlay channel only exists in the
/// overlay engine.
abstract final class BlockOverlay {
  static const MethodChannel _channel = MethodChannel(BlockerChannels.overlay);

  /// Package name of the app currently being blocked.
  static Future<String?> blockedPackage() async {
    final value = await _channel.invokeMethod<String>('getBlockedPackage');
    return (value == null || value.isEmpty) ? null : value;
  }

  /// Human-readable label of the blocked app, when it can be resolved.
  static Future<String?> blockedAppLabel() async {
    final value = await _channel.invokeMethod<String>('getBlockedAppLabel');
    return (value == null || value.isEmpty) ? null : value;
  }

  /// The strings and colours from the `BlockerConfig` the host app set, so a
  /// custom overlay can stay in sync with the rest of the configuration.
  static Future<Map<String, Object?>> config() async {
    final value = await _channel.invokeMethod<Map<Object?, Object?>>(
      'getOverlayConfig',
    );
    return value?.map((k, v) => MapEntry(k.toString(), v)) ?? <String, Object?>{};
  }

  /// Dismiss the block screen without changing anything. The screen will come
  /// back on the next check if the app is still blocked.
  static Future<void> close() =>
      _channel.invokeMethod<void>('close').catchError((_) {});

  /// Run the configured primary action: leave the blocked app and open either
  /// the host app or the launcher, then dismiss the block screen.
  static Future<void> primaryAction() =>
      _channel.invokeMethod<void>('primaryAction').catchError((_) {});

  /// Let the user into the app once. The bypass is dropped as soon as they
  /// leave the app; the package stays on the block list.
  static Future<void> allowOnce({String? packageName}) => _channel
      .invokeMethod<void>('allowOnce', {
        if (packageName != null) 'packageName': packageName,
      })
      .catchError((_) {});

  /// Permanently remove packages from the block list. Defaults to the app
  /// currently being blocked.
  static Future<void> unblock([List<String>? packages]) => _channel
      .invokeMethod<void>('unblock', {
        if (packages != null) 'packages': packages,
      })
      .catchError((_) {});
}
