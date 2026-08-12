# Courier Offer Capture

Small Android companion app for automatically saving incoming Wolt/Bolt courier offer screens.

## How it works

1. `NotificationListenerService` watches Wolt/Bolt notifications.
2. A notification that looks like a new task/order **arms** a capture for that exact package for up to 20 seconds.
3. `OfferAccessibilityService` waits until that courier app is the active window.
4. It reads the accessibility tree and prefers to wait until a price or distance is visible.
5. If the app does not expose useful accessibility text, it falls back to a short render delay.
6. Android's official `AccessibilityService.takeScreenshot()` API captures the screen.
7. PNG is saved to `Pictures/CourierOffers`, so it appears in the Gallery without storage permissions.

The Wolt/Bolt APKs are **not modified**. No root, Shizuku, ADB, Lucky Patcher or MediaProjection prompt is required.

## Setup

Install the debug APK and open **Courier Offer Capture**.

Enable:

- **Notification access** for Courier Offer Capture.
- **Accessibility** → Courier offer screen capture.

Optional:

- Turn on **Automatically open Wolt/Bolt when an offer notification arrives**. The app sends the original notification `PendingIntent`, effectively doing the notification tap for you when Android permits it. If disabled, bring the courier app to the foreground yourself.

## Diagnostics

The main screen shows:

- last screenshot filename;
- last screenshot error;
- the last offer-screen text that Android Accessibility could see.

That last field is intentionally included for the next step. If Wolt exposes restaurant/distance/pay as accessibility text, an enriched notification can be built directly from the UI tree with no OCR. If not, OCR can be added only as a fallback over the captured screenshot.

## Screenshot timing

The service does not use a fixed Tasker-style `wait 1500 ms` sequence. A courier notification arms the service, UI events from that same package start the render check, and the service retries while the screen is loading. It captures as soon as price/distance-like content is visible, or after a short fallback delay if the UI hierarchy is opaque.

## Limitations

- Requires Android 11 / API 30 or newer because `AccessibilityService.takeScreenshot()` was added in API 30.
- If Wolt/Bolt marks the offer window as secure (`FLAG_SECURE`), Android can reject the screenshot. The app records the failure on its diagnostics screen.
- Notification wording is matched using English/Lithuanian/Ukrainian/Russian task/order/delivery stems. If either app changes the wording completely, add the new phrase to `CourierNotificationListener.kt`.
- Auto-open depends on Android allowing the original notification `PendingIntent` to bring the courier app forward.

## Build

The repository contains a GitHub Actions workflow that builds a debug APK. Locally, open the project in Android Studio or run Gradle with JDK 17.
