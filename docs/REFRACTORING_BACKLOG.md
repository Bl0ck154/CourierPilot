# CourierPilot refactoring backlog

This file is intentionally short. It gives future agents the cleanup order and the safety constraints around old compatibility code.

## Safety rule

Do not remove Wolt/Bolt parser branches just because they are marked `legacy`. Courier apps use staged/A-B UI rollouts. Remove parser compatibility only after replay tests or production diagnostics show the branch is no longer needed.

## Phase 1 — dead code and CI truth

- Remove unreachable retired dashboard activities.
- Remove the v0.10 `LiveOfferAdvisor` implementation after confirming `LiveAdvisorHub` only instantiates `StableLiveOfferAdvisor`.
- Make startup smoke follow the real launcher path.
- Remove stale hard-coded version names from CI artifacts.

## Phase 2 — arrival reminder reliability

- Persist an armed reminder with TTL so process death does not lose it.
- Restore only if the delivery identity still matches and TTL has not expired.
- Add tests for process restart, superseded deliveries, and cancellation.

## Phase 3 — entrance learning

- Store successful real arrival points separately from geocoder results.
- Require repeated evidence before preferring a learned entrance point.
- Use a robust center/median and bounded outlier rejection.
- Fall back to geocoder when confidence is insufficient.

## Phase 4 — access-code quality

- Add explicit Works / Wrong-or-old feedback.
- Track confirmation, rejection, last seen, and confidence separately from raw observations.
- Suppress very stale low-confidence hints while preserving raw evidence.
- Never turn one rejection into a permanent blacklist; future live evidence may relearn a code.

## Phase 5 — storage and naming cleanup

- [x] Add screenshot retention controls and storage-size visibility.
- [x] Migrate `euroPerKilometer` to currency-neutral `moneyPerKilometer` without changing scoring semantics.
- [ ] Split large accessibility and live-advisor classes into capture/state/routing/presentation pieces.
  - [x] Extract low-level courier Accessibility window/tree traversal into `OfferAccessibilitySurface`.
  - [x] Extract visible live-advisor tree inspection into `LiveAdvisorSurfaceInspector`.
  - [ ] Extract Android screenshot acquisition/fallback/bitmap plumbing from `OfferAccessibilityService`; keep OCR, Wolt state and persistence policy outside it.
  - [ ] Extract Wolt capture-session state/frame accumulation from `OfferAccessibilityService` after the screenshot boundary is stable.
  - [ ] Extract overlay view/gesture rendering from `StableLiveOfferAdvisor`; keep offer lifetime and decision state in the advisor.
  - [ ] Reassess whether the remaining route coordination needs another owner after those mechanical splits. Do not move route/scoring rules merely to satisfy a class-count target.
- [ ] Define the minimum supported upgrade baseline before deleting one-shot database repair revisions.
  - Current policy deliberately declares no minimum directly-upgradable historical version, so `AddressDataRepair` and `OfferDataRepair` remain supported compatibility code.
  - Do not invent a baseline during cleanup. The product support decision and baseline-to-current replay coverage must exist first; see `docs/UPGRADE_SUPPORT_POLICY.md`.
