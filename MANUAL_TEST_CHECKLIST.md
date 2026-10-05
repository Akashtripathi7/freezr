# Manual test checklist (real devices)

Run on: Pixel (stock), Samsung (One UI), Xiaomi/Redmi (HyperOS/MIUI), OPPO or realme (ColorOS),
vivo, OnePlus, and one Android 8.0 device (minSdk 26). Use a debug build and `adb logcat -s Freezr FreezrA11y`.

## Onboarding & health
- [ ] Fresh install: each permission step shows its explanation; Accessibility and Usage show the disclosure *before* opening Settings.
- [ ] Returning from Settings marks the step "Granted".
- [ ] Notifications step shows the runtime dialog on Android 13+, and is already granted on older versions.
- [ ] Home health card lists exactly the missing items; each Fix button opens the right screen.
- [ ] OEM guide detects the brand; shortcut buttons open the autostart/battery screens (or are hidden if they don't resolve).

## Blocking
- [ ] Frozen app opened from launcher → overlay ≤ 300 ms, correct icon, name, reason, unfreeze time and live countdown.
- [ ] Opened from a notification, from recents, and from another app's deep link → blocked.
- [ ] Go home, back button, and recents button all leave the app; overlay never stacks or flickers on repeated taps.
- [ ] Rotation while overlay is shown → re-lays out correctly.
- [ ] Split screen with a frozen app → overlay; with two allowed apps → nothing.
- [ ] Frozen video app in PiP → PiP window covered, rest of the screen usable.
- [ ] Countdown reaching zero dismisses the overlay and the app is usable.
- [ ] Dialer, SMS, Settings, keyboard, launcher, clock can never be frozen (try adding them: they're not listed).

## Schedules
- [ ] Overnight Mon 23:00→08:00: active Mon 23:00, still active Tue 07:59, inactive Tue 08:00.
- [ ] Overlapping schedules: overlay shows the latest continuous end.
- [ ] Start = end → frozen all day.
- [ ] Preview text matches ("Frozen tonight 11:00 PM → 8:00 AM").
- [ ] Duplicate, delete, toggle; edits apply immediately.
- [ ] Pre-freeze notification N minutes before start; none if notifications are denied (no crash).

## Limits
- [ ] 5-minute limit on an app: notification at 80%, heads-up N minutes before, block at 100%, unfreeze at reset.
- [ ] Kill the process mid-session (`kill -9`) → used time is still correct afterwards.
- [ ] Change reset time → next day boundary follows it.

## Focus / tile / widget / notification
- [ ] Focus 15m from Home, widget and Quick Settings tile; remaining time correct everywhere.
- [ ] Focus ends on time with the app swiped away and the screen off.
- [ ] Status notification Pause/Resume toggles protection; under Strict Mode it opens the Strict screen instead.

## Strict Mode & emergency
- [ ] Strict on during a freeze: cannot pause, end focus early, edit/delete/disable schedules, loosen limits, add to the whitelist, or remove domains — from any surface.
- [ ] PIN: 5 wrong tries → lockout with backoff; correct PIN unlocks for 5 minutes.
- [ ] Delay: request → wait → confirm window → unlock; more than 3 requests per hour are refused; all attempts appear in the log.
- [ ] Device admin on + Strict locked: Freezr's App info, Accessibility page and Device admin page bounce back; the deactivation dialog shows the warning.
- [ ] Emergency: 30 s wait (cancellable) → phrase → 5-minute pass for that app only; passes counter decreases; a limit-frozen app gets extra minutes instead.

## Places, Wi-Fi, web
- [ ] Creating a place rule asks for location, then shows the background-location rationale.
- [ ] Walking into or out of a geofence (or a mock location) toggles the rule; reboot keeps it.
- [ ] Wi-Fi rule activates on connecting to the SSID and deactivates on disconnect.
- [ ] Website blocking in Chrome, Samsung Internet, Firefox, Brave, Edge: listed domain and subdomains blocked; typing in the URL bar is not blocked; Go back works.
- [ ] YouTube Shorts / Instagram Reels rules leave the surface; Selector health shows "Working". If the app changed its layout, nothing is blocked (fail open).

## Process states
Follow STATES.md rows 3–10 on each OEM device. Pay extra attention on Xiaomi, OPPO, vivo and Huawei:
- [ ] After 30 minutes in Doze with the screen off, a schedule still starts on time.
- [ ] After swiping Freezr from recents, blocking keeps working.
- [ ] After a reboot, blocking works after unlock without opening Freezr.

## Accessibility of the app itself
- [ ] TalkBack reads every control (day chips announce full day names and state; master switch announces its state).
- [ ] Font size at 200% → no clipped buttons; dark and light themes both readable.
