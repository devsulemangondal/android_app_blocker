import 'package:android_app_blocker/android_app_blocker.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.appblocker.android_app_blocker/methods');

  group('BlockerConfig', () {
    test('serialises colours as ARGB ints', () {
      final map = const BlockerConfig(
        accentColor: Color(0xFF123456),
        backgroundColor: Color(0xFFFFFFFF),
      ).toMap();

      expect(map['accentColor'], 0xFF123456);
      expect(map['backgroundColor'], 0xFFFFFFFF);
    });

    test('serialises durations as milliseconds', () {
      final map = const BlockerConfig(
        pollInterval: Duration(milliseconds: 500),
        decisionTimeout: Duration(seconds: 3),
      ).toMap();

      expect(map['pollIntervalMs'], 500);
      expect(map['decisionTimeoutMs'], 3000);
    });

    test('keeps a null secondary button so it can be hidden', () {
      final map = const BlockerConfig(secondaryButtonText: null).toMap();
      expect(map.containsKey('secondaryButtonText'), isTrue);
      expect(map['secondaryButtonText'], isNull);
    });

    test('maps the decision fallback to its wire value', () {
      expect(
        const BlockerConfig(
          decisionFallback: DecisionFallback.allow,
        ).toMap()['decisionFailurePolicy'],
        'allow',
      );
      expect(
        const BlockerConfig().toMap()['decisionFailurePolicy'],
        'block',
      );
    });
  });

  group('model parsing', () {
    test('BlockerPermissions.fromMap reads each flag', () {
      final permissions = BlockerPermissions.fromMap(const {
        'overlay': true,
        'usageStats': true,
        'notifications': false,
      });

      expect(permissions.overlay, isTrue);
      expect(permissions.canBlock, isTrue);
      expect(permissions.allGranted, isFalse);
    });

    test('BlockerPermissions.canBlock needs both special permissions', () {
      final permissions = BlockerPermissions.fromMap(const {
        'overlay': true,
        'usageStats': false,
        'notifications': true,
      });

      expect(permissions.canBlock, isFalse);
    });

    test('AppUsageStats.fromMap converts milliseconds to Durations', () {
      final stats = AppUsageStats.fromMap(const {
        'packageName': 'com.example.app',
        'openCount': 7,
        'blockedCount': 3,
        'bypassedCount': 1,
        'blockedTimeMs': 90000,
        'foregroundTimeMs': 120000,
      });

      expect(stats.packageName, 'com.example.app');
      expect(stats.blockedTime, const Duration(minutes: 1, seconds: 30));
      expect(stats.foregroundTime, const Duration(minutes: 2));
    });

    test('InstalledApp.fromMap tolerates a missing icon', () {
      final app = InstalledApp.fromMap(const {
        'packageName': 'com.example.app',
        'name': 'Example',
        'isSystemApp': false,
      });

      expect(app.icon, isNull);
      expect(app.name, 'Example');
    });
  });

  group('AndroidAppBlocker', () {
    final calls = <MethodCall>[];

    setUp(() {
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            return switch (call.method) {
              'getBlockedApps' => <String>['com.example.one'],
              'isRunning' => true,
              _ => true,
            };
          });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('block sends the package list', () async {
      await AndroidAppBlocker.block(['com.example.one', 'com.example.two']);

      expect(calls.single.method, 'block');
      expect(calls.single.arguments, {
        'packages': ['com.example.one', 'com.example.two'],
      });
    });

    test('getBlockedApps returns the platform list', () async {
      expect(await AndroidAppBlocker.getBlockedApps(), ['com.example.one']);
    });

    test('setBlockDecisionHandler rejects a closure', () async {
      expect(
        () => AndroidAppBlocker.setBlockDecisionHandler(
          (packageName) async => true,
        ),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('clearing the handler does not need a callback handle', () async {
      await AndroidAppBlocker.setBlockDecisionHandler(null);
      expect(calls.single.method, 'clearDecisionHandler');
    });
  });
}
