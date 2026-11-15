import 'dart:typed_data';

/// Which of the special permissions the blocker needs are currently granted.
class BlockerPermissions {
  /// Creates a snapshot of the blocker's permission state.
  const BlockerPermissions({
    required this.overlay,
    required this.usageStats,
    required this.notifications,
  });

  /// "Display over other apps" — required to draw the block screen.
  final bool overlay;

  /// "Usage access" — required to see which app is in the foreground.
  final bool usageStats;

  /// Runtime notification permission (Android 13+). The foreground service
  /// notification is silent, but without this the user will not see it.
  final bool notifications;

  /// True when blocking can actually run.
  bool get canBlock => overlay && usageStats;

  /// True when every permission, including notifications, is granted.
  bool get allGranted => overlay && usageStats && notifications;

  /// Reads a permission snapshot from the platform channel payload.
  factory BlockerPermissions.fromMap(Map<Object?, Object?> map) {
    return BlockerPermissions(
      overlay: map['overlay'] == true,
      usageStats: map['usageStats'] == true,
      notifications: map['notifications'] == true,
    );
  }

  /// A snapshot with nothing granted, used on unsupported platforms.
  static const BlockerPermissions none = BlockerPermissions(
    overlay: false,
    usageStats: false,
    notifications: false,
  );

  @override
  String toString() =>
      'BlockerPermissions(overlay: $overlay, usageStats: $usageStats, '
      'notifications: $notifications)';
}

/// An app installed on the device.
class InstalledApp {
  /// Creates a description of an installed app.
  const InstalledApp({
    required this.packageName,
    required this.name,
    required this.isSystemApp,
    this.icon,
  });

  /// Android package id, for example `com.example.socialapp`.
  final String packageName;

  /// Display name shown in the launcher.
  final String name;

  /// Whether the app ships with the system image.
  final bool isSystemApp;

  /// PNG bytes, only populated when requested with `includeIcons: true`.
  final Uint8List? icon;

  /// Reads an installed app from the platform channel payload.
  factory InstalledApp.fromMap(Map<Object?, Object?> map) {
    return InstalledApp(
      packageName: (map['packageName'] as String?) ?? '',
      name: (map['name'] as String?) ?? '',
      isSystemApp: map['isSystemApp'] == true,
      icon: map['icon'] as Uint8List?,
    );
  }

  @override
  String toString() => 'InstalledApp($name, $packageName)';
}

/// Usage numbers for one package.
///
/// [openCount] and [foregroundTime] come from Android's own usage statistics,
/// so they cover periods when the app was not blocked. The remaining counters
/// are maintained by this plugin.
class AppUsageStats {
  /// Creates a usage snapshot for one package.
  const AppUsageStats({
    required this.packageName,
    required this.openCount,
    required this.blockedCount,
    required this.bypassedCount,
    required this.blockedTime,
    required this.foregroundTime,
  });

  /// Android package id these numbers describe.
  final String packageName;

  /// Times the app was brought to the foreground in the lookback window.
  final int openCount;

  /// Times the block screen was shown for this app.
  final int blockedCount;

  /// Times the user dismissed the block screen with the secondary action.
  final int bypassedCount;

  /// Total time the block screen was on top of this app.
  final Duration blockedTime;

  /// Total foreground time for the app, as reported by Android.
  final Duration foregroundTime;

  /// Reads usage numbers from the platform channel payload.
  factory AppUsageStats.fromMap(Map<Object?, Object?> map) {
    int asInt(Object? value) => (value as num?)?.toInt() ?? 0;
    return AppUsageStats(
      packageName: (map['packageName'] as String?) ?? '',
      openCount: asInt(map['openCount']),
      blockedCount: asInt(map['blockedCount']),
      bypassedCount: asInt(map['bypassedCount']),
      blockedTime: Duration(milliseconds: asInt(map['blockedTimeMs'])),
      foregroundTime: Duration(milliseconds: asInt(map['foregroundTimeMs'])),
    );
  }

  @override
  String toString() =>
      'AppUsageStats($packageName, opened: $openCount, blocked: $blockedCount, '
      'bypassed: $bypassedCount)';
}

/// What the service should do when the Dart block decision handler cannot
/// answer — because it is not registered, threw, or took too long.
enum DecisionFallback {
  /// Treat the app as blocked. Safest default for a focus/parental app.
  block,

  /// Let the app through.
  allow,

  /// Do nothing this time: no block screen, no bypass. The service retries
  /// while the app stays in the foreground.
  skip;

  /// Value sent across the platform channel.
  String get wireValue => switch (this) {
    DecisionFallback.block => 'block',
    DecisionFallback.allow => 'allow',
    DecisionFallback.skip => 'skip',
  };
}
