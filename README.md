# CourierPilot

Android companion app for automatically archiving incoming Wolt/Bolt courier offers **only after a price is visible**, then building a local history and statistics from the captured offers.

## Core behavior

1. `NotificationListenerService` watches Wolt/Bolt new-task notifications.
2. A matching notification arms capture for the courier app.
3. `OfferAccessibilityService` waits until that courier app is the active window.
4. Accessibility text is checked for a real `€ / EUR` price.
5. If the courier UI does not expose the price to Accessibility, screenshots are taken **in memory only** and passed through on-device ML Kit OCR.
6. No image is saved while the price is missing.
7. As soon as a plausible non-zero price is detected, that current frame is saved to `Pictures/CourierOffers` and added to the local offer database.
8. If no price appears, the pending offer can remain armed for up to **3 minutes**. An offer that expires without a detected price is not saved.
9. Repeated notification updates do not reset an already active capture. While OCR/capture is active, another notification cannot overwrite that transaction.

The Wolt/Bolt APKs are not modified. No root, Shizuku, ADB, Lucky Patcher or MediaProjection prompt is required.

## 0.3.0 UI

The app now has three main screens:

- **Home** — capture health, today's offer metrics, 7-day summary, a contribution-style activity calendar and recent offers;
- **History** — up to 200 recent captured offers grouped by day; tapping one opens its saved screenshot;
- **Stats** — Today / 7 days / 30 days summaries, GitHub-style yearly contribution heatmap, recent offer activity by hour and Wolt/Bolt split.

Settings moved out of the dashboard. When both Notification Access and Accessibility are healthy, setup controls stay hidden and Home only shows a small `Capture active` status. If either permission is lost, an `Action required` card appears with the exact fix button.

Diagnostics are also hidden from the main screen and live under **Settings → Diagnostics**, where raw Accessibility/OCR text can be expanded only when needed.

## Offer history

Every successfully captured priced offer stores:

- offer arrival time;
- platform (`Wolt` or `Bolt`);
- detected price;
- detected distance when available;
- best-effort restaurant name;
- original screenshot filename and MediaStore URI;
- raw Accessibility/OCR text for diagnostics and future parser improvements.

## Statistics

Available aggregates include:

- offer count;
- average offer price;
- average detected distance;
- average €/km when distance is available;
- Today / 7-day / 30-day periods;
- Wolt vs Bolt split;
- daily activity for up to 365 days;
- contribution-style yearly heatmap;
- hourly activity based on recent captured offers.

Hourly activity is intentionally **not called work hours** because the app does not yet know exact shift start/end times.

## Setup

Install the APK and open **CourierPilot**.

Enable:

- **Notification access** for the app;
- **Accessibility** → Courier offer screen capture.

Optional:

- Turn on **Automatically open Wolt/Bolt on offer notification** in Settings. The listener sends the courier notification's original `PendingIntent` when Android permits the background launch.

## Screenshot storage

Only the final frame where a price has been detected is written to the Gallery. Intermediate OCR probe screenshots exist only as temporary in-memory bitmaps and are recycled after recognition.

Saved images are written to:

`Pictures/CourierOffers`

## Limitations

- Requires Android 11 / API 30 or newer because the app uses `AccessibilityService.takeScreenshot()`.
- If Wolt/Bolt marks the offer window as secure, Android can reject screenshot capture.
- OCR uses the Google Play services ML Kit Latin text recognizer.
- Captures are currently **serialized**: if a second Wolt/Bolt offer notification arrives while another offer is actively waiting for price/OCR, the active capture is kept rather than overwritten.
- Restaurant extraction is best-effort until more real Wolt/Bolt samples are collected. The UI filters obvious navigation labels such as `Close drawer` instead of presenting them as restaurants.
- Price parsing rejects `€0.00` and values outside a broad courier-offer range, but screens containing multiple plausible euro amounts still require real-world tuning.
- Distance extraction currently uses the first plausible distance shown by Accessibility/OCR.

## Build

Current app version: `0.4.0` (`versionCode 4`).

Signed release APKs are built only by manual workflow dispatch or a `v*` tag. Release-signing identity and verification details are documented in [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md).
