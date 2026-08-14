# Route Intelligence Scaffolding Branch

Branch: `route-intelligence-scaffolding`

Status: experimental groundwork; do not merge into production merely because it compiles.

This branch turns the route-intelligence ideas into code contracts and testable math before the Valhalla VPS and Bolt ground-truth samples are available.

## What this branch adds

### Route draft / waypoint model

`RoutePlanning.kt` introduces explicit waypoint state:

- current location;
- pickup(s);
- drop-off(s);
- resolved coordinate vs unresolved textual address;
- coordinate provenance;
- confidence for recovered coordinates.

`OfferRouteDraftBuilder` converts an existing `ParsedOffer` into a draft without inventing missing coordinates. A Wolt address remains unresolved until a geocoder resolves it. A Bolt map point remains unresolved until Accessibility semantics or map recovery produces evidence.

Interfaces are reserved for `CurrentLocationSource`, `AddressGeocoder` and `RouteProvider`.

### Route comparison engine

`RouteComparisonEngine` executes both research candidates independently:

- `PEDESTRIAN_SHORTCUT`;
- `CYCLEWAY_BIASED`.

It returns both results and does **not** automatically choose a winner. One candidate can fail without destroying the other result. Real Vilnius validation decides later whether one stock profile is consistently preferable or custom Valhalla costing is required.

### Bolt screen-to-coordinate model

`BoltMapRecoveryModel.kt` defines:

- screen pixel points;
- semantic marker evidence;
- marker kinds;
- current-location GPS anchor;
- local screen-to-geographic transform;
- explicit scale/orientation evidence requirements.

Important invariant:

**Knowing the phone GPS coordinate and seeing the current-location marker is only one anchor. It does not determine map scale/zoom.**

The code refuses coordinate projection unless meters-per-pixel and map orientation are independently established.

`LocalMapTransform.fromTwoAnchors(...)` can derive scale and rotation once two independent screen+geographic anchor pairs are known. That gives a concrete future path: phone GPS/current marker + one independently recognized landmark/intersection can calibrate the Bolt viewport, after which other marker pixels can be projected.

Potential evidence sources:

- hidden Accessibility semantics;
- stable map SDK camera metadata if exposed;
- visible scale bar;
- recognized road intersections/landmarks;
- rendered-road matching against OSM;
- stable north-up behavior plus independently inferred zoom.

Never use a guessed scale as a production destination coordinate.

### Inactive Valhalla HTTP provider

`ValhallaHttpRouteProvider.kt` already provides a concrete implementation of `RouteProvider` with:

- HTTPS-only endpoint config;
- optional bearer token;
- bounded connect/read timeouts;
- JSON POST to `/route`;
- bounded HTTP error diagnostics;
- existing Valhalla request/response contract reuse.

It is intentionally **not instantiated anywhere** and the Android manifest still has no `INTERNET` permission. When the VPS exists, activation should be a small wiring task rather than a redesign.

### Valhalla/Meili map-matching contract

`ValhallaMapMatchingContract.kt` is based on the current official Valhalla `trace_attributes` API.

It can build `map_snap` requests from timestamped GPS samples and requests only useful attributes such as:

- `edge.id`;
- `edge.way_id`;
- `edge.length`;
- `edge.use`;
- `edge.surface`;
- `edge.cycle_lane`;
- matched point coordinates/type/edge index/distance;
- matched shape.

It parses those into `MapMatchedTrace`, `MatchedTraceEdge` and `MatchedTracePoint` models. This is the bridge from raw phone GPS history to real Valhalla graph edges used by personal traversal statistics.

### Personal route learning

`PersonalRouteLearning.kt` defines the first statistical layer for real data:

- raw GPS samples;
- map-matched edge traversals;
- per-edge travel statistics;
- restaurant wait observations/statistics.

The initial algorithm is intentionally simple:

- median traversal time per matched edge;
- at least 5 observations before an edge gets a personalized time;
- median restaurant wait;
- at least 3 observations before restaurant wait is treated as usable personalized data.

This is a conservative baseline before time-of-day buckets, recency weighting, weather or any ML.

### Delivery lifecycle model

`DeliveryTimeline.kt` defines explicit observed states:

- offer captured;
- accepted;
- arrived at pickup;
- picked up;
- arrived at drop-off;
- delivered;
- cancelled.

Each event carries timestamp, source and confidence. `DeliveryTimelineAnalyzer` only derives metrics from events that actually exist; it never infers completion because enough time passed.

This allows future real metrics such as:

- accepted -> completed duration;
- per-venue arrived -> picked-up wait;
- stacked pickup waits by stop key.

The still-missing part is reliable Accessibility/GPS classification that produces these events on real Wolt/Bolt delivery screens.

### Offer economics

`OfferEconomics.kt` calculates transparent route-based metrics from known inputs:

- route distance;
- route duration;
- optional restaurant wait;
- optional handoff time;
- `€/km`;
- effective `€/h`.

It deliberately outputs no `GOOD/BAD`, no auto-accept and no hidden score. Future UI should show which components were personalized and which came from baseline Valhalla/platform estimates.

### Isolated research database

`RouteResearchDatabase.kt` defines a separate `route_research.db`. Production code does not open it.

Prepared tables cover:

- GPS recording sessions;
- raw GPS samples;
- matched edge traversals;
- delivery timeline events;
- venue wait observations;
- real-route validation samples;
- Bolt map recovered-vs-ground-truth coordinates.

This is intentionally separate from the proven offer-history DB so experimental schema work cannot destabilize offer capture.

## What is still blocked on real external evidence

### Valhalla endpoint

The HTTP class exists, but activation still needs:

- deployed HTTPS endpoint;
- real auth configuration;
- `INTERNET` permission;
- background-thread/cancellation wiring;
- real Vilnius route smoke tests;
- route-shape debug UI.

### Android location

Need to choose the minimal-permission implementation. Offer-time current location can be implemented separately from later continuous historical GPS recording. Do not silently turn route research into permanent background tracking.

### Wolt geocoding

Text addresses can already become unresolved pickup/drop-off waypoints. A real geocoder still needs to be chosen/deployed and validated on Lithuanian address quality.

### Bolt coordinate recovery

Need several real datasets containing:

- Accessibility tree dump;
- screenshot of the same offer/map;
- current phone GPS at capture time;
- eventual known pickup/drop-off ground truth.

Only then implement actual marker recognition and scale/orientation inference.

### Lifecycle event detection

Need real Wolt/Bolt accepted/pickup/delivery screens and event samples before classifiers can safely emit `ACCEPTED`, `ARRIVED_PICKUP`, `PICKED_UP` and `DELIVERED`.

### Historical learning

Need opted-in GPS traces plus the deployed Valhalla `trace_attributes` endpoint before matched-edge records become real rather than fixtures.

## Intended future pipeline

```text
Offer captured
   |
   +-- current phone GPS -----------------------------+
   |                                                  |
   +-- Wolt textual stops -> geocoder -> coordinates  |
   |                                                  +--> OfferRouteDraft
   +-- Bolt map -> semantics/map recovery -> coords   |
                                                      v
                                            Valhalla comparison
                                         pedestrian / cycleway
                                                      |
                                                      v
                                           route distance/time
                                                      |
                         +----------------------------+
                         |
               delivery lifecycle + real GPS trace
                         |
                         +--> trace_attributes / Meili
                         |          |
                         |          +--> matched graph edges
                         |                    |
                         |                    +--> personal segment times
                         |
                         +--> real venue wait times
                                      |
                                      v
                                OfferEconomics
                           €/km + effective €/h
```

## Tests in this branch

Tests verify:

- parsed offers remain unresolved instead of receiving fabricated coordinates;
- Bolt projection is impossible without scale/orientation evidence;
- known pixel offsets project plausibly around Vilnius;
- two known anchors derive a usable transform;
- route comparison preserves one candidate when the other fails;
- personal segment ETA is gated by minimum real sample count;
- economics includes route + wait transparently;
- HTTPS Valhalla endpoint contract;
- `trace_attributes` request/response parsing;
- delivery lifecycle never invents completion;
- isolated research SQLite schema is created as expected.

## Merge policy

Keep this branch and draft PR unmerged until it is useful to the next implementation stage.

Before production merge, at minimum:

1. CI green;
2. Valhalla endpoint available or there is a concrete reason the contracts are needed on main;
3. real Bolt dump reviewed;
4. no production behavior activated accidentally;
5. no new location/network permission added without documented user-facing behavior.
