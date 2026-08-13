# Reliability 0.4

This release focuses on missed Wolt/Bolt offer captures and richer structured offer data.

## Capture reliability

- Capture no longer depends on one particular Accessibility event. A lightweight watchdog keeps checking while an offer is pending.
- The service searches all Accessibility interactive windows for the pending courier package, not only `rootInActiveWindow`.
- On Android 14+ it captures the courier window with `takeScreenshotOfWindow(windowId, ...)`, which makes temporary System UI overlays / shade interaction less likely to break capture.
- Accessibility text is still the first source of truth. OCR is only used while the price is not visible to Accessibility.
- OCR retry interval backs off with offer age: about 1.2 s initially, 2.5 s after 15 s, and 5 s after 60 s.
- Stale asynchronous screenshot/OCR callbacks are rejected before writing a screenshot or database record.
- Notification updates use `StatusBarNotification.key` so an update of the same pending notification does not reset the 3-minute timer.
- A genuinely new notification from the same courier app can replace stale pending state.
- If the other courier platform posts an offer while one capture is active, one offer can wait in a small queue and is promoted after the current capture finishes.

## Structured offer data

Database schema v2 keeps existing history and adds optional structured fields:

- merchant name(s)
- pickup address(es)
- customer name(s)
- drop-off address(es)
- delivery count
- estimated min/max minutes

The parser understands the real Wolt layouts observed in single and stacked offers, including `Delivery from` and `2 deliveries from` screens. Bolt fields stay empty when Bolt does not expose them rather than inventing values.

Customer/drop-off data remains local in the app database and is not uploaded anywhere by Courier Offer Archive.

## Background reliability

Notification Listener and Accessibility are Android-managed services; the app intentionally does not add a permanent foreground service or permanent wake lock just to stay alive.

For courier use, configure the app manually in Android/OEM settings:

1. App info → Battery / Battery usage → choose Unrestricted / Allow background activity if available.
2. Exclude Courier Offer Archive from battery optimization if the firmware exposes that option.
3. On Realme / ColorOS / OxygenOS also check Auto launch / background activity controls in App info or battery settings.
4. Keep Notification Access and Accessibility enabled.

## Locked screen

Auto-open of Wolt/Bolt remains best-effort because modern Android can restrict background activity launches while locked. The capture transaction itself stays armed for up to three minutes. The watchdog can therefore resume capture after Wolt/Bolt becomes visible following unlock or manual opening.

This release does not use deprecated screen-wake hacks or attempt to bypass the keyguard.

## Build

The 0.4.0 code path completed `assembleDebug` successfully on GitHub Actions with JDK 17 and Android 35 before being fast-forwarded to `main`.
