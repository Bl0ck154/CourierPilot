# Route Intelligence Implementation Status

Status: real-device Valhalla/Bolt research workflow implemented; automatic offer-time routing still disabled

This file distinguishes the testable 0.9 research tools from later production routing and personalized learning.

## Implemented now

### Provider-neutral route domain

`RouteIntelligence.kt` defines:

- route points;
- route requests/results;
- pedestrian-shortcut and cycleway-biased research profiles;
- a provider-neutral `RouteProvider` boundary;
- validation constraints;
- `PRODUCTION_ENABLED = false`.

Production offer capture does not depend on routing and remains functional if route research is disabled, offline or broken.

### Protected Valhalla client

`ValhallaRouteProvider`:

- accepts only an explicitly enabled HTTPS endpoint;
- sends the token via `Authorization: Bearer ...`;
- uses bounded connect/read timeouts and a 2 MiB response limit;
- returns non-2xx responses as failures;
- preserves HTTP status, Valhalla warnings, distance, generic duration and encoded route shapes;
- never writes the token or route coordinates into privacy-safe diagnostics.

The validated deployment is available at `https://valhalla.zivkr.pp.ua`. The token remains VPS/device-only and is not committed to GitHub.

The public gateway currently exposes protected `/route` and `/status`. The future `/trace_attributes` map-matching endpoint is intentionally not part of the 0.9 public surface.

### Real-phone route validation

`RouteResearchActivity` is now usable without manually discovering the phone coordinates elsewhere.

It supports:

1. one-shot foreground current-location acquisition;
2. Android address geocoding for the test destination;
3. manual coordinate editing as a fallback;
4. pedestrian-shortcut and cycleway-biased requests to the self-hosted Valhalla service;
5. distance, generic ETA and warnings;
6. Polyline6 decoding;
7. a native geometry-only comparison preview;
8. shareable GeoJSON for deliberate private debugging;
9. a local verdict plus optional note for each known Vilnius route.

The preview is intentionally not presented as a full navigation map. Its purpose is to make detours and candidate differences visible while collecting a trusted corpus.

See `ROUTE_RESEARCH_TESTING.md` for the exact phone workflow.

### Foreground location boundary

0.9 adds coarse/fine location permissions for explicit research actions only.

`RouteResearchLocation` can:

- request a fresh current location while the research activity is open;
- choose between available GPS/network fixes;
- expose accuracy, provider and fix age;
- read the best cached fix for an armed Bolt research sample.

There is no background-location permission and no continuous GPS logger wired into production in 0.9.

### Destination geocoding

`RouteResearchGeocoder` uses the Android geocoder as a best-effort research helper. The resulting coordinates remain visible/editable and must not be treated as infallible ground truth.

The route-domain `AddressGeocoder` boundary is also present for later replacement with a more controlled provider if needed.

### Local Vilnius validation corpus

The isolated `route_research.db` now stores:

- route comparisons;
- both candidate results;
- start/end coordinates;
- user verdict (`pedestrian better`, `cycleway better`, `both usable`, `both bad`);
- optional notes.

The schema also reserves isolated research tables for later GPS samples, matched edge traversals, delivery lifecycle events, venue waits and Bolt ground truth. Production offer persistence never depends on this database.

### Bolt full research sample

The separate `BoltAccessibilityDiagnosticsService` remains scoped to `com.bolt.deliverycourier` and independent from `OfferAccessibilityService`.

One explicit arm action is consumed exactly once. The resulting private sample can contain:

- `accessibility-tree.txt` — bounded hierarchy including text/content descriptions/view IDs/screen bounds;
- `screen.png` — screenshot of the same research frame when Android permits screenshot capture;
- `metadata.json` — timestamp, screen dimensions and best available cached phone GPS with accuracy/age/provider.

The tree remains capped at 1,500 nodes / roughly 180k characters. The sample stays in app-private storage until the user deliberately shares it through the restricted research `FileProvider` or clears it.

IMPORTANT: the sample can contain customer, merchant and exact-location data. It is not copied into the normal privacy-safe Capture event log and should not be posted publicly.

### Bolt map-coordinate recovery model

`BoltMapRecoveryModel.kt` provides the conservative geometry layer for later analysis:

- screen marker evidence;
- current-location anchor;
- local screen→geo projection;
- map scale/orientation derived from two independently known screen+geographic anchors;
- explicit refusal to project when scale/orientation evidence is missing.

No Bolt pickup/drop-off coordinate is fabricated automatically in 0.9.

### Future personal-routing models compiled but dormant

The selective port from the superseded route-intelligence draft includes:

- `RoutePlanning.kt` — resolved/unresolved waypoints with coordinate provenance/confidence;
- `DeliveryTimeline.kt` — observed delivery lifecycle and pickup-wait derivation;
- `OfferEconomics.kt` — transparent route-distance/time based €/km and effective €/h arithmetic;
- `PersonalRouteLearning.kt` — conservative median segment times and venue waits with minimum sample thresholds;
- `ValhallaMapMatchingContract.kt` — future `/trace_attributes` payload/parser for GPS→OSM edge matching.

These models are scaffolding. They are not connected to automatic offer decisions.

## Intentionally not implemented yet

### Production offer-time routing

`RouteIntelligencePolicy.PRODUCTION_ENABLED` remains `false`.

Do not wire the network request into the offer-capture critical path until the Vilnius validation corpus demonstrates that the route candidates are trustworthy enough. A routing timeout/failure must never delay priced-offer persistence.

### Automatic Wolt offer routing

Wolt text addresses can already be parsed in many offers, but 0.9 does not automatically execute:

`current GPS → pickup address → drop-off address → coordinates → Valhalla`

The research geocoder proves the shape of this workflow; automatic wiring should follow only after route validation and address-resolution behavior are reviewed.

### Bolt pickup/drop-off recovery

Real samples are still the dependency.

Collect several full Bolt samples and investigate in this order:

1. hidden marker semantics/content descriptions;
2. stable map/marker view IDs and bounds;
3. current-location marker and route-line geometry;
4. viewport/zoom/orientation clues;
5. recover scale/orientation from independently known anchors where possible;
6. compare recovered coordinates against eventual ground truth.

Never fabricate a Bolt coordinate to make the pipeline look complete.

### Continuous GPS / personal segment learning

0.9 does not continuously record courier movement.

Before enabling the future personalized ETA feature, implement an explicit user-controlled tracking lifecycle and verify battery/privacy behavior. Then expose the protected Valhalla `/trace_attributes` endpoint and map real GPS traces to OSM edges.

Personal segment timing should only influence estimates after the configured minimum sample count is met; the current model requires five real traversals of a segment. Venue-wait statistics similarly require repeated real observations.

### Live offer advisor

The intended later calculation is transparent rather than a magic rating:

`offer price + real route distance/time + observed venue wait + handoff overhead → €/km and effective €/h`

No auto-accept/auto-reject behavior is planned as part of this research path.

## Validation gates before active routing behavior

- Android CI/tests green;
- protected Valhalla `/route` smoke tests in Vilnius; **completed 2026-08-14**;
- current-location button works on a real phone;
- destination geocoding reviewed against known Vilnius addresses;
- 10–20 known Vilnius route comparisons saved with verdicts;
- stair-heavy/pathologically long candidates identified and rejected/tuned;
- at least several full Bolt samples captured from real offers;
- recovered Bolt coordinates, if implemented, checked against real ground truth;
- production capture remains independent;
- no raw Bolt sample is added to normal diagnostics/logs;
- continuous/background location is not introduced accidentally.

## Superseded draft

Old draft PR #13 contains the original broad route-intelligence scaffolding but has diverged substantially from current `main`. Useful model pieces have been selectively ported into the 0.9 work. The obsolete duplicate HTTP provider from that draft should not be merged.
