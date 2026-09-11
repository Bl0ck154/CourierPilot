# CourierPilot 0.15.73 legacy cleanup

This refactor removes code paths that are no longer reachable from the production application while preserving parser compatibility for older and A/B-tested Wolt/Bolt UI variants.

## Removed production code

- `MainActivity` — original pre-dashboard View-based UI.
- `CourierPilotActivity` — later View-based dashboard generation.
- `CourierPilotHomeActivity` — subsequent View-based home/history/stats generation.
- `CourierPilotComposeActivity` — deprecated compatibility redirect to `CourierPilotDashboardActivity`.
- `LiveOfferAdvisor` — v0.10 live-overlay implementation superseded by `StableLiveOfferAdvisor` on 2026-08-21.

The current production UI remains `AppUpdateLauncherActivity` -> `CourierPilotDashboardActivity`. The live overlay remains `LiveAdvisorHub` -> `StableLiveOfferAdvisor`.

## Compatibility intentionally retained

Do not remove parser branches merely because they are labeled `legacy`. Courier apps can roll out different UI strings/designs at different times. Old and modern Wolt earnings labels, delivery-count formats, and other observed card variants remain supported unless replay/telemetry proves they are no longer needed.

## CI cleanup

- Startup smoke now exercises the real launcher and verifies that it reaches `CourierPilotDashboardActivity`.
- Debug artifact naming no longer hardcodes the obsolete `0.14.2` version.
- Regression tests that were bundled under the old v0.10 advisor test name were moved to lifecycle/route-focused tests; only the retired advisor economics tests were removed.

## Follow-up refactors

Keep these separate from this dead-code cleanup so behavioral regressions remain easy to isolate:

1. Persist armed arrival reminders across process death.
2. Learn real entrance/arrival coordinates from completed deliveries instead of relying only on geocoded building centers.
3. Add access-code confidence/staleness and explicit Works/Wrong feedback.
4. Add screenshot retention/storage controls.
5. Migrate misleading `euroPerKilometer` naming to currency-neutral `moneyPerKilometer` without changing scoring behavior.
6. Split large accessibility/live-advisor classes into state, capture, routing, and presentation units.
7. Establish an explicit retirement policy for one-shot `AddressDataRepair` / `OfferDataRepair` migrations after the minimum supported upgrade baseline moves forward.
