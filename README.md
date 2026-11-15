# android_app_blocker

Block any app on Android from Flutter.

A foreground service watches which app is on screen and, when the user opens
something on your block list, covers it with a full-screen block screen. You
supply the copy, the colours and — optionally — the logic that decides whether
a blocked app is actually blocked right now.

Android only. iOS does not allow third-party apps to block other apps.

## Features

- Block and unblock apps by package name; the list survives restarts and reboots.
- A built-in block screen you style from Dart, or your own Flutter widget.
- Optional background callback so "blocked" can depend on a schedule, a server,
  a streak, or anything else your app knows.
- Permission helpers for *display over other apps* and *usage access*.
- A list of installed apps (with icons) so users can pick what to block.
- On-device usage counters: opens, blocks, bypasses, time blocked.
- No network access of any kind. The plugin never phones home.

## Install

```yaml
dependencies:
  android_app_blocker: ^0.1.0
```

The plugin contributes its own permissions and service declaration, so there is
nothing to add to your `AndroidManifest.xml`.

## Quick start

```dart
import 'package:android_app_blocker/android_app_blocker.dart';

// 1. Describe the block screen.
await AndroidAppBlocker.configure(const BlockerConfig(
  title: 'Stay focused',
  message: 'You blocked this app until 6pm.',
  primaryButtonText: 'Back to work',
  secondaryButtonText: 'Just 5 minutes',
  accentColor: Color(0xFF16A34A),
));

// 2. Get the two special permissions. Both open a settings screen; re-check
//    when your app resumes.
final permissions = await AndroidAppBlocker.checkPermissions();
if (!permissions.usageStats) {
  await AndroidAppBlocker.requestUsageStatsPermission();
}
if (!permissions.overlay) {
  await AndroidAppBlocker.requestOverlayPermission();
}

// 3. Block apps and start.
await AndroidAppBlocker.block(['com.example.socialapp']);
await AndroidAppBlocker.start();
```

`start()` throws a `PlatformException` with code `MISSING_PERMISSION` if either
permission is still missing.

### Letting the user choose apps

```dart
final apps = await AndroidAppBlocker.getInstalledApps(includeIcons: true);
for (final app in apps) {
  print('${app.name} — ${app.packageName}');
}
```

Pass `includeIcons: false` (the default) when you only need names; decoding
every icon is expensive on devices with a lot of apps installed.

## Deciding when blocking applies

By default every package on the block list is blocked, always. If "blocked"
depends on something only your app knows, register a decision handler:

```dart
@pragma('vm:entry-point')
Future<bool?> shouldBlock(String packageName) async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.reload();
  final until = prefs.getInt('focus_until') ?? 0;
  return DateTime.now().millisecondsSinceEpoch < until;
}

await AndroidAppBlocker.setBlockDecisionHandler(shouldBlock);
```

- Return `true` to block, `false` to allow, `null` when you cannot tell.
- When you return `null`, time out, or throw, the plugin falls back to
  `BlockerConfig.decisionFallback` (`block` by default; `allow` and `skip` are
  the other options).

Three things to know about the handler:

1. **It runs in a background isolate.** Your widgets, providers and in-memory
   singletons do not exist there. Read what you need from disk or the network.
2. **It must be a top-level or static function** annotated with
   `@pragma('vm:entry-point')`. Closures and instance methods cannot be looked
   up after the process has been killed, and registering one throws
   `ArgumentError`.
3. **It is on the critical path.** The block screen does not appear until it
   answers, so keep it fast and set a sensible `decisionTimeout`.

If you call a network API from the handler, that request is yours — the plugin
has no networking code and no endpoints of its own.

## Using your own Flutter block screen

Set `useFlutterOverlay: true` and add an entrypoint to your `lib/main.dart`:

```dart
@pragma('vm:entry-point')
void appBlockerOverlayMain() {
  runApp(const MyBlockScreen());
}

class MyBlockScreen extends StatelessWidget {
  const MyBlockScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Not right now'),
              ElevatedButton(
                onPressed: BlockOverlay.primaryAction,
                child: const Text('Go back'),
              ),
              TextButton(
                onPressed: BlockOverlay.allowOnce,
                child: const Text('Let me in once'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
```

`BlockOverlay` works only inside that entrypoint. It exposes
`blockedPackage()`, `blockedAppLabel()`, `config()`, `primaryAction()`,
`allowOnce()`, `unblock()` and `close()`.

Rename the entrypoint with `BlockerConfig.flutterOverlayEntrypoint` if
`appBlockerOverlayMain` clashes with something in your app.

## Usage statistics

```dart
final stats = await AndroidAppBlocker.getUsageStats(lookbackDays: 7);
for (final s in stats) {
  print('${s.packageName}: opened ${s.openCount}x, '
        'blocked ${s.blockedCount}x, bypassed ${s.bypassedCount}x');
}
```

`openCount` and `foregroundTime` come from Android's own usage statistics and
cover times when the app was not blocked. The rest are counted by this plugin.
Everything stays on the device.

## Permissions and Play Store notes

The plugin declares these for you:

| Permission | Why |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Draw the block screen over other apps |
| `PACKAGE_USAGE_STATS` | See which app is in the foreground |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep monitoring while your app is closed |
| `POST_NOTIFICATIONS` | Show the required silent service notification |
| `RECEIVE_BOOT_COMPLETED` | Resume blocking after a reboot |

Things to plan for before you ship:

- **Usage access cannot be granted from a dialog.** It is a settings screen the
  user has to visit. Explain why before sending them there.
- **`FOREGROUND_SERVICE_SPECIAL_USE` needs a Play Console declaration.**
  Describe the blocking behaviour the user explicitly turned on. The plugin
  supplies a neutral `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; override it in your own
  manifest if you want different wording.
- **App listing is limited on Android 11+.** `getInstalledApps` returns apps
  with a launcher activity, which is enough for a block picker. If you need the
  complete list, add `QUERY_ALL_PACKAGES` to your own manifest and justify it to
  Google — the plugin deliberately does not request it for you.
- **Aggressive OEMs** (Xiaomi, Oppo, Vivo, Samsung) may kill the service. Offer
  `openBatteryOptimisationSettings()` and ask users to allow autostart.

## How it works

A `specialUse` foreground service polls `UsageStatsManager` for the package that
most recently came to the foreground. Only `ACTIVITY_RESUMED` and
`MOVE_TO_FOREGROUND` events count; `PAUSED` events are ignored, because with
gesture navigation a blocked app can report `PAUSED` before the launcher reports
`RESUMED`, which would leave the block screen stuck on screen.

When a blocked package appears, the plugin asks your decision handler (if any)
and shows the block screen only on a confirmed *block* answer. An unknown answer
never produces a block screen on its own.

Accessibility services are **not** used, so the plugin does not fall under the
Play policy for accessibility-based blocking.

## Limitations

- Android only.
- Blocking is an overlay, not a kernel-level lock. A determined user can
  revoke the permission, force-stop your app, or uninstall it.
- Polling costs some battery. The default 250ms interval is a compromise;
  raise `pollInterval` if you prefer battery life over instant response.
- The block screen uses `FLAG_NOT_FOCUSABLE`, so the hardware back button goes
  to the app underneath. Give users a clear way out via the primary button.

## License

MIT
#   a n d r o i d _ a p p _ b l o c k e r  
 