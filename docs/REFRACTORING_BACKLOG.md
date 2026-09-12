# CourierPilot refactoring backlog

This file is intentionally short. It gives future agents the cleanup order and the safety constraints around old compatibility code.

## Safety rule

Do not remove Wolt/Bolt parser branches just because they are marked `legacy`. Courier apps use staged/A-B UI rollouts. Remove parser compatibility only after replay tests or production diagnostics show the branch is no longer needed.

## Phase 1 — dead code and CI truth

- [x] Remove unreachable retired dashboard activities. The production launcher path is now `AppUpdateLauncherActivity` -> `CourierPilotDashboardActivity`; see `docs/LEGACY_CLEANUP_01573.md`.
- [x] Remove the v0.10 `LiveOfferAdvisor` implementation; `LiveAdvisorHub` uses `StableLiveOfferAdvisor`.
- [x] Make startup smoke follow the real launcher path. `.github/workflows/startup-smoke.yml` launches `AppUpdateLauncherActivity` and requires `CourierPilotDashboardActivity` to become active.
- [x] Remove stale hard-coded version names from CI artifacts. Release naming is resolved from `app/build.gradle.kts`.

## Phase 2 — arrival reminder reliability

- [x] Persist an armed reminder with TTL so process death does not lose it (`PendingArrivalReminderStore`).
- [x] Restore only while the reminder is still inside TTL and its saved access code still exists for the building.
- [ ] Complete direct regression coverage for superseded-delivery and explicit-cancellation paths. Process restart/store round-trip and TTL expiry are already covered by `AccessHintIntelligenceTest`.

## Phase 3 — entrance learning

- [x] Store successful real arrival points separately from geocoder results; only explicit positive arrival feedback records a learned sample.
- [x] Require repeated evidence before preferring a learned entrance point (`MIN_SAMPLES = 2`).
- [x] Use a robust median center with bounded outlier rejection before returning a learned entrance.
- [x] Fall back to normal destination geocoding whenever learned-entrance confidence is insufficient.

## Phase 4 — access-code quality

- [x] Add explicit `Works` / `Wrong / old` feedback actions to arrival notifications.
- [x] Track confirmation, rejection, last seen and confidence separately from raw observations.
- [x] Suppress stale low-confidence hints while preserving raw delivery evidence.
- [x] Never turn one rejection into a permanent blacklist; fresh live evidence can revive the code.

## Phase 5 — storage and naming cleanup

- [x] Add screenshot retention controls and storage-size visibility.
- [x] Migrate `euroPerKilometer` to currency-neutral `moneyPerKilometer` without changing scoring semantics.
- [x] Split large accessibility and live-advisor classes into capture/state/routing/presentation pieces where there is a real ownership boundary.
  - [x] Extract low-level courier Accessibility window/tree traversal into `OfferAccessibilitySurface`.
  - [x] Extract visible live-advisor tree inspection into `LiveAdvisorSurfaceInspector`.
  - [x] Extract Android screenshot acquisition/fallback/bitmap plumbing into `OfferScreenshotCapture`; OCR and persistence policy remain in `OfferAccessibilityService`.
  - [x] Extract Wolt capture-session state/frame accumulation into `WoltCaptureSession`; capture policy, route recovery and OCR timing remain in `OfferAccessibilityService`.
  - [x] Extract overlay view/gesture rendering into `LiveAdvisorOverlayView`; offer lifetime, scoring and cached decision state remain in `StableLiveOfferAdvisor`.
  - [x] Reassess remaining route coordination. `AutomaticWoltRouteCoordinator` already owns GPS, geocoding, Valhalla computation, prepared-route reuse and route outcomes. The Wolt code that remains in `OfferAccessibilityService` is Accessibility disclosure/semantics recovery and must stay next to capture policy; adding another route owner would only add callbacks and split one UI transaction across more classes.
  - Further extraction is allowed only when another cohesive responsibility becomes independently testable; class size alone is not a reason to move lifetime/scoring rules.
- [ ] Define the minimum supported upgrade baseline before deleting one-shot database repair revisions.
  - Current policy deliberately declares no minimum directly-upgradable historical version, so `AddressDataRepair` and `OfferDataRepair` remain supported compatibility code.
  - Do not invent a baseline during cleanup. The product support decision and baseline-to-current replay coverage must exist first; see `docs/UPGRADE_SUPPORT_POLICY.md`.
