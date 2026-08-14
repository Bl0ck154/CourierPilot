# CourierPilot Route Intelligence Roadmap

Status: active implementation roadmap; 0.10 reaches experimental Wolt routing + live advisor, while preferred scooter routing, Bolt recovery and personal learning remain evidence-gated.

## Product goal

At offer time CourierPilot should compress the courier's mental arithmetic without making the acceptance decision.

Desired output:

- platform offer price;
- platform-provided distance/time when available;
- independently calculated route distance/time with provenance;
- €/km;
- effective €/h only from inputs that are explicitly shown;
- later: personalized route time + venue wait once real history is sufficient;
- optional voice summary;
- no automatic accept/reject.

## Routing philosophy

The target is a Ninebot-class urban scooter workflow in Vilnius, not a conventional road-cyclist model.

Preference goals:

1. short practical route;
2. strongly avoid stairs/steps;
3. use cycleways when useful without accepting a silly detour;
4. permit practical path/pedestrian topology where routing data allows it;
5. retain OSM access restrictions;
6. learn from real ridden routes rather than hiding bad stock-router behavior behind arbitrary Android heuristics.

Stock Valhalla still has no one costing mode that exactly means this. Therefore CourierPilot continues to compare:

- **pedestrian shortcut** — pedestrian topology with high stair penalty;
- **cycleway biased** — hybrid bicycle with stronger path/cycleway preference.

Neither profile is the permanent CourierPilot winner yet.

## Implemented through 0.10

### Self-hosted Valhalla

A protected HTTPS Valhalla deployment for Lithuania is already live and used by the Android client. The token remains outside GitHub and privacy-safe diagnostics.

### Current location + geocoding

CourierPilot can obtain a one-shot foreground phone location and resolve textual addresses through the Android geocoder. There is no background-location permission in 0.10.

### Manual validation harness

The Route Research screen supports:

- current phone fix;
- editable test destination;
- address → coordinates;
- both Valhalla candidates;
- geometry preview;
- GeoJSON export;
- local route verdicts/notes.

### Wolt ordered route acquisition

`OfferParser` now preserves the sequential Wolt Timeline in addition to the existing pickup/drop-off lists.

This prevents a stacked route such as:

`pickup A → drop-off 1 → pickup B → drop-off 2`

from being silently reordered into:

`pickup A → pickup B → drop-off 1 → drop-off 2`.

### Experimental automatic Wolt routing

0.10 can run an **explicitly enabled, post-capture** route experiment:

`fresh GPS → every ordered Wolt textual stop → geocode → Valhalla pedestrian + cycleway candidates`.

Important constraints:

- off by default;
- starts only after clean screenshot + offer DB insertion;
- every required stop must resolve or the run fails closed;
- no candidate is automatically selected;
- calculated numbers never overwrite platform numbers;
- waypoint provenance/confidence and both candidate summaries are stored locally.

### Live advisor

The post-capture overlay now shows immediately usable arithmetic:

- price;
- platform km / ETA;
- platform-derived €/km and €/h range;
- optional Wolt calculated candidates when enabled;
- optional voice summary.

It deliberately exposes raw numbers instead of GOOD/BAD scoring.

### Outcome groundwork

The separate research timeline records a certain `OFFER_CAPTURED` event and then only explicit, monotonic screen-state cues.

This is the beginning of the `offer → outcome` loop, not a completed delivery detector.

### Bolt sample collection

One-shot Bolt research bundles already contain Accessibility tree + matching screenshot + available cached phone GPS metadata. This is the evidence source for the next Bolt phase.

## Next phase 1 — validate Wolt live routing

Collect real Wolt examples, especially stacked jobs, and compare:

- captured Timeline order;
- Android-geocoded stop coordinates;
- platform distance/ETA;
- pedestrian Valhalla candidate;
- cycleway Valhalla candidate;
- actual route the courier would ride.

Target at least 10–20 known Vilnius routes with awkward cases: Old Town, river crossings, stairs, courtyards, pedestrian shortcuts and cycleway detours.

Do not select a preferred profile until this corpus is reviewed.

## Next phase 2 — Bolt marker coordinate recovery

Bolt is still blocked by evidence, not by Android GPS.

Use real private samples and investigate in this order:

1. semantic marker labels/content descriptions;
2. stable marker/map view IDs and bounds;
3. current-location marker and route-line geometry;
4. viewport/zoom/orientation clues;
5. independently known anchors to recover map scale/orientation;
6. recovered marker coordinates checked against ground truth.

If the evidence is insufficient, keep the coordinate unknown. Never fabricate it.

See `BOLT_MAP_COORDINATE_RECOVERY.md`.

## Next phase 3 — reliable delivery lifecycle

Expand explicit Wolt/Bolt state cues from real screens while preserving the monotonic state machine.

Goal:

`OFFER_CAPTURED → ACCEPTED → ARRIVED_PICKUP → PICKED_UP → ARRIVED_DROPOFF → DELIVERED`

Only observed states may become statistics. A screen disappearing is not acceptance; a later unrelated completion message must not attach to the wrong offer.

Once enough real lifecycle evidence exists, derive:

- accepted → completed duration;
- pickup arrival → picked-up wait;
- venue-specific wait distributions;
- delivery handoff overhead.

## Next phase 4 — opt-in real GPS traces

Only after lifecycle boundaries are reliable, add explicit active-work/active-delivery GPS collection with retention controls.

Requirements:

- foreground/user-controlled tracking lifecycle;
- battery impact measured on a real courier shift;
- local-first trace storage;
- no indefinite tracking because Accessibility happens to be enabled;
- map matching through the protected Valhalla `/trace_attributes` path when deployed.

See `PERSONAL_ROUTE_LEARNING.md`.

## Next phase 5 — personalized ETA and effective €/h

Use simple statistics before any ML:

- median/trimmed segment traversal times;
- minimum real sample thresholds;
- recency weighting if useful;
- venue median pickup wait after repeated observations;
- fallback to baseline routing wherever personal support is weak.

Target transparent calculation:

`effective €/h = offer price / (ride to pickup + expected venue wait + remaining route + handoff overhead) × 60`.

Every component must expose provenance/sample support. Do not display a personalized estimate if the supporting history is too thin.

## Potential later routing profile work

If the real corpus shows that neither stock candidate is consistently acceptable, investigate a custom Valhalla costing/fork on the server.

Prefer improving the routing engine over growing a pile of opaque client-side exceptions.

## Privacy boundaries

- GPS/address history is sensitive and local-first.
- Automatic Wolt routing is independently switchable and off by default.
- When enabled, ordered offer coordinates are sent to the configured self-hosted Valhalla endpoint; document this clearly.
- Raw customer addresses/GPS never belong in privacy-safe diagnostics.
- Bolt samples stay private until deliberately shared.
- Continuous GPS must require explicit user-controlled activation.

## Implementation progress

- [x] Deploy protected self-hosted Valhalla for Lithuania.
- [x] Provider-neutral route client and bounded HTTPS transport.
- [x] One-shot Android current-location acquisition.
- [x] Manual route comparison/debug harness.
- [x] One-shot full Bolt sample capture.
- [ ] Recover Bolt marker coordinates from real evidence.
- [x] Preserve ordered Wolt textual route stops.
- [x] Experimental opt-in Wolt post-capture routing.
- [x] Transparent live advisor with platform arithmetic.
- [~] Offer→outcome lifecycle: conservative explicit-state groundwork implemented; real cue coverage incomplete.
- [ ] Opt-in continuous/active-delivery GPS trace collection.
- [ ] Map matching and personal segment statistics fed into advisor.
- [ ] Real venue-wait adjustment fed into effective €/h.
- [ ] Preferred/custom scooter routing profile selected from real validation.

## Required evidence before production route activation

`RouteIntelligencePolicy.PRODUCTION_ENABLED` must remain false until the route is trusted enough to influence normal product behavior.

Required evidence includes:

- real Vilnius routes rather than only synthetic tests;
- no stair-heavy route silently promoted over an obvious practical alternative;
- stacked Wolt stop order verified on real offers;
- geocoded Wolt stops spot-checked;
- no fabricated Bolt destination;
- route provenance visible and retained;
- routing failure demonstrably independent from core offer persistence;
- opt-out works independently from capture;
- personalized estimates gated by real sample counts.

## Official references

- Valhalla docs: https://valhalla.github.io/valhalla/
- Valhalla route API: https://valhalla.github.io/valhalla/api/turn-by-turn/overview/
- Valhalla source: https://github.com/valhalla/valhalla
- Lithuania OSM extract: https://download.geofabrik.de/europe/lithuania.html
