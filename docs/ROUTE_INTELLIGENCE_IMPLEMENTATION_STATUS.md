# Route Intelligence Implementation Status

Status: 0.10 adds a fail-safe live advisor and opt-in automatic Wolt route experiment; Bolt coordinate recovery and personalized routing still require real evidence.

This file distinguishes what is compiled and wired now from what remains research.

## Implemented now

### Core capture remains the hard boundary

CourierPilot still saves the clean priced-offer screenshot and inserts the offer into `courier_offers.db` before any live-advisor, geocoder, GPS or Valhalla work starts.

Post-capture helpers are wrapped as best-effort work. A route failure cannot roll back an archived offer.

### Live offer advisor

0.10 can show a temporary post-capture Accessibility overlay with:

- platform price;
- platform-provided distance and ETA when available;
- transparent €/km;
- transparent €/h range derived from the platform ETA;
- optional voice summary;
- an explicit Wolt-route toggle;
- no Accept/Decline automation and no hidden GOOD/BAD verdict.

The overlay is hidden while a new offer screenshot is being collected so CourierPilot does not intentionally draw itself into the clean proof image.

### Ordered Wolt stop model

`OfferParser` now preserves the sequential route Timeline in `orderedRouteStops` instead of relying only on separate pickup/drop-off arrays.

This matters for stacked jobs such as:

`current → pickup A → drop-off 1 → pickup B → drop-off 2`

The automatic route coordinator uses this captured order when available and only falls back to pickup-list-then-drop-off-list when no ordered Timeline was parsed.

### Opt-in automatic Wolt route experiment

Automatic Wolt routing is **off by default** and is separate from merely configuring the manual Valhalla endpoint.

When explicitly enabled after an offer was already persisted, `AutomaticWoltRouteCoordinator`:

1. obtains one fresh foreground device-location fix;
2. reparses the captured Wolt offer text;
3. keeps the captured Timeline order;
4. geocodes each required textual stop;
5. fails closed if any required stop cannot be resolved;
6. requests both `PEDESTRIAN_SHORTCUT` and `CYCLEWAY_BIASED` from the protected self-hosted Valhalla endpoint;
7. returns both candidates without selecting a winner.

Platform-provided and calculated route metrics remain separate.

### Route provenance storage

`route_research.db` is now schema version 2. It retains the existing manual validation corpus and adds isolated live-advisor research tables for:

- advisor runs linked to the local offer ID;
- platform price/distance/ETA snapshot;
- current-location accuracy;
- resolved ordered waypoints;
- waypoint kind, coordinate provenance and confidence;
- both Valhalla candidate summaries;
- route failure reason when the run fails closed.

This data is local and intentionally separate from privacy-safe Reliability diagnostics.

### Conservative offer → outcome groundwork

A durably persisted offer records `OFFER_CAPTURED` with confidence 1.0.

Later lifecycle events are recorded only when explicit courier-screen text matches a recognized cue **and** the transition is monotonic:

`OFFER_CAPTURED → ACCEPTED → ARRIVED_PICKUP → PICKED_UP → ARRIVED_DROPOFF → DELIVERED`

`CANCELLED` can terminate permitted in-progress states. A disappearing offer or generic screen change is not evidence of acceptance/completion.

This is intentionally sparse. Missing explicit UI cues produce missing lifecycle data rather than an invented outcome.

### Manual Valhalla research remains available

The 0.9 research harness is unchanged in purpose:

- one-shot foreground current location;
- destination address geocoding;
- pedestrian vs cycleway comparison;
- Polyline6 geometry preview;
- GeoJSON sharing;
- local route verdict and notes.

The protected endpoint is `https://valhalla.zivkr.pp.ua`; the bearer token remains device/VPS-only.

### Bolt full research sample remains available

The separate `BoltAccessibilityDiagnosticsService` can capture one explicitly armed private sample containing:

- bounded Accessibility tree;
- matching screenshot when Android permits it;
- screen dimensions/timestamp;
- best cached phone GPS fix and its age/accuracy/provider.

Raw Bolt research data may contain customer/location information and is never copied into normal privacy-safe diagnostics.

### Compiled future models

The repository also contains dormant/statistical groundwork for:

- route waypoint provenance;
- Bolt screen→geo transform with refusal when scale/orientation evidence is absent;
- delivery timeline analysis;
- route economics;
- median personal segment speed estimates after minimum samples;
- restaurant wait statistics after minimum samples;
- Valhalla `/trace_attributes` map-matching contract.

## Still blocked / not claimed complete

### Preferred scooter route

0.10 deliberately displays both stock Valhalla candidates. The app still needs a real Vilnius validation corpus before one profile can be tuned or preferred confidently.

Do not relabel generic Valhalla duration as personalized Ninebot ETA.

### Bolt pickup/drop-off coordinates

Real Bolt samples are still the dependency. Investigate actual marker semantics/bounds/viewport evidence first and validate any recovered coordinates against ground truth.

Never fabricate a coordinate merely to complete the product flow.

### Reliable full delivery outcomes

The explicit state machine is groundwork, not a complete accepted/completed detector. Platform wording and screen states need real-device samples. The tracker intentionally misses transitions rather than linking a later delivery to the wrong offer.

### Continuous GPS and personalized ETA

There is still no background-location permission and no continuous courier GPS logger.

Before personalized routing can affect the advisor:

1. implement an explicit user-controlled active-delivery/shift tracking lifecycle;
2. test battery/privacy behavior;
3. expose protected `/trace_attributes` if needed;
4. map-match real traces;
5. accumulate enough segment and venue-wait samples;
6. only then replace generic estimates where sample support is sufficient.

## Next evidence/development order

1. Install/test 0.10 live advisor on real Wolt offers.
2. Verify actual Wolt Timeline order and Android geocoding on stacked offers.
3. Collect 10–20 Vilnius route comparisons / live route runs.
4. Collect several full real Bolt map samples.
5. Tune/choose routing profile only from that evidence.
6. Extend explicit delivery lifecycle cues from real Wolt/Bolt screens.
7. Add opt-in active-work GPS trace collection and map matching.
8. Feed personal segment times + real venue waits into effective €/h only after minimum sample thresholds are met.

## Safety invariants

- clean offer persistence precedes advisor/network work;
- automatic Wolt routing is independently switchable and off by default;
- routing failure never blocks offer capture;
- no auto-accept/auto-reject;
- platform vs calculated metrics retain provenance;
- no fabricated Bolt coordinate;
- no continuous/background location permission in 0.10;
- raw customer/address/GPS data stays out of privacy-safe diagnostics.
