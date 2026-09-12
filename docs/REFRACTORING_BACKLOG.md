# CourierPilot refactoring backlog

This file is intentionally short. It gives future agents the cleanup order and the safety constraints around old compatibility code.

## Safety rule

Do not remove Wolt/Bolt parser branches just because they are marked `legacy`. Courier apps use staged/A-B UI rollouts. Remove parser compatibility only after replay tests or production diagnostics show the branch is no longer needed.

## Phase 1 — dead code and CI truth

- [x] Remove unreachable retired dashboard activities.
- [x] Remove the v0.10 `LiveOfferAdvisor` implementation after confirming `LiveAdvisorHub` only instantiates `StableLiveOfferAdvisor`.
- [x] Make startup smoke follow the real `AppUpdateLauncherActivity` -> `CourierPilotDashboardActivity` path.
- [x] Remove stale hard-coded version names from CI artifacts; release naming resolves from Gradle.

## Phase 2 — arrival reminder reliability

- [x] Persist an armed reminder with TTL so process death does not lose it.
- [x] Restore a valid reminder dormant and resume it only after the same live `deliveryKey` is observed again; changed deliveries cancel it and the original TTL remains authoritative.
- [x] Cover process restart/TTL, superseded deliveries, `cancelUnless`, explicit cancellation, same-key restore/resume, and rejected-code restore suppression with regression tests.

## Phase 3 — entrance learning

- [x] Store successful real arrival points separately from geocoder results.
- [x] Require repeated evidence before preferring a learned entrance point.
- [x] Use a robust median and bounded outlier rejection.
- [x] Fall back to geocoder when confidence is insufficient.

## Phase 4 — access-code quality

- [x] Add explicit Works / Wrong-or-old feedback.
- [x] Track confirmation, rejection, last seen, and confidence separately from raw observations.
- [x] Suppress very stale low-confidence hints while preserving raw evidence.
- [x] Never turn one rejection into a permanent blacklist; future live evidence may relearn a code.

## Phase 5 — storage, naming and ownership cleanup

- [x] Add screenshot retention controls and storage-size visibility.
- [x] Migrate `euroPerKilometer` to currency-neutral `moneyPerKilometer` without changing scoring semantics.
- [x] Split large accessibility and live-advisor classes where there is a cohesive ownership boundary.
  - [x] Extract low-level courier Accessibility window/tree traversal into `OfferAccessibilitySurface`.
  - [x] Extract visible live-advisor tree inspection into `LiveAdvisorSurfaceInspector`.
  - [x] Extract Android screenshot acquisition/fallback/bitmap plumbing into `OfferScreenshotCapture`; OCR and persistence policy remain in `OfferAccessibilityService`.
  - [x] Extract Wolt capture-session state/frame accumulation into `WoltCaptureSession`; capture policy, route recovery and OCR timing remain in `OfferAccessibilityService`.
  - [x] Extract Wolt hot-price poll identity/throttle/drag scheduling into `WoltPricePoller`; Accessibility parsing and persistence policy remain in `OfferAccessibilityService`.
  - [x] Extract overlay view/gesture rendering into `LiveAdvisorOverlayView`; offer lifetime, scoring and cached decision state remain in `StableLiveOfferAdvisor`.
  - [x] Extract TextToSpeech ownership, queueing and offer speech formatting into `LiveAdvisorSpeech`; voice-enable policy and offer lifetime remain in `StableLiveOfferAdvisor`.
  - [x] Extract per-offer frozen decision-threshold snapshot/prewarm ownership into `LiveAdvisorDecisionThresholds`; `OfferDecisionEngine` scoring and live-card presentation remain unchanged.
  - [x] Reassess route ownership. `AutomaticWoltRouteCoordinator` already owns GPS, geocoding, Valhalla computation, prepared-route reuse and route outcomes. Wolt disclosure/semantics recovery remains in `OfferAccessibilityService` because it is part of the Accessibility capture transaction, not route computation.
  - Further extraction is allowed only when another cohesive responsibility becomes independently testable; class size alone is not a reason to move lifetime/scoring rules.
- [ ] Define the minimum supported upgrade baseline before deleting one-shot database repair revisions.
  - Current policy deliberately declares no minimum directly-upgradable historical version, so `AddressDataRepair` and `OfferDataRepair` remain supported compatibility code.
  - Do not invent a baseline during cleanup. The product support decision and baseline-to-current replay coverage must exist first; see `docs/UPGRADE_SUPPORT_POLICY.md`.
