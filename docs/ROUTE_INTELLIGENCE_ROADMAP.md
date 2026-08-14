# CourierPilot Route Intelligence Roadmap

Status: 0.11 implements explicit real-ridden GPS traces. Wolt live routing and advisor exist; Bolt coordinates, map matching, personal ETA and venue-wait prediction remain evidence-gated.

## Product goal

At offer time CourierPilot should compress mental arithmetic without making the acceptance decision:

- platform price/distance/time when exposed;
- independently calculated route distance/time with provenance;
- €/km and transparent €/h inputs;
- later: personal route time + venue wait only when real history is sufficient;
- optional voice;
- no automatic accept/reject.

## Routing target

The target is a practical Ninebot-class Vilnius courier route rather than a conventional road-cyclist model:

1. short practical route;
2. strongly avoid stairs;
3. use cycleways when useful without absurd detours;
4. retain legitimate pedestrian/path shortcuts where routing data permits;
5. keep OSM access restrictions;
6. learn from actual ridden traces rather than inventing a speed constant.

CourierPilot currently keeps two inspectable Valhalla candidates:

- **pedestrian shortcut** — pedestrian topology with strong stair penalty;
- **cycleway biased** — hybrid bicycle with stronger path/cycleway preference.

Neither is promoted as the permanent scooter profile yet.

## Implemented through 0.11

### Protected self-hosted Valhalla

Lithuania routing is available through the protected HTTPS endpoint. The bearer token remains outside GitHub and privacy-safe diagnostics.

### Manual validation harness

Route Research supports one-shot current GPS, address lookup, both Valhalla candidates, geometry preview, GeoJSON export and local verdict/notes.

### Ordered Wolt routing

`OfferParser` preserves the sequential Wolt Timeline, including interleaved stacked routes such as:

`pickup A → drop-off 1 → pickup B → drop-off 2`.

With Wolt routing explicitly enabled, the post-capture experiment performs:

`fresh GPS → ordered textual stops → bounded geocoding → both Valhalla candidates`.

It starts only after clean screenshot + offer DB insertion, fails closed if a required stop is unresolved, never overwrites platform metrics and never selects a route winner automatically.

### Transparent live advisor

The post-capture overlay shows raw price/km/ETA arithmetic plus optional calculated candidates and voice. It does not produce an opaque GOOD/BAD score or press Accept/Decline.

### Conservative offer → outcome groundwork

A durable offer records certain `OFFER_CAPTURED`. Later states are accepted only from explicit courier-screen cues and monotonic transitions. Missing evidence remains missing.

### Bolt sample collection

One-shot private Bolt bundles contain Accessibility tree, matching screenshot where permitted, and cached phone GPS metadata. Real samples are the evidence source for coordinate recovery.

### Explicit real-ridden GPS traces — 0.11

CourierPilot now has a user-started location foreground service for real route-learning traces.

Properties:

- start only from a visible `Ride trace` screen;
- visible ongoing notification with Stop action;
- foreground location + notification permissions only; no `ACCESS_BACKGROUND_LOCATION`;
- `START_NOT_STICKY`, so a killed trace is not silently resurrected;
- requested cadence about 2 s / 2 m;
- >80 m accuracy points and extreme jumps rejected;
- service heartbeat distinguishes a bad GPS fix from a dead recorder;
- raw accepted points, accuracy and reported speed stored locally in existing `gps_sessions/gps_samples`;
- latest trace can be deliberately exported as GeoJSON.

The long-press launcher shortcut **CourierPilot → Ride trace** exposes the recorder without adding a second launcher icon.

## Next phase 1 — real phone validation

Collect Wolt offers and ridden traces, especially:

- stacked Timeline orders;
- Old Town shortcuts;
- stairs/steep paths;
- river crossings;
- courtyards/passages;
- cycleway detours;
- routes where stock pedestrian and bicycle candidates disagree strongly.

Target at least 10–20 known Vilnius route comparisons plus several actual ridden traces before selecting a preferred profile.

## Next phase 2 — Bolt marker coordinates

Use real private Bolt samples and investigate in this order:

1. semantic marker labels/content descriptions;
2. stable map/marker view IDs and bounds;
3. current-location marker and route-line geometry;
4. viewport/zoom/orientation clues;
5. independently known anchors for scale/orientation;
6. recovered coordinates validated against ground truth.

Unknown remains unknown. Never fabricate a Bolt coordinate.

## Next phase 3 — map matching

Expose the already-modeled protected Valhalla `/trace_attributes` path only after real trace export is verified.

Then:

1. submit a completed local GPS session;
2. retain matched edge/way identifiers and matching confidence;
3. reject low-confidence/malformed matches;
4. store per-edge traversal intervals locally;
5. keep the raw trace so map-matching decisions remain inspectable.

Map matching must remain research-only until real Vilnius traces show sane results.

## Next phase 4 — personal segment time

Start with simple statistics, not ML:

- minimum sample thresholds;
- median/trimmed traversal time per matched edge/segment;
- discard implausible/outlier traversals;
- optionally weight recent history;
- fallback to baseline route ETA where personal support is thin.

Never show a personalized ETA without sample support/provenance.

## Next phase 5 — venue waits and effective €/h

Expand reliable explicit lifecycle cues so real pickup wait can be measured:

`ACCEPTED → ARRIVED_PICKUP → PICKED_UP`.

After enough repeated observations per venue, estimate a conservative median pickup wait and combine it with supported personal route time:

`effective €/h = offer price / (ride to pickup + expected venue wait + remaining route + handoff overhead) × 60`.

Every component must expose its source/sample count.

## Potential custom routing profile

If the real corpus shows both stock Valhalla candidates are consistently wrong for scooter courier use, improve server-side Valhalla costing/profile behavior rather than growing opaque Android-side exceptions.

## Privacy boundaries

- route/address/GPS history is sensitive and local-first;
- automatic Wolt routing is independently switchable and off by default;
- real ride traces start only by explicit user action and remain local unless shared;
- no automatic GPS start from Accessibility/platform presence;
- raw customer addresses/GPS stay out of privacy-safe diagnostics;
- Bolt samples remain private until deliberately exported.

## Implementation progress

- [x] Protected self-hosted Valhalla for Lithuania.
- [x] Provider-neutral route client and bounded HTTPS transport.
- [x] One-shot Android current-location acquisition.
- [x] Manual route comparison/debug harness.
- [x] One-shot full Bolt sample capture.
- [ ] Recover Bolt marker coordinates from real evidence.
- [x] Preserve ordered Wolt textual route stops.
- [x] Experimental opt-in Wolt post-capture routing.
- [x] Transparent live advisor with platform arithmetic.
- [~] Offer→outcome lifecycle groundwork; real cue coverage incomplete.
- [x] Explicit opt-in foreground GPS route traces.
- [ ] Validate protected `/trace_attributes` on real recorded sessions.
- [ ] Feed matched personal segment statistics into advisor.
- [ ] Feed real venue-wait adjustment into effective €/h.
- [ ] Select/tune preferred/custom scooter profile from real validation.

## Production route gate

`RouteIntelligencePolicy.PRODUCTION_ENABLED` remains false until calculated routing is trusted enough to influence normal product behavior.

Required evidence:

- real Vilnius comparisons and ridden traces;
- stacked Wolt order verified on phone;
- geocoded stops spot-checked;
- no fabricated Bolt destination;
- route provenance visible/retained;
- route failures independent from core capture;
- opt-out works independently from capture;
- personalized estimates gated by real sample counts.

## Official references

- Valhalla docs: https://valhalla.github.io/valhalla/
- Valhalla route API: https://valhalla.github.io/valhalla/api/turn-by-turn/overview/
- Valhalla source: https://github.com/valhalla/valhalla
- Lithuania OSM extract: https://download.geofabrik.de/europe/lithuania.html
