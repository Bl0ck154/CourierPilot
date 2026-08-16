# CourierPilot 0.14.0

This release cleans up the user-facing control surface and tightens trust around background state and screenshot storage.

## Preserved from 0.13.1

The parallel 0.13.1 address-memory work remains intact. It gates durable address persistence to confirmed delivery/offer context and is intentionally not replaced by the 0.14 changes.

## Online presence

- A sticky/foreground-service notification such as `Courier app is running` no longer means that the courier is Online.
- Real offers and explicit wording such as `Waiting for orders` remain strong Online evidence.
- Explicit `Go online` / `Off duty` style wording remains Offline evidence.
- Legacy `ONLINE · persistent notification` state from older builds self-heals to `No signal` after upgrade and is removed from automatic work-time accounting.

## Optional offer screenshots

- Saving PNG screenshots to `Pictures/CourierOffers` is **off by default**.
- OCR still works when screenshot saving is off: CourierPilot captures an in-memory frame, runs ML Kit, and immediately recycles the bitmap.
- If Accessibility already exposes the price, no final screenshot is taken when storage is disabled.
- Offer details explicitly handle offers that have no saved proof image.

## Settings

The main Compose Settings screen now exposes only user-facing controls:

- Live offer card
- Voice readout
- Auto-open real offer notifications
- Wake screen for offers
- Wolt calculated route
- Bolt calculated route
- Save offer screenshots
- Notification access
- Accessibility capture
- Reliability Center

Route toggles are disabled with a simple status when the private route service is not provisioned. The server URL/token are not shown in normal Settings.

## Reliability Center

Reliability Center is now a Compose/Material 3 screen matching the rest of CourierPilot. It focuses on:

- Notification listener health
- Accessibility health
- Battery optimization
- Android background restriction
- Pending capture / last capture / last error
- Screenshot-storage status
- Privacy-safe event log and sharing

Research-only Valhalla/Bolt controls were removed from normal Reliability UI.

## Developer tools

Internal research controls are hidden by default. Developer mode is unlocked by tapping the version seven times in Settings. Only then does CourierPilot expose Developer Tools, including manual route research and Bolt research diagnostics.

## Version

- `versionName`: `0.14.0`
- `versionCode`: `27`
