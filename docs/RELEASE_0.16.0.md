# CourierPilot 0.16.0 — Live Advisor overhaul (R1)

Android version: **0.16.0** · versionCode **136**

## What's new

- **Stable offers and decisions.** Dismissing the floating card by swiping hides the card for the current offer; it does not reset its session, reroute it or change an already established €/km verdict and emoji. A genuinely different offer can appear as usual.
- **More compact floating card.** Removed the large header, reduced the width and adjusted the default placement to avoid Bolt/Wolt top controls when those controls are exposed through Accessibility. Horizontal flicks dismiss smoothly; vertical dragging still saves the preferred position.
- **Less capture flicker and gesture interruption.** Display screenshots mask the floating card in the screenshot instead of making it blink on screen. Bitmap conversion and related capture work were moved off the main/UI thread.
- **Safer Bolt map recovery.** North-up projection, weak-anchor guards, an ETA distance prior, and more robust marker filtering reduce the risk of treating map noise as a customer destination. Incomplete or failed routes show a provisional ETA-based rate or `?/km` rather than an endless spinner; no provisional rate is treated as a final scored verdict.
- **Better developer diagnostics.** Developer mode can show short pickup and recovered destination labels plus geometry/ETA confidence on the card. Bolt recovery error statistics (count, median, p80) and recent records are available locally, with an explicit manual export for analysis.

## Important privacy detail

The on-screen debug address lines are not written to CaptureEventLog or remote telemetry. Research coordinates remain in the local database. A **manually** exported Bolt research bundle can contain sensitive coordinates, screenshots or addresses: inspect its destination before sharing.

## Manual acceptance checklist — real device, Bolt and Wolt

The following checks are **not yet marked as passed**; an Android unit-test/build success is not a substitute for observing real courier app screens.

- [ ] **Bolt position:** open an offer; the floating card appears below the `Decline` / menu controls without covering them.
- [ ] **Swipes and verdict stability:** swipe left and right, both short-fast and long-slow. The card slides out smoothly, does not return for the **same** offer, and its verdict emoji never changes after a swipe. A genuinely different new offer appears normally.
- [ ] **No flicker:** keep an offer visible for at least 30 seconds; the card does not blink during screenshots/capture.
- [ ] **Bolt double delivery:** test one restaurant with two customers. See a full verdict, provisional `≈ … ⏳`, or `?/km` within **20 seconds**; never an endless loading indicator.
- [ ] **Developer debug and ground truth:** enable Developer mode. Check that the card lists the pickup and recovered customer addresses; accept the Bolt offer and confirm a corresponding ground-truth record appears in Developer tools.
- [ ] **Wolt regressions:** test a single offer and a batch/add-on. Existing behaviour remains intact and card placement is usable.
- [ ] **Vertical drag:** reposition the card by dragging vertically; confirm the position is remembered next time.

## Known boundaries

- Bolt customer positions derived from map screenshots are estimates; weak anchors or missing markers can still prevent a verified full-route verdict.
- Reverse-geocoded customer labels require a network lookup and may fall back to coordinates in Developer mode.
- Multi-drop accuracy is being measured with locally stored ground truth; optional map-label registration/route-line pairing is planned for the later WS-F workstream, **not** part of 0.16.0.
- Detailed per-stage Wolt timings are not available yet (overall elapsed time is recorded).

## Automated verification

Release gate: `gradle testDebugUnitTest assembleDebug` on the merged WS-A–WS-E code, followed by the signed release APK workflow. The physical-device checklist above must be completed separately.
