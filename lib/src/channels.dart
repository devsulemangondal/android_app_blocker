/// Method channel names shared by the Dart and Kotlin sides.
abstract final class BlockerChannels {
  /// Main plugin channel, served by the plugin's Android entry point.
  static const String methods = 'com.appblocker.android_app_blocker/methods';

  /// Channel served by the blocking service for the Flutter block screen.
  static const String overlay = 'com.appblocker.android_app_blocker/overlay';

  /// Channel used by the headless block-decision isolate.
  static const String decision = 'com.appblocker.android_app_blocker/decision';
}
