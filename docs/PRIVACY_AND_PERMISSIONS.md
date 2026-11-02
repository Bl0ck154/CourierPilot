# CourierPilot privacy and permissions

CourierPilot is an **unofficial** Android assistant, not affiliated with Wolt or Bolt. It needs sensitive access because it interprets courier-app offer screens. This page describes its behavior; it is **not** a claim of independent privacy certification.

## Data stored on the phone

Priced offer history and proof screenshots, customer/venue addresses, door/access-code reminders, route research and optional ride traces are stored locally. Some proof screenshots are written to `Pictures/CourierOffers`: other apps that have access to pictures, and your own photo-sync service, may be able to access or sync them. Avoid showing personal screenshots in public bug reports.

Android app-data backup and device-transfer extraction are disabled in the manifest to protect local offer history and stored door codes. **This also means that reinstalling, changing devices or clearing app data can permanently erase your history.** The Valhalla token is stored in `noBackupFilesDir`.

## Permission inventory

| Android access | Why CourierPilot uses it | Optional? |
| --- | --- | --- |
| Notification listener | Detect courier-app offer and availability signals | Needed for notification-based capture |
| Accessibility service | Read visible courier-app text and capture screens for OCR | Needed for screen-based capture; never taps Accept/Decline |
| Foreground / coarse location | Start-point GPS for route estimation and explicit ride traces | Needed only for those functions |
| Background location | Optional precise arrival-time access-code reminders; ETA fallback works without it | Declared in current APK; normal setup does not request it |
| Notification permission | Display ongoing tracing, reminders and update alerts | Needed for corresponding notifications on Android 13+ |
| Foreground service location | User-initiated ride recording with an ongoing notification | Only while recording |
| Internet | Optional routing, update checks, and enabled remote diagnostics | Needed for network-backed features |
| Install packages | User-triggered installation of signed APK updates downloaded from GitHub | Only for in-app sideload updates |

## Network requests

- With routing enabled, geocoded **coordinates of the route stops** are sent to the **configured HTTPS Valhalla server** along with the bearer token. The gateway operator can see route coordinates; routing is *not* purely on-device.
- With remote diagnostics enabled, bounded technical metadata is sent to the project's endpoint; diagnostics are off by default. The code is intended to exclude screenshots, raw OCR text, customer names, exact addresses and GPS points from these reports.
- App update checks/downloads contact GitHub and may expose ordinary network metadata such as IP address to GitHub.
- Optional market intelligence and other network-backed features are controlled separately; confirm their in-app settings before enabling them.

## Distribution and Google Play

The current GitHub APK includes `REQUEST_INSTALL_PACKAGES` for self-updates, and `ACCESS_BACKGROUND_LOCATION` for an optional arrival-reminder path. **Do not submit this variant to Google Play without a fresh policy review.** Google Play restricts both permissions. A future Play-specific build should remove or replace prohibited sideload-update behavior and obtain an appropriate background-location review, or eliminate that permission.

See [Google Play sensitive-permission policy](https://support.google.com/googleplay/android-developer/answer/16558241).

## Reporting

Use [the privacy-safe Reliability report](../.github/ISSUE_TEMPLATE/bug_report.yml) and redact all personally identifiable delivery information before filing an issue. Never publish a private routing token, customer address or door code.
