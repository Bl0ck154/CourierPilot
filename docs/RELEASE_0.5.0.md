# CourierPilot 0.5.0

This release turns the 0.4 capture prototype into a more diagnosable day-to-day courier tool while preserving the priced-offer capture rule.

## Capture reliability

- Privacy-safe bounded capture event log with stages such as notification, armed, window missing, price detected, OCR fallback, screenshot failure, stale callback and saved.
- Event log never stores customer names, addresses or raw Accessibility/OCR offer text.
- Pending capture resumes immediately on `ACTION_USER_PRESENT` after the device is unlocked.
- When auto-open is enabled, CourierPilot makes a best-effort attempt to reopen the pending courier app after unlock.
- Optional `Wake screen for offers` briefly wakes the display for approximately three seconds; it does not unlock or bypass the keyguard.
- Existing Android 14+ courier-window screenshot logic, adaptive OCR retry and stale-callback protection remain in place.

## Reliability center

A dedicated screen shows:

- Notification Listener status;
- Accessibility capture status;
- battery-optimization / Doze exemption status;
- Android background restriction status;
- current pending offer;
- last screenshot / last error;
- privacy-safe capture event log;
- shortcuts to Android battery, app-info, notification-listener and Accessibility settings;
- a shareable privacy-safe diagnostics report.

Realme / ColorOS / OxygenOS OEM background controls still require manual user approval in system settings.

## Rich offer details

Captured Wolt fields are shown on a dedicated Offer Details screen when available:

- expected price;
- route distance and €/km;
- estimated time;
- single vs stacked delivery count;
- merchant names;
- pickup addresses;
- customer names;
- drop-off addresses;
- original screenshot;
- optional raw Accessibility/OCR text for diagnostics.

Fields that Bolt does not expose remain empty rather than being inferred.

Customer and drop-off information stays only in the local CourierPilot SQLite database and is not included in the privacy-safe event log.

## True work-time tracking

CourierPilot no longer needs to pretend that first-offer-to-last-offer equals work time.

The Home screen has explicit `Start shift` / `End shift` controls. Statistics can therefore show true manually tracked work time for Today, 7 days and 30 days.

`Offers / tracked hour` is explicitly an offer-arrival metric, not completed-delivery earnings per hour.

## Statistics

In addition to existing price, distance, platform and hourly offer activity:

- interactive contribution heatmap day summaries;
- single vs stacked offer counts;
- total delivery stops represented by captured offers;
- top detected venues over 30 days;
- tracked work time for Today / 7 days / 30 days.

## Database

Schema version 3 adds a `shifts` table. Existing offer history migrates forward without deleting captured offers.

## Signing

The application remains `com.block154.courierpilot` and must continue using the permanent release certificate documented in `RELEASE_SIGNING.md`.
