# Freezr

An on-device Android app blocker: schedules, focus sessions, daily limits, place/Wi-Fi rules,
website and Reels/Shorts blocking, Strict Mode, emergency unlocks and local insights.
No network permission, no account, no analytics.

## Setup

Requirements: JDK 17+ (tested on JDK 23 and Android Studio's JBR), Android SDK 36.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM + Robolectric tests (93 tests)
./gradlew assembleRelease      # R8-minified, unsigned
```

Install and grant everything from a shell (handy for testing; real users use onboarding):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.freezr.app GET_USAGE_STATS allow
adb shell appops set com.freezr.app SCHEDULE_EXACT_ALARM allow
adb shell pm grant com.freezr.app android.permission.POST_NOTIFICATIONS
adb shell settings put secure enabled_accessibility_services \
  com.freezr.app/com.freezr.app.platform.accessibility.FreezrAccessibilityService
```

Stack: Kotlin 2.2, Compose + Material 3, Navigation Compose, Hilt, Room (schema v2, exported),
DataStore, WorkManager, AlarmManager, Glance, Quick Settings tiles, Play Services Location.
`compileSdk`/`targetSdk` = 36 on AGP 8.13 (see Known limitations).

## Architecture

```
            ┌──────────────────────────── ui (Compose, ViewModels → StateFlow) ───────────────────────────┐
            │ Home · Schedules · Apps · Insights · Strict · Emergency · Rules · Web · Settings · Onboarding│
            └───────────────┬─────────────────────────────────────────────────────────────┬──────────────┘
                            │ writes (guarded by StrictGuard)                             │ observes
            ┌───────────────▼──────────────── data ───────────────────────────────────────▼──────────────┐
            │ Room (rules, passes, logs, usage) + DataStore (settings)  ──►  FreezeStateRepository      │
            │ Repositories: Schedule, Limit, Emergency, Strict, ContextRule, Domain, FreezeControls     │
            │          every write ──► RuleChangeNotifier                 snapshot: StateFlow<EngineContext>│
            └───────────────┬───────────────────────────────────────────────────────────┬──────────────┘
                            │                                                           │
            ┌───────────────▼──────────── domain (pure Kotlin, no Android) ─────────────▼──────────────┐
            │ FreezeDecisionEngine.evaluate / nextTransitionAfter / activeFreezes                       │
            │ AlarmPlanner · TimeWindows · UsageAggregator · PinHasher · Strict/Emergency policies      │
            └───────────────▲───────────────────────────────────────────────────────────▲──────────────┘
                            │ plan                                                      │ evaluate (cached snapshot)
            ┌───────────────┴──────────────── platform ─────────────────────────────────┴──────────────┐
            │ EnforcementCoordinator.replan(): snapshot → AlarmPlanner → AlarmScheduler (exact alarms)  │
            │                                   → EnforcementBus → FreezrAccessibilityService → overlay │
            │                                   → status notification · widget · QS tiles               │
            │ reconcileAll(): whitelist · usage · geofences · Wi-Fi · housekeeping, then replan()       │
            │ Triggers: app open, a11y connect, alarms, boot, locked boot, update, time/tz, watchdog    │
            └──────────────────────────────────────────────────────────────────────────────────────────┘
```

Core rule: **nothing that affects blocking lives only in memory**. The accessibility service holds a
cached snapshot derived from Room + DataStore (plus the system's own UsageStats store) and can rebuild
it at any moment. Transitions are absolute `Instant`s planned from wall-clock rules with the zone's
DST rules, armed with `setExactAndAllowWhileIdle`.

Decision precedence (`FreezeDecisionEngine`): protected/own app → master off → emergency pass →
Quick Freeze → schedule → daily limit → location/Wi-Fi rule → allowed.

Key packages:

| Package | Contents |
|---|---|
| `domain` | engine, time windows, alarm planner, usage aggregation, PIN hashing, policies (unit-tested, JVM only) |
| `data` | Room entities/DAOs/migrations, settings, repositories, `StrictGuard` |
| `platform` | accessibility service + overlay, inspectors, alarms, receivers, notifications, health, OEM guide, location/Wi-Fi, widget, tiles, WorkManager |
| `ui` | Compose screens, theme, navigation |

## Permissions explained

| Permission | Why | If denied |
|---|---|---|
| Accessibility service | See which app is in front and draw the freeze screen (`TYPE_ACCESSIBILITY_OVERLAY`, no overlay permission). Optional content reading for web/in-app rules and Strict-Mode Settings protection. | Nothing can be frozen; health card + high-priority notification. |
| `PACKAGE_USAGE_STATS` (Usage access) | Per-app foreground time for daily limits and insights. | Limits never trigger; insights empty; health card. |
| `POST_NOTIFICATIONS` | Pre-freeze heads-up, limit warnings, status, problem alerts. | Silently skipped; health card. |
| `SCHEDULE_EXACT_ALARM` | On-the-minute transitions in Doze. | Falls back to inexact while-idle alarms; health card. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm alarms/geofences after reboot. | — |
| Location (fine, coarse, background) + Wi-Fi/network state | Only for place / Wi-Fi rules; requested when such a rule is created, background with a rationale. | Those rules stay inactive; health card. |
| Device admin (optional) | Uninstall speed bump under Strict Mode. No policies are used. | Feature off. |
| Battery-optimisation exemption | User is sent to the system list (no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). | Health card + OEM guide. |

Not requested: `INTERNET` (explicitly removed), `QUERY_ALL_PACKAGES` (launcher-intent `<queries>` suffices),
`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE` (WorkManager's optional foreground service is removed).

## Play Console declarations

**Accessibility API (Permissions declaration → Accessibility)**

> Freezr is a digital-wellbeing app that lets users freeze apps they choose during schedules, focus
> sessions, daily limits and place/Wi-Fi rules they configure. The AccessibilityService is the core
> functionality: it detects which app is in the foreground (TYPE_WINDOW_STATE_CHANGED) and shows a
> full-screen "This app is frozen" overlay with a Go Home button. If the user opts in, it also reads
> the URL bar of supported browsers to block websites the user listed, recognises specific short-video
> screens (e.g. YouTube Shorts) inside apps the user selected, and, under the user-enabled Strict Mode,
> recognises Freezr's own App info / Accessibility / Device admin pages to prevent impulsive disabling.
> No data accessed through the API is stored, logged, shared or transmitted; the app has no internet
> permission. A prominent in-app disclosure is shown and consent obtained before the user is sent to
> Accessibility settings. `isAccessibilityTool` is false.

**Usage access (`PACKAGE_USAGE_STATS`)**

> Used to compute per-app daily screen time on the device to enforce user-set daily limits and to show
> the user their own usage insights. Data never leaves the device. Disclosed in-app before the user
> opens the Usage access settings screen.

**Background location**

> Used only when the user creates a place or Wi-Fi rule ("freeze games while I'm at school"). Geofence
> transitions and the connected Wi-Fi network determine whether the rule is active, including when the
> app is closed. Requested only at rule creation, after an in-app rationale. Location is processed on
> the device and never stored or shared.

**Exact alarms (`SCHEDULE_EXACT_ALARM`)**

> Core user-facing feature: schedules the user creates must start and end at the exact minute
> (e.g. a 23:00 sleep schedule). Requested in onboarding with an explanation.

**Data safety form**: no data collected, no data shared; data is not encrypted in transit because it is
never transmitted; users can delete data by uninstalling / clearing storage.

**Package visibility**: `QUERY_ALL_PACKAGES` is not used. If it is ever added, declare under
"Permissions declaration → All installed apps": *"Core functionality is letting the user choose any
installed app to freeze; the launcher-intent query is insufficient because …"*.

## Documents

- [STATES.md](STATES.md) — process-state matrix: implementation and manual tests per row
- [MANUAL_TEST_CHECKLIST.md](MANUAL_TEST_CHECKLIST.md) — real-device / OEM checklist
- [PRIVACY_POLICY.md](PRIVACY_POLICY.md) — privacy policy draft

## Known limitations

- **SDK level**: built with compile/target SDK 36 on AGP 8.13. Android 37 is available; moving to it
  needs AGP 9.x and re-verifying Robolectric/Hilt support. Change `compileSdk`/`targetSdk` in
  `app/build.gradle.kts` once the toolchain is bumped.
- **Force stop** by the user disables the accessibility service until the app is opened again. No app
  can prevent this; Freezr detects it on next open, via the watchdog and on boot, and notifies.
- **Website and in-app blocking is best effort.** Selectors live in
  `app/src/main/assets/blocking_rules.json` (versioned; a newer file at
  `files/rules/blocking_rules.json` overrides it). Target apps change view IDs; everything fails open.
  No remote updater is shipped (no network permission).
- **Picture-in-picture**: a frozen app in PiP is covered by a small overlay over the PiP window only
  (a full-screen block would trap the user); its audio may continue.
- **Partner approval** is behind `PartnerApprovalProvider` with `LocalPartnerApprovalStub` (needs a
  backend; shown as unavailable in the UI).
- **Usage numbers** come from Android's UsageStats; OEMs that drop or delay events make limits
  slightly late. Daily totals older than the system's retention are kept from Freezr's own snapshots.
- **Wi-Fi SSID** reading requires fine + background location on Android 10+ and location services on.
- **Direct boot**: before first unlock only alarms are re-armed (Room/DataStore are credential-encrypted);
  enforcement starts when the accessibility service binds after unlock.
- **Instrumented tests**: none are needed for CI; DAO, migration and alarm tests run on Robolectric.
  Overlay/accessibility behaviour is covered by the manual checklist.
- Some short computed phrases in `ui/components/Format.kt` (e.g. "Every day", "Frozen tonight …")
  are in Kotlin rather than `strings.xml`; everything else is externalised.

## License

Copyright 2026 Akash Tripathi. Licensed under the [Apache License, Version 2.0](LICENSE).
