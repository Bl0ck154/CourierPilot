# Courier Offer Archive

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

## Offer history

Every successfully captured priced offer stores:

- offer arrival time;
- platform (`Wolt` or `Bolt`);
- detected price;
- detected distance when available;
- best-effort restaurant name;
- original screenshot filename and MediaStore URI;
- raw Accessibility/OCR text for diagnostics and future parser improvements.

Tap a recent offer in the app to open its original screenshot.

## Statistics

The main screen currently shows:

- **Today** — all offers plus separate Wolt/Bolt summaries;
- **Last 7 days** — all offers plus separate Wolt/Bolt summaries;
- **Last 30 days** — all offers plus separate Wolt/Bolt summaries;
- offer count;
- average offer price;
- average detected distance;
- average €/km when distance is available;
- daily history for the last 30 days;
- Wolt/Bolt offer count per day;
- daily average price and €/km;
- the 40 most recent offers.

The database is local SQLite and does not depend on the screenshots for aggregation, so statistics remain fast as the archive grows.

## Setup

Install the APK and open **Courier Offer Archive**.

Enable:

- **Notification access** for the app;
- **Accessibility** → Courier offer screen capture.

Optional:

- Turn on **Automatically open Wolt/Bolt on offer notification**. The listener sends the courier notification's original `PendingIntent` when Android permits the background launch. Leave this off if you prefer to open the offer yourself.

## Diagnostics

The bottom of the main screen shows:

- last saved screenshot;
- last capture/OCR error or status;
- last merged text seen by Accessibility and ML Kit OCR.

This raw text is intentionally retained so restaurant/distance parsing can be tuned against real Wolt/Bolt screens without guessing their internal view IDs.

## Screenshot storage

Only the final frame where a price has been detected is written to the Gallery. Intermediate OCR probe screenshots exist only as temporary in-memory bitmaps and are recycled after recognition.

Saved images are written to:

`Pictures/CourierOffers`

## Limitations

- Requires Android 11 / API 30 or newer because the app uses `AccessibilityService.takeScreenshot()`.
- If Wolt/Bolt marks the offer window as secure, Android can reject screenshot capture.
- OCR uses the Google Play services ML Kit Latin text recognizer. On first use its model may need to become available before OCR succeeds; the capture loop keeps retrying while the offer remains armed.
- Captures are currently **serialized**: if a second Wolt/Bolt offer notification arrives while another offer is actively waiting for price/OCR, the active capture is kept rather than being overwritten. True concurrent two-platform capture is a future improvement.
- Restaurant extraction is intentionally best-effort until enough real Wolt/Bolt Accessibility/OCR samples are collected.
- Price parsing rejects `€0.00` and values outside a broad courier-offer range, but screens containing multiple plausible euro amounts still require real-world tuning.
- Distance extraction currently uses the first plausible distance shown by Accessibility/OCR; screens with multiple route distances require real-world tuning.
- Notification wording is matched using English/Lithuanian/Ukrainian/Russian task/order/delivery stems. If either app changes the wording completely, update `CourierNotificationListener.kt`.
- Auto-open depends on Android allowing the original notification `PendingIntent` to bring the courier app forward.

## Build

Current app version: `0.2.0`.

The debug APK was successfully built by GitHub Actions on 2026-08-13 with JDK 17 / Android 35. The workflow uploads it as the `app-debug` artifact.
