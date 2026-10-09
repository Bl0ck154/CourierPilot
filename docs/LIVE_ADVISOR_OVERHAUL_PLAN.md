# Live Advisor Overhaul Plan (0.16.x)

Status: approved for implementation by agents
Base version: 0.15.94 (`main` @ 02ef288)
Target: 0.16.0 (waves 1–2), 0.17.0 (wave 3)

This document is the single source of truth for the agents implementing the live-advisor
overhaul. Every agent MUST read the whole document (not only its own workstream) before writing
code, because the workstreams share contracts (section 3).

---

## 0. Problem statement (from real courier use, Bolt + Wolt)

1. The floating live card ("overlay") lags and flickers. Swipe-to-dismiss feels broken: the card
   half-moves, snaps, blinks, sometimes re-appears after being swiped away.
2. Swiping the card away changes offer state. After a swipe/re-appearance the verdict emoji for the
   **same offer** changes (👍 → 😐 → 👎). Swipe must be a purely visual action.
3. The card is too big and positioned over Bolt's top controls (menu button on the left, `Decline`
   on the right). It has a mostly empty header row. Needs to be ~15% narrower, ~5% lower, more
   compact, and must work on different phones/displays.
4. The card is often slow: spinner for a long time, sometimes forever (Bolt multi-drop offers).
5. Bolt does not expose the customer address on the offer screen, only a map. CourierPilot recovers
   customer pins from a screenshot and projects them to coordinates; this is often wrong (e.g.
   0.34 km walking for a delivery that is really 1–2 km away).
6. Multi-stop Bolt offers (1 restaurant × 2 customers, 2 × 1, 2 × 3, …) are not recovered reliably.
7. For debugging, the courier wants to see (tiny font, in the card) the pickup address and the
   recovered customer address, to judge whether the recovery was right.

## 1. Root-cause analysis (verified in code)

### RC-1 Bolt user-dismiss tombstone never matches → card re-appears
`LiveOfferUserDismissalPolicy.isSameOffer` (`LiveOfferUserDismissal.kt`) requires **two** of
`priceCents / distanceMeters / deliveryCount` (or a Wolt-only route fingerprint). Bolt offers
expose no distance, and usually no explicit delivery count, so only the price is comparable →
`isSameOffer == false`. After a swipe, `LiveAdvisorHub.onUserDismissedOffer` clears
`currentOffer`/`pendingPreview`, the advisor is no longer "tracking", so
`OfferAccessibilityService.attemptCapture` resumes passive Bolt discovery OCR (every ~3 s,
`OfferDiscoveryOcrPolicy.BOLT_ACTIVE_RESCAN_MS`). `armFromVisibleOffer` → `isUserDismissedOffer`
is false → `tryRestoreRecentOffer`/re-arm → a **new** card generation appears.

### RC-2 Verdict is recomputed on every card re-creation
`finalPresentationLocked` only protects one *generation*. Any re-creation (RC-1, temporary hide →
history restore, duplicate restore) runs routing and scoring again:
* `LiveAdvisorDecisionThresholds.snapshotFor` freezes **cold-start** thresholds
  (`currency_cold_start_frozen`) if the per-offer background prewarm has not finished yet; the next
  generation of the same offer usually gets **adaptive** thresholds → different band/emoji for the
  same €/km.
* After the first route, `MarketIntelligence.onRouteResolved` inserts the offer into market
  history, so a re-created card is partially judged against itself.
* A Bolt re-route uses a newer GPS fix → slightly different distance.

### RC-3 Capture suppression flickers the card
`OfferScreenshotCapture` calls `LiveAdvisorHub.setCaptureSuppressed(true/false)` around every
display screenshot (always on API < 34, and on the display fallback that Android 16/ColorOS hits
often). For non-Wolt platforms `LiveAdvisorOverlayView.setCaptureSuppressed` sets `alpha = 0` then
`alpha = 1` → visible blink. It also overrides the swipe alpha/translation mid-gesture.

### RC-4 Main-thread jank during gestures
`OfferScreenshotCapture.toBitmap` (hardware → ARGB copy of a full-screen bitmap) and screenshot /
OCR callbacks run on the main executor, the same thread that delivers overlay touch events.
Swipe has no velocity (fling) support, a 16%-of-width distance threshold and a hard snap-back.

### RC-5 Layout wastes space and ignores the courier app's controls
`LiveAdvisorOverlayView.ensure()`: width = screen − 24 dp, default `y = 48 dp`, a full header row
(`CourierPilot · version` + `×`), `RATE_MIN_WIDTH_DP = 176`. Nothing checks where Bolt/Wolt put
their own buttons. A saved `overlayYPx` from an old drag overrides any new default.

### RC-6 Bolt pickup-only route = infinite spinner
`AutomaticBoltRouteCoordinator` returns `BoltRouteScope.PICKUP_ONLY` when not all customer pins are
recovered. `StableLiveOfferAdvisor.updateBoltRoute` then calls `renderProgressiveDecision`, which
keeps `setDecisionLoading()` because routing is enabled → the spinner never ends (see 3rd
screenshot: `🚶 0.34 km 🚲 0.75 km` + spinner).

### RC-7 Bolt map projection is ill-conditioned
`BoltMultiStopMapRecovery.selectTransform` builds the screen→geo transform from exactly two
anchors: the current-location dot ↔ GPS fix, and a pickup pin ↔ geocoded pickup address. It solves
**scale and rotation** (up to ±45°) from them. The courier usually stands next to the restaurant,
so the two anchors are 10–40 px / 30–150 m apart. GPS error (10–30 m) + geocoding error (20–50 m) +
pin-tip error (±3–5 px) over such a short baseline gives scale errors of 2–5× and large rotation
errors, amplified for customer pins 300–600 px away.
Additional weaknesses in `BoltScreenshotMarkerExtractor`:
* map bottom is a fixed `0.72 × height` (depends on phone aspect ratio and sheet height);
* green park/POI icons can be confused with customer pins (no shape check);
* cyan current-location dot overlapping a blue pickup pin distorts clusters/tips;
* the marker source is the persisted proof screenshot, which can be taken mid map-zoom animation;
* stop ordering is nearest-neighbour, although Bolt draws explicit connecting lines
  (blue: courier→pickup, green: pickup→customer→customer).

### Useful facts
* Bolt's map (Mapbox) is **north-up** in all real screenshots (labels horizontal, Neris river north
  of the old town). Rotation can be fixed to 0°.
* Bolt shows ETAs: `~6 min` under the pickup row, `~10 min` on the customer row, and a total in the
  accept button (`15 min, 2,41 €`). These are an independent distance prior.
* After acceptance Bolt reveals the real customer address; `DeliveryScreenDetailsExtractor` and
  `DeliveryLifecycleTracking` already parse delivery screens → ground truth for recovery accuracy.
* The map shows OCR-able labels (POIs, districts, stations, parks) spread across the whole map →
  far better anchors than GPS + pickup.

---

## 2. Global rules for every agent

1. **Scope**: implement only your workstream. Only edit files listed as *owned* by it. If you
   truly need a change in a file owned by another workstream, keep it minimal (a few lines), and
   describe it explicitly in the commit message (`cross-stream: <file>: <why>`).
2. **Branch**: `feat/la-<ws-id>-<short-name>` from the latest `origin/main`
   (e.g. `feat/la-a-offer-session`).
3. **No version bump**, no release notes, no tag. The release workstream (R) does that.
4. **Privacy** (CONTRIBUTING.md): never write customer names, customer/drop-off addresses, or
   customer coordinates into `CaptureEventLog`, `RemoteDiagnostics` or any uploaded data. Pickup
   (restaurant) addresses are allowed only where they were already logged before. The debug line
   (WS-E) is on-screen only.
5. **Tests**: every behavioural change needs unit tests (JUnit / Robolectric, same style as
   `app/src/test/java/com/block154/courierpilot/*Test.kt`). Prefer extracting pure `object`
   policies (like `OverlayGestureAxisPolicy`, `LiveOfferTransactionPolicy`) so logic is testable
   without Android windows. Do not delete or weaken existing tests; if an existing test encodes the
   old (buggy) behaviour, update it and say why in the commit.
6. **Validation before merge**: `gradle testDebugUnitTest assembleDebug` (CI uses Gradle 8.10.2,
   JDK 17, Android SDK platform 35 / build-tools 35.0.0). All green.
7. **Code style**: match surrounding code (Kotlin, `internal`, KDoc explaining *why* with real-world
   evidence, constants in `companion object`/`private const val`). Keep comments short.
8. **Merge procedure** (agents merge themselves):
   ```bash
   git fetch origin main
   git merge origin/main            # resolve conflicts; never drop the other stream's changes
   gradle testDebugUnitTest assembleDebug
   git push -u origin <your-branch>
   # Preferred: open a PR into main, wait for CI green, merge it (merge commit).
   # If no GitHub PR tooling is available: fast-forward main yourself:
   git push origin HEAD:main        # if rejected: fetch + merge origin/main again, re-test, retry
   ```
   Never force-push `main`. Never rewrite another stream's commits.
9. When done, report: what changed, tests added, anything deferred, cross-stream edits.

---

## 3. Shared contracts between workstreams

These APIs are created by one stream and consumed by another. The **producer** must create them
exactly as named (extra parameters with defaults are fine).

| Contract | Producer | Consumer |
|---|---|---|
| `LiveAdvisorOverlayView.applyDebugLines(lines: List<String>)` — tiny text area under the main row; `GONE` when list empty | WS-B | WS-E |
| `LiveAdvisorOverlayView.screenRect(): android.graphics.Rect?` — current on-screen window bounds, `null` when detached | WS-B | WS-D |
| `LiveAdvisorOverlayView.isSwipeExitRunning: Boolean` + `detach()` must not cancel a running swipe-exit animation | WS-B | WS-A |
| `StableLiveOfferAdvisor.isUserHidden(): Boolean`; `isTrackingOffer()` stays `true` while user-hidden | WS-A | WS-D, WS-E |
| `AutomaticBoltRouteOutcome` new nullable fields: `etaEstimateMeters: Int?`, `recoveryConfidence: Double?`, `diagnostics: BoltRecoveryDiagnostics?` | WS-C | WS-E |
| `BoltRecoveryDiagnostics` data class (scale m/px, measured rotation °, anchor baseline px/m, marker counts, projected pickup/drop-off `RoutePoint`s, weak-anchor reason) | WS-C | WS-E, WS-F |
| `BoltScreenshotMarkerExtractor.extract(bitmap, mapBottomPx: Int? = null, excludeRects: List<Rect> = emptyList())` | WS-C | WS-D, WS-F |
| `BoltEtaExtractor.extract(rawText): BoltEtas(toPickupMin, toCustomerMin, totalMin)` and `BoltEtaDistanceModel` (`estimateMeters(minutes)`, `observe(minutes, meters)`) | WS-C | WS-E, WS-F |
| `LiveAdvisorHub.overlayScreenRect(): Rect?` | WS-D | WS-D (capture), WS-F |

If a consumer starts before the producer merged (should not happen with the wave order below),
it must stub nothing — wait/rebase instead.

---

## 4. Waves and parallelism

| Wave | Workstreams (run in parallel) | Starts when |
|---|---|---|
| 1 | **WS-A** offer session & verdict stability · **WS-B** overlay layout, placement & gesture · **WS-C** Bolt map recovery math & markers | now |
| 2 | **WS-D** capture without hiding + main-thread relief · **WS-E** terminal states, debug line, timings, ground truth | all of wave 1 merged into `main` |
| R1 | **Release 0.16.0** | wave 2 merged |
| 3 | **WS-F** map-label registration + line-graph pairing (research-heavy, gated) | 0.16.0 released (ideally after a few days of WS-E ground-truth data) |
| R2 | **Release 0.17.0** | WS-F merged |

File ownership guarantees no overlap inside a wave:

| File | Wave 1 owner | Wave 2 owner |
|---|---|---|
| `StableLiveOfferAdvisor.kt` | A | E (D: `setCaptureSuppressed` passthrough only) |
| `LiveAdvisorHub.kt` | A | E (Bolt route callback) · D (`setCaptureSuppressed`, `overlayScreenRect`) |
| `LiveOfferUserDismissal.kt` | A | — |
| `LiveAdvisorDecisionThresholds.kt` | A | — |
| `OfferAccessibilityService.kt` | A (only `armFromVisibleOffer` / dismissal gating) | D (screenshot callbacks) |
| `LiveAdvisorOverlayView.kt` | B | D (`setCaptureSuppressed` only) |
| `OverlayGestureAxisPolicy.kt`, `LiveAdvisorSettings.kt` (overlay keys) | B | — |
| `AutomaticBoltRouteCoordinator.kt`, `BoltScreenshotMarkerExtractor.kt`, `BoltMapRecoveryModel.kt` | C | E (timings, parallel geocode, diagnostics plumbing) |
| `OfferScreenshotCapture.kt` | — | D |
| `PhotonAddressGeocoder.kt`, `RouteResearchDatabase.kt`, `DeliveryLifecycleTracking.kt`, `DeveloperToolsActivity.kt` | — | E |

Wave-2 streams touch different functions of `LiveAdvisorHub.kt`/`StableLiveOfferAdvisor.kt`;
merge `origin/main` before pushing and resolve hunks carefully.

---

## 5. Workstreams

### WS-A — Offer session & verdict stability (wave 1)

Goal: one offer = one stable card session. Swipe hides visually only. The verdict (€/km + emoji)
for an offer never changes once shown.

Owned files: `StableLiveOfferAdvisor.kt`, `LiveAdvisorHub.kt`, `LiveOfferUserDismissal.kt`,
`LiveAdvisorDecisionThresholds.kt`, new `LiveOfferVerdictCache.kt`; in
`OfferAccessibilityService.kt` only `armFromVisibleOffer` and dismissal-related gating.

Tasks:

**A1. Visual-only user dismissal.**
* In `StableLiveOfferAdvisor`, add `userHidden` state. `dismissCurrentOfferByUser(reason)`:
  detach the view (respect `overlayView.isSwipeExitRunning` — do not cancel a running swipe
  animation), set `userHidden = true`, log `overlay_user_hidden`. Do **not** call
  `suppressCurrentOffer`, do not bump `generation`, do not clear `currentParsed`, caches, route
  state or the watchdog.
* While `userHidden`: `ensureView()`/`restoreFromCache`/`showPending`/`showBase`/`update*` keep
  updating cached state but never attach the view for the same session. `isTrackingOffer()` stays
  true (so discovery OCR and screen re-arm stay suppressed via
  `LiveAdvisorHub.isCurrentTrackedOfferScreen`).
* The session ends only through real end evidence that already exists in `checkOfferStillVisible`
  (accepted task surface, idle home, presence, confirmed different offer, notification ended +
  window gone, navigation screen). Ending clears `userHidden`.
* A genuinely different offer (new notification for a different offer, or confirmed screen
  replacement) starts a new session and shows normally.
* `LiveAdvisorHub.onUserDismissedOffer` must no longer null `currentOffer` / `pendingPreview`; it
  only records the tombstone (A2) as a fallback for after the session ends.
* Expose `fun isUserHidden(): Boolean`.

**A2. Stronger dismissal identity (fallback tombstone).**
* Extend `LiveOfferDismissalIdentity` with `pickupKey` (via `DeliveryAddressNormalizer.identity`
  of the first pickup address, fallback normalized merchant name) and `estimatedMinutesMin`.
* Bolt rule: same offer if same package, price equal when both known, and (pickup key equal OR
  merchant equal); delivery count/minutes must not contradict when both known.
* Wolt rule: unchanged.
* Tests: Bolt price-only + same pickup ⇒ same; different pickup ⇒ different; Wolt regression
  cases unchanged.

**A3. Immutable verdict cache.**
* New `LiveOfferVerdictCache` (process memory, LRU 32 entries, TTL 30 min). Key: offer id when
  known, else identity key (package + price + pickup key + route fingerprint/drop-off keys).
  Value: `rateLine`, `band`, `routeLine`, walking/cycling metres, threshold source, created time.
* `renderProfitability` stores the first locked verdict. `showBase`, history restore
  (`LiveAdvisorHub.restoreHistoricalOffer`) and duplicate restore look up the cache first; on hit
  apply the locked presentation and **do not** start a new route for that offer.
* Only a different offer (different key) can produce a different verdict.

**A4. Thresholds loaded once, not per offer.**
* `LiveAdvisorDecisionThresholds`: keep a process-level warmed snapshot per
  `(platform, currency)`. Warm it at advisor creation and refresh it in the background **after**
  an offer session ends (never during one). `snapshotFor` uses the latest warmed snapshot and
  freezes it for the session; cold-start only when nothing was ever warmed.
* Because refresh happens after the session, the current offer can never be judged by thresholds
  that include itself.
* Keep logging `score_model` with `source=`.

Acceptance:
* Unit tests: swipe ⇒ later same-offer `showPending/showBase`/history restore do not attach;
  different offer after swipe ⇒ attaches; verdict cache hit returns identical line/band; thresholds
  source identical for two generations of the same offer; Bolt dismissal identity cases.
* Existing tests green (`LiveOfferUserDismissalPolicyTest`, `LiveAdvisorStabilityPolicyTest`,
  `LiveOfferResumePolicyTest`, `OfferHistoryResumePolicyTest`, `BoltLiveAdvisorV012Test`, …).

### WS-B — Overlay layout, placement & gesture (wave 1)

Goal: smaller card that never covers the courier app's top controls on any phone, and a smooth,
reliable swipe.

Owned files: `LiveAdvisorOverlayView.kt`, `OverlayGestureAxisPolicy.kt`, overlay keys in
`LiveAdvisorSettings.kt`, new `OverlayGeometryPolicy.kt`, `OverlayObstacleFinder.kt`,
`OverlaySwipePolicy.kt`.

Tasks:

**B1. Compact layout.**
* Remove the header row. Keep a small `×` (≥ 32 dp touch target, visually ~14 sp) at the top-right
  corner of the card. Version label: only when `DeveloperModeSettings.enabled`, 8 sp, inline.
* Padding ~8/4 dp. `RATE_MIN_WIDTH_DP` 176 → ~112. Rate text: uniform autosize 16–24 sp, single
  line. Route text 11 sp, max 2 lines.
* Add the debug text area (contract `applyDebugLines`): 8.5 sp, monospace, max 3 lines, ellipsize
  end, `GONE` when empty.
* Target: card height reduced ≥ 25% vs 0.15.94 for the same content.

**B2. Width.** `OverlayGeometryPolicy.widthPx(screenWidthPx, density)`:
`base = screen − 24dp; width = clamp(base × 0.85, 280dp, 400dp)`, never > base. Centered.
Recompute on configuration/display-size change (`ensure()` re-reads metrics).

**B3. Smart vertical placement.**
* `OverlayObstacleFinder(service)` inspects the visible courier window (`service.windows` /
  `rootInActiveWindow`, Bolt or Wolt package) and returns the bottom edge of visible clickable
  nodes in the top 30% of the screen (e.g. `Decline`, `Atmesti`, menu/hamburger, any clickable
  node there). Bounded walk (depth ≤ 25, nodes ≤ 400), main thread budget < 4 ms; cache per
  offer surface.
* `OverlayGeometryPolicy.defaultYPx(obstacleBottomPx?, topInsetPx, screenHeightPx, density)`:
  if obstacles: `obstacleBottom + 8dp`; else `max(topInset + 8dp, 48dp + 5% screen height)`.
  Clamp to `[topInset + 4dp, 35% of screen height]`.
* Top inset: status bar + display cutout from `WindowManager.currentWindowMetrics.windowInsets`
  (API 30+), fallback `status_bar_height` resource.
* Saved position migration: new key `overlay_y_px_v2`; the old key is ignored (one-time reset so
  the new default applies). Manual vertical drag still saves v2 and then wins over the default.
* Placement is computed when a new card session is attached; it must not jump while visible.

**B4. Swipe gesture.**
* `VelocityTracker` during horizontal mode. `OverlaySwipePolicy.shouldDismiss(dxPx, vxPxPerSec,
  widthPx, density)`: dismiss if `|dx| ≥ max(56dp, 0.30 × width)` OR (`|vx| ≥ 900 dp/s` and
  `|dx| ≥ 16dp` and same sign).
* Dismiss: animate `translationX` to `±(width + 24dp)` and `alpha → 0` over ~160 ms
  (DecelerateInterpolator), call `onDismiss` at animation start, remove the window at the end.
  `isSwipeExitRunning` true meanwhile; `detach()` during it must let the animation finish.
* Snap-back: 180 ms, DecelerateInterpolator, from current position.
* Keep `OverlayGestureAxisPolicy` (vertical drag has priority); unit-test the swipe policy.

**B5. No interference during gestures.** `setCaptureSuppressed` must be ignored while
`gestureTouchActive` or `isSwipeExitRunning` (WS-D will later remove hiding entirely).

**B6. Contracts.** Implement `screenRect()`, `applyDebugLines()`, `isSwipeExitRunning` (section 3).

Acceptance: unit tests for geometry/placement/swipe policies (360 dp, 393 dp, 412 dp widths;
16:9, 20:9, 21:9 heights; with/without obstacles; cutout insets). Robolectric smoke test that
`ensure()` builds the view without the header row. Manual checklist in commit message.

### WS-C — Bolt map recovery math & markers (wave 1)

Goal: stop wild projections; add an independent ETA distance prior; make marker detection robust
across phones. Pure, testable code.

Owned files: `AutomaticBoltRouteCoordinator.kt` (incl. `BoltMultiStopMapRecovery`),
`BoltScreenshotMarkerExtractor.kt`, `BoltMapRecoveryModel.kt`, new `BoltEtaExtractor.kt`,
`BoltEtaDistanceModel.kt`, `BoltRecoveryDiagnostics.kt`.

Tasks:

**C1. North-up transform.** Constant `BOLT_MAP_NORTH_UP = true`. With north-up, solve only the
scale (1 DOF) from the anchor vector by least squares
(`s = (Δscreen · Δgeo_m) / |Δscreen|²`, y axis flipped). Still compute the measured rotation for
diagnostics; reject a candidate pairing if measured rotation > 20° (wrong pairing or noise).

**C2. Baseline guard.** A two-anchor scale is *strong* only if pixel baseline ≥ `max(120px,
0.11 × bitmap width)` AND geo baseline ≥ 250 m. Otherwise it is *weak*: do not project customers
from it alone; use C3 prior, or fail closed with reason `anchor_baseline_too_short`.

**C3. ETA prior.**
* `BoltEtaExtractor.extract(rawText)`: `~N min` line after a pickup address row → to-pickup;
  `~N min` on the customer row → to-customer; `N min, X €` button → total. Fixtures from the real
  layouts (see `BoltOfferRegressionTest`, `BoltMultiStopV0148Test`; screenshots text:
  `Casa Della Pasta (Vokiečių str.) / Vokiečių gatvė 13 01130 Vilnius Lithuania / ~6 min /
  ~10 min / 15 min, 2,41 €`, `Sushi Square (Vilniaus str.) / Vilniaus g. 47, Vilnius, 01119 … /
  ~7 min / ~14 min / 20 min, 3,98 €`, `iLunch (A. Goštauto str.) / A. Goštauto g. 40A, Vilnius /
  ~7 min / ~10 min / 17 min, 3,74 €`).
* `BoltEtaDistanceModel` (SharedPreferences-backed): `estimateMeters(minutes)` with cold-start
  speed 230 m/min (cycling, includes stops) and `observe(minutes, routeMeters)` EWMA (α = 0.15,
  ignore outliers outside 80–450 m/min). WS-E feeds it from ground truth.
* Use 1 (weak anchors): pick the scale that makes `courier→pickups→customers` straight-line chain
  × 1.3 detour ≈ ETA estimate for to-customer leg; mark confidence ≤ 0.45.
* Use 2 (sanity): if a strong-anchor recovery's chain × 1.3 deviates > 45% from the ETA estimate,
  lower confidence to ≤ 0.4 and set `diagnostics.etaConflict = true`.
* Fill `AutomaticBoltRouteOutcome.etaEstimateMeters` always when ETAs were parsed.

**C4. Marker extraction robustness** (`BoltScreenshotMarkerExtractor`):
* `mapBottomPx` parameter: the coordinator computes it from Accessibility (top of the bottom sheet
  = min top of nodes containing the price/accept text or pickup rows); fallback 0.72 × height.
* `excludeRects` parameter: pixels inside are ignored (WS-D passes the overlay rect).
* Pin-vs-POI shape check: a pin has a body ≥ 0.6× the strongest pin of its colour, or a stem
  (narrow vertical run under the body ≥ 0.25 × body diameter). Small green park circles without
  stem are rejected.
* Cyan current-location dot overlapping a blue pin: compute blue tips ignoring cyan pixels; do not
  merge the two clusters.
* Synthetic bitmap tests for each case (extend `BoltMultiStopV0148Test` style).

**C5. Diagnostics.** Populate `BoltRecoveryDiagnostics` (section 3) for every run, success or
failure.

Acceptance: tests — short baseline ⇒ no customer projected from anchors alone; north-up projection
correct within 1 m on synthetic data; rotation-reject; ETA parsing for the three real layouts;
POI rejection; overlap case; existing Bolt tests green.

### WS-D — Capture without visible hiding + main-thread relief (wave 2)

Goal: the card never blinks because of screenshots, and screenshots never stutter a gesture.

Owned files: `OfferScreenshotCapture.kt`, screenshot callback sections of
`OfferAccessibilityService.kt`, `LiveAdvisorHub.setCaptureSuppressed` + new `overlayScreenRect()`,
`LiveAdvisorOverlayView.setCaptureSuppressed`, passthrough in `StableLiveOfferAdvisor`.

Tasks:
* **D1. Mask instead of hide.** Stop toggling overlay alpha for captures. Before any OCR / marker
  extraction / proof persistence of a *display* screenshot, paint the overlay rect
  (`overlayScreenRect()` inflated by 12 dp; screen → bitmap coordinates) with the median colour of
  its 4 px border (neutral for OCR and markers). Window screenshots (API 34+) need no mask.
  The price OCR must never read the card's own `€…/km` text — add a test with a bitmap containing
  the card text inside the rect.
* **D2. Rare clean capture.** If WS-C's recovery reports fewer customer pins than expected AND the
  overlay rect intersects the map area, allow exactly one extra capture with the overlay hidden for
  a single frame (≤ 1 per offer session). Log `overlay_clean_capture`.
* **D3. Off main thread.** Move `toBitmap` + masking to a dedicated single-thread executor; hop
  back to main only for state changes; keep `isCaptureCurrent(token)` checks after every hop;
  always close `HardwareBuffer`.
* **D4.** Verify with WS-A that a user-hidden session keeps passive Bolt discovery OCR off; add a
  test for `isCurrentTrackedOfferScreen` while user-hidden.

Acceptance: tests for rect mapping/masking and the clean-capture budget; overlay alpha never
changes due to capture (unit test on the view policy); existing capture tests green
(`OfferCaptureRuntimeTest`, `OfferDiscoveryOcrPolicyTest`, `CaptureFlightGuardTest`, …).

### WS-E — Terminal states, debug line, timings, ground truth (wave 2)

Goal: no infinite spinner; the courier can see what CourierPilot recognised; we can measure Bolt
recovery accuracy.

Owned files: `updateBoltRoute` + new timeout logic in `StableLiveOfferAdvisor.kt`, Bolt route
callback in `LiveAdvisorHub.kt`, timing/geocoding/diagnostics plumbing in
`AutomaticBoltRouteCoordinator.kt`, `PhotonAddressGeocoder.kt`, `RouteResearchDatabase.kt`,
`DeliveryLifecycleTracking.kt` (hook only), `DeveloperToolsActivity.kt`, new
`BoltRecoveryTruth.kt`, `LiveAdvisorDebugLines.kt`.

Tasks:
* **E1. Terminal states.**
  * Bolt `PICKUP_ONLY` or failed route: if `etaEstimateMeters` → show provisional
    `≈ €X/km ⏳` (`LiveAdvisorPresentation.provisionalRateLine`) and route line
    `🕒 ~N min ≈ X.X km`; else `?/km`. These are not locked verdicts (A3 cache stores only real
    route verdicts).
  * Global watchdog: 20 s after a session starts without any verdict → `?/km` + log
    `verdict_timeout` with the pending stage. Never an endless spinner.
* **E2. Debug line** (only when `DeveloperModeSettings.enabled`; via `applyDebugLines`):
  * Bolt: `🍴 <short pickup address> · geo 0.85` (one per pickup);
    `👤 ≈ <reverse-geocoded street + number> · map 0.62 · ±80 m` (one per customer);
    `s 2.4 m/px · base 180px/420m · eta 10′≈2.3 km`.
  * Wolt: pickup / drop-off text addresses as parsed.
  * Short form: drop postcode, city, country. Max 3 lines (join multiple stops with ` | `).
  * `PhotonAddressGeocoder.reverse(lat, lon)` (Photon `/reverse`, same endpoint config as forward
    geocoding), LRU cache 64, 3 s timeout, background thread; fallback shows `54.6872,25.2797`.
  * Never log these strings.
* **E3. Timings.** Log `bolt_route_timing` / `wolt_route_timing`:
  `gps_ms, geocode_ms(per pickup), markers_ms, valhalla_ms, total_ms` (no addresses).
* **E4. Parallel pickup geocoding** in `resolvePickups` (keep result order; per-address timeout
  stays 7 s; overall cap 8 s).
* **E5. Ground truth.** When a Bolt offer is accepted and the real customer address is extracted,
  geocode it and compare with the recovered customer points of that offer's latest live-advisor
  run. Store locally (new table `bolt_recovery_truth`: offer_id, recovered lat/lon, truth lat/lon,
  error_m, scale, baseline px/m, marker counts, ETA minutes, route metres, created_at; local only,
  respects existing retention settings). Feed `BoltEtaDistanceModel.observe` with
  (to-customer minutes, true route metres when a route is computed later, else skip).
  Developer tools: show count, median and p80 error, last 20 rows; local export (share sheet) of
  rows + the matching saved Bolt research samples for WS-F fixtures.

Acceptance: tests for terminal-state presentation, timeout, debug-line formatting/shortening,
reverse-geocode parsing (fixture JSON), truth error computation, parallel geocode ordering.

### WS-F — Map-label registration & line-graph pairing (wave 3, gated)

Goal: well-conditioned screen→geo transform from many map labels; correct stop pairing for
multi-orders.

Owned files: new `BoltMapLabelRegistration.kt`, `BoltRouteLineGraph.kt`, integration in
`BoltMultiStopMapRecovery`, geocoder bbox support.

Tasks:
* **F1. Label OCR**: run the existing ML Kit recognizer on the map crop (above `mapBottomPx`,
  overlay masked). Drop Bolt UI strings (`Show map`, `Decline`, `mapbox`, times, prices); keep
  labels ≥ 4 letters.
* **F2. Label geocoding** restricted to a bbox around GPS (± 5 km); prefer POI/place results;
  offline cache keyed by normalized label. Districts get low weight (large areas).
* **F3. Robust fit**: north-up similarity (scale + tx + ty) by RANSAC over label anchors + GPS dot
  + geocoded pickups; inlier threshold 60 m; ≥ 3 inliers required; confidence from inlier count
  and residual RMS. Prefer it over C1/C3 when confident.
* **F4. Line graph**: sample pixels along segments between markers (blue: courier→pickup, green:
  pickup→customer→customer); ≥ 60% coloured samples ⇒ connected. Order stops by the graph;
  fallback nearest-neighbour. Cover 1×1, 1×2, 2×1, 2×2, 2×3 synthetic cases.
* **F5. Gate**: setting `bolt_label_registration` (default off) until WS-E ground truth shows
  median error < 120 m and better than the C-pipeline on the same samples; then default on.

Acceptance: synthetic + exported real fixtures; report median/p80 error before/after.

### R — Releases

* **R1 (0.16.0)** after waves 1–2: `git fetch origin main`, confirm all streams merged, run
  `gradle testDebugUnitTest assembleDebug`, bump `versionCode`/`versionName` in
  `app/build.gradle.kts` (135 → 136, `0.16.0`), write `docs/RELEASE_0.16.0.md` (user-facing
  summary + manual test checklist below), commit `Bump CourierPilot to 0.16.0`, push to `main`,
  tag `v0.16.0` and push the tag (triggers `.github/workflows/build.yml`). Verify the workflow
  succeeded.
* **R2 (0.17.0)** same after WS-F.

Manual test checklist (real device, Bolt + Wolt):
1. Bolt offer: card appears below `Decline`/menu, does not cover them.
2. Swipe left/right short-fast and long-slow: card slides out smoothly, never re-appears for the
   same offer, emoji never changes; next different offer shows normally.
3. No blinking while the offer is visible (watch 30 s).
4. Bolt double (1 restaurant × 2 customers): verdict or `≈ … ⏳` / `?/km` within 20 s, never an
   endless spinner.
5. Developer mode: debug line shows pickup + recovered customer addresses; after accepting, the
   ground-truth row appears in Developer tools.
6. Wolt single + batch: unchanged behaviour, card placement OK.
7. Vertical drag still works and is remembered.
