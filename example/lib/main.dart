import 'package:android_app_blocker/android_app_blocker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const ExampleApp());

/// Example Flutter block screen, used when `useFlutterOverlay` is turned on.
///
/// This runs in its own engine, launched by the blocking service, so it has to
/// be a top-level function marked with `@pragma('vm:entry-point')`.
@pragma('vm:entry-point')
void appBlockerOverlayMain() => runApp(const _FlutterBlockScreen());

/// Example of a block decision handler: blocks only outside a break window
/// the user set in the app. Runs in a background isolate.
@pragma('vm:entry-point')
Future<bool?> exampleShouldBlock(String packageName) async {
  final hour = DateTime.now().hour;
  // Allow a lunch break; block the rest of the working day.
  if (hour == 13) return false;
  return hour >= 9 && hour < 18;
}

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'android_app_blocker example',
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF4F46E5),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  BlockerPermissions _permissions = BlockerPermissions.none;
  List<InstalledApp> _apps = const <InstalledApp>[];
  Set<String> _blocked = <String>{};
  bool _running = false;
  bool _loading = true;
  bool _useFlutterOverlay = false;
  bool _useSchedule = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Both permissions are granted on a settings screen, so re-check on resume.
    if (state == AppLifecycleState.resumed) {
      _refresh();
      AndroidAppBlocker.hideOverlay();
    }
  }

  Future<void> _bootstrap() async {
    await _applyConfig();
    final apps = await AndroidAppBlocker.getInstalledApps(includeIcons: true);
    final blocked = await AndroidAppBlocker.getBlockedApps();
    final running = await AndroidAppBlocker.isRunning();
    final permissions = await AndroidAppBlocker.checkPermissions();
    if (!mounted) return;
    setState(() {
      _apps = apps;
      _blocked = blocked.toSet();
      _running = running;
      _permissions = permissions;
      _loading = false;
    });
  }

  Future<void> _applyConfig() {
    return AndroidAppBlocker.configure(
      BlockerConfig(
        title: 'Not right now',
        message: 'You asked this app to stay out of the way.',
        primaryButtonText: 'Take me back',
        secondaryButtonText: 'Open anyway',
        accentColor: const Color(0xFF4F46E5),
        useFlutterOverlay: _useFlutterOverlay,
        notificationTitle: 'Blocking is on',
        notificationText: 'Watching for apps you blocked.',
      ),
    );
  }

  Future<void> _refresh() async {
    final permissions = await AndroidAppBlocker.checkPermissions();
    final running = await AndroidAppBlocker.isRunning();
    final blocked = await AndroidAppBlocker.getBlockedApps();
    if (!mounted) return;
    setState(() {
      _permissions = permissions;
      _running = running;
      _blocked = blocked.toSet();
    });
  }

  Future<void> _toggle(InstalledApp app, bool blocked) async {
    if (blocked) {
      await AndroidAppBlocker.block([app.packageName]);
    } else {
      await AndroidAppBlocker.unblock([app.packageName]);
    }
    await _refresh();
  }

  Future<void> _start() async {
    try {
      await AndroidAppBlocker.start();
    } on PlatformException catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(error.message ?? 'Could not start blocking.')),
      );
    }
    await _refresh();
  }

  Future<void> _showUsage() async {
    final stats = await AndroidAppBlocker.getUsageStats(lookbackDays: 7);
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Last 7 days'),
        content: SizedBox(
          width: double.maxFinite,
          child: stats.isEmpty
              ? const Text('Nothing blocked yet.')
              : ListView(
                  shrinkWrap: true,
                  children: [
                    for (final s in stats)
                      ListTile(
                        dense: true,
                        title: Text(s.packageName),
                        subtitle: Text(
                          'opened ${s.openCount} · blocked ${s.blockedCount} · '
                          'bypassed ${s.bypassedCount}',
                        ),
                      ),
                  ],
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('App blocker example'),
        actions: [
          IconButton(
            onPressed: _showUsage,
            icon: const Icon(Icons.insights_outlined),
            tooltip: 'Usage',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                _PermissionCard(
                  permissions: _permissions,
                  onOverlay: AndroidAppBlocker.requestOverlayPermission,
                  onUsage: AndroidAppBlocker.requestUsageStatsPermission,
                  onBattery: AndroidAppBlocker.openBatteryOptimisationSettings,
                ),
                SwitchListTile(
                  value: _useFlutterOverlay,
                  title: const Text('Use the Flutter block screen'),
                  subtitle: const Text(
                    'Off: the built-in native screen, configured from Dart.',
                  ),
                  onChanged: (value) async {
                    setState(() => _useFlutterOverlay = value);
                    await _applyConfig();
                  },
                ),
                SwitchListTile(
                  value: _useSchedule,
                  title: const Text('Only block during working hours'),
                  subtitle: const Text(
                    'Runs a decision handler in a background isolate.',
                  ),
                  onChanged: (value) async {
                    setState(() => _useSchedule = value);
                    await AndroidAppBlocker.setBlockDecisionHandler(
                      value ? exampleShouldBlock : null,
                    );
                  },
                ),
                const Divider(height: 1),
                Expanded(
                  child: ListView.builder(
                    itemCount: _apps.length,
                    itemBuilder: (context, index) {
                      final app = _apps[index];
                      return CheckboxListTile(
                        value: _blocked.contains(app.packageName),
                        onChanged: (value) => _toggle(app, value ?? false),
                        title: Text(app.name),
                        subtitle: Text(app.packageName),
                        secondary: app.icon == null
                            ? const Icon(Icons.android)
                            : Image.memory(app.icon!, width: 36, height: 36),
                      );
                    },
                  ),
                ),
              ],
            ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _running
            ? () async {
                await AndroidAppBlocker.stop();
                await _refresh();
              }
            : _start,
        icon: Icon(_running ? Icons.stop : Icons.play_arrow),
        label: Text(_running ? 'Stop blocking' : 'Start blocking'),
      ),
    );
  }
}

class _PermissionCard extends StatelessWidget {
  const _PermissionCard({
    required this.permissions,
    required this.onOverlay,
    required this.onUsage,
    required this.onBattery,
  });

  final BlockerPermissions permissions;
  final VoidCallback onOverlay;
  final VoidCallback onUsage;
  final VoidCallback onBattery;

  @override
  Widget build(BuildContext context) {
    if (permissions.canBlock) {
      return const ListTile(
        leading: Icon(Icons.verified_user_outlined, color: Colors.green),
        title: Text('Permissions granted'),
      );
    }
    return Card(
      margin: const EdgeInsets.all(12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Two permissions are needed before blocking can start.'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                if (!permissions.usageStats)
                  FilledButton.tonal(
                    onPressed: onUsage,
                    child: const Text('Usage access'),
                  ),
                if (!permissions.overlay)
                  FilledButton.tonal(
                    onPressed: onOverlay,
                    child: const Text('Draw over apps'),
                  ),
                TextButton(
                  onPressed: onBattery,
                  child: const Text('Battery settings'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// The Flutter block screen
// ---------------------------------------------------------------------------

class _FlutterBlockScreen extends StatelessWidget {
  const _FlutterBlockScreen();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        backgroundColor: Colors.white,
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(
                  Icons.do_not_disturb_on_outlined,
                  size: 84,
                  color: Color(0xFF4F46E5),
                ),
                const SizedBox(height: 28),
                const Text(
                  'Not right now',
                  style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                FutureBuilder<String?>(
                  future: BlockOverlay.blockedAppLabel(),
                  builder: (context, snapshot) => Text(
                    snapshot.data ?? '',
                    style: const TextStyle(color: Color(0xFF667085)),
                  ),
                ),
                const SizedBox(height: 36),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: BlockOverlay.primaryAction,
                    child: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 14),
                      child: Text('Take me back'),
                    ),
                  ),
                ),
                TextButton(
                  onPressed: BlockOverlay.allowOnce,
                  child: const Text('Open anyway'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
