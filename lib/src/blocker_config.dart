import 'package:flutter/painting.dart';

import 'models.dart';

/// Everything the host app controls about the block screen and the background
/// service. Pass it to `AndroidAppBlocker.configure`.
///
/// The plugin ships neutral defaults only — all user-facing copy comes from
/// here, so nothing about your app is baked into the package.
class BlockerConfig {
  /// Creates a configuration for the block screen and background service.
  const BlockerConfig({
    this.title = 'App blocked',
    this.message = 'You asked to stay away from this app.',
    this.primaryButtonText = 'Go back',
    this.secondaryButtonText = 'Open anyway',
    this.iconResourceName,
    this.backgroundColor = const Color(0xFFFFFFFF),
    this.titleColor = const Color(0xFF101828),
    this.messageColor = const Color(0xFF667085),
    this.accentColor = const Color(0xFF4F46E5),
    this.primaryButtonTextColor = const Color(0xFFFFFFFF),
    this.secondaryButtonTextColor = const Color(0xFF667085),
    this.useFlutterOverlay = false,
    this.flutterOverlayEntrypoint = 'appBlockerOverlayMain',
    this.primaryOpensHostApp = true,
    this.pollInterval = const Duration(milliseconds: 250),
    this.notificationTitle = 'App blocking active',
    this.notificationText = 'Monitoring apps you chose to block.',
    this.notificationChannelName = 'App blocking',
    this.notificationChannelDescription =
        'Keeps app blocking running in the background.',
    this.notificationIconResourceName,
    this.restartOnBoot = true,
    this.decisionFallback = DecisionFallback.block,
    this.decisionTimeout = const Duration(seconds: 6),
  });

  // --- Block screen content ------------------------------------------------

  /// Headline on the block screen.
  final String title;

  /// Supporting line under the headline.
  final String message;

  /// Main button. Closes the block screen and leaves the blocked app.
  final String primaryButtonText;

  /// Optional secondary action that lets the user through once. Set to `null`
  /// to remove the escape hatch entirely.
  final String? secondaryButtonText;

  /// Name of a drawable or mipmap **in your own app** to show as the icon,
  /// for example `'ic_block_screen'`. Falls back to a neutral built-in icon.
  final String? iconResourceName;

  // --- Block screen styling ------------------------------------------------

  /// Fill colour behind the whole block screen.
  final Color backgroundColor;

  /// Colour of [title].
  final Color titleColor;

  /// Colour of [message].
  final Color messageColor;

  /// Used for the primary button fill and the default icon tint.
  final Color accentColor;
  /// Label colour inside the primary button.
  final Color primaryButtonTextColor;

  /// Label colour of the secondary action.
  final Color secondaryButtonTextColor;

  // --- Block screen behaviour ----------------------------------------------

  /// Render your own Flutter widget instead of the built-in native screen.
  /// Requires a Dart entrypoint named [flutterOverlayEntrypoint].
  final bool useFlutterOverlay;

  /// Name of the `@pragma('vm:entry-point')` top-level function that runs the
  /// Flutter block screen. Only used when [useFlutterOverlay] is true.
  final String flutterOverlayEntrypoint;

  /// Primary button opens your app (true) or the home screen (false).
  final bool primaryOpensHostApp;

  // --- Service behaviour ---------------------------------------------------

  /// How often the foreground app is checked. Lower is more responsive and
  /// costs more battery. Clamped to 50ms–5s natively.
  final Duration pollInterval;

  /// Title of the ongoing foreground service notification.
  final String notificationTitle;

  /// Body of the ongoing foreground service notification.
  final String notificationText;

  /// Name of the notification channel, as shown in Android settings.
  final String notificationChannelName;

  /// Description of the notification channel, as shown in Android settings.
  final String notificationChannelDescription;

  /// Name of a drawable in your own app for the service notification icon.
  /// Should be a white-on-transparent vector, as Android tints it.
  final String? notificationIconResourceName;

  /// Resume blocking automatically after the device reboots.
  final bool restartOnBoot;

  /// What to do when the block decision handler cannot answer.
  final DecisionFallback decisionFallback;

  /// How long to wait for the block decision handler before giving up.
  final Duration decisionTimeout;

  /// Serialises the configuration for the platform channel.
  Map<String, Object?> toMap() => <String, Object?>{
    'title': title,
    'message': message,
    'primaryButtonText': primaryButtonText,
    'secondaryButtonText': secondaryButtonText,
    'iconResourceName': iconResourceName,
    'backgroundColor': backgroundColor.toARGB32(),
    'titleColor': titleColor.toARGB32(),
    'messageColor': messageColor.toARGB32(),
    'accentColor': accentColor.toARGB32(),
    'primaryButtonTextColor': primaryButtonTextColor.toARGB32(),
    'secondaryButtonTextColor': secondaryButtonTextColor.toARGB32(),
    'useFlutterOverlay': useFlutterOverlay,
    'flutterOverlayEntrypoint': flutterOverlayEntrypoint,
    'primaryOpensHostApp': primaryOpensHostApp,
    'pollIntervalMs': pollInterval.inMilliseconds,
    'notificationTitle': notificationTitle,
    'notificationText': notificationText,
    'notificationChannelName': notificationChannelName,
    'notificationChannelDescription': notificationChannelDescription,
    'notificationIconResourceName': notificationIconResourceName,
    'restartOnBoot': restartOnBoot,
    'decisionFailurePolicy': decisionFallback.wireValue,
    'decisionTimeoutMs': decisionTimeout.inMilliseconds,
  };
}
