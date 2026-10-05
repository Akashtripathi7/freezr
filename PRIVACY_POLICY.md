# Freezr Privacy Policy (draft)

_Last updated: 5 October 2026_

**In short: everything stays on your phone.** Freezr has no internet permission, no account, no ads,
no analytics and no third-party tracking SDKs. We (the developer) receive no data from the app.

## What Freezr accesses and why

| Data | Purpose | Stored? |
|---|---|---|
| Which app is in the foreground (Accessibility API) | Decide whether to show the freeze screen | No: processed in memory |
| Browser address bar text (only if you enable website blocking) | Match it against the websites you listed | No |
| Specific on-screen elements in apps you select (only if you enable in-app rules) | Detect Shorts/Reels-style screens and leave them | No |
| Freezr's own pages in Settings (only under Strict Mode) | Stop you from turning Freezr off on impulse | No |
| App usage times (Usage access) | Daily limits and your insights | Daily per-app totals, up to 90 days |
| List of apps with a launcher icon | Let you choose apps to freeze | No (read live) |
| Location / Wi-Fi network name (only with place or Wi-Fi rules) | Know whether a rule is active | Only "inside / not inside" per rule; no location history |

## What is stored on the device

Your rules and settings, counts of blocked opens, emergency-unlock and Strict-Mode unlock logs, and
daily usage totals. A Strict-Mode PIN is stored only as a salted PBKDF2 hash, encrypted with an
Android Keystore key. Records older than 90 days are deleted automatically. Freezr's data is excluded
from cloud backup and device-to-device transfer.

## Sharing

None. Freezr cannot send data anywhere: it has no network permission.

## Your choices

You can revoke any permission in Android Settings; Freezr keeps working with reduced features and
tells you what's affected. To delete all data, clear Freezr's storage or uninstall it.

## Children

Freezr does not knowingly collect any information from anyone, including children.

## Contact

[developer contact email]
