# Changelog

## 0.1.0

First release.

- Block and unblock Android apps by package name, persisted across restarts.
- Foreground service that detects the current app via `UsageStatsManager` and
  shows a full-screen block screen over blocked apps.
- Fully configurable built-in block screen: title, message, button labels,
  colours and icon.
- Optional Flutter-rendered block screen via a `vm:entry-point` entrypoint,
  with `BlockOverlay` for controlling it.
- Optional background block-decision handler, so blocking can depend on app
  state, a schedule or a server, with a configurable fallback when it cannot
  answer.
- Permission helpers for overlay, usage access and battery optimisation.
- Installed-app listing with optional icons.
- On-device usage counters: opens, blocks, bypasses and time blocked.
- Automatic restart after reboot, opt-out via `BlockerConfig.restartOnBoot`.
