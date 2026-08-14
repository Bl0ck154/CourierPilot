# Route Intelligence Scaffolding Branch

Branch: `route-intelligence-scaffolding`

Status: experimental groundwork; do not merge into production merely because it compiles.

This branch intentionally turns the route-intelligence ideas into code contracts and testable math before the Valhalla VPS and Bolt ground-truth samples are available.

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

Interfaces are reserved for:

- `CurrentLocationSource`;
- `AddressGeocoder`;
- `RouteProvider` (already defined in the previous groundwork).

### Route comparison engine

`RouteComparisonEngine` executes both research candidates independently:

- `PEDESTRIAN_SHORTCUT`;
- `CYCLEWAY_BIASED`.

It deliberately returns both results and does **not** automatically choose a winner. One candidate can fail without destroying the other result.

A later real-Vilnius validation layer may decide whether one profile is consistently preferable or whether custom Valhalla costing is required.

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

The code therefore refuses coordinate projection unless both are independently established:

- meters per pixel;
- map orientation/rotation.

Once those are known, `LocalMapTransform` converts a marker pixel position into an approximate latitude/longitude using a local tangent/equirectangular approximation suitable for short city-scale distances.

This is scaffolding, not proof that Bolt marker recovery is solved.

Potential future sources for scale/orientation evidence include:

- hidden Accessibility semantics;
- stable map SDK camera metadata if somehow exposed;
- visible scale bar;
- two or more recognized map landmarks/road intersections;
- matching rendered road geometry to OSM;
- stable north-up behavior plus inferred zoom from map features.

Never use a guessed scale as a production destination coordinate.

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

This provides a conservative baseline before considering time-of-day buckets, recency weighting, weather or any ML.

### Offer economics

`OfferEconomics.kt` calculates transparent route-based metrics from known inputs:

- route distance;
- route duration;
- optional restaurant wait;
- optional handoff time;
- `€/km`;
- effective `€/h`.

It deliberately outputs no `GOOD/BAD`, no auto-accept and no hidden score.

Future UI should show which components were personalized and which came from baseline Valhalla/platform estimates.

## What is still blocked on real external evidence

### Valhalla endpoint

Need the deployed self-hosted server before implementing:

- HTTPS HTTP client;
- auth;
- timeouts/cancellation;
- endpoint settings;
- real Vilnius route requests;
- route-shape display/debug screen.

### Android location

Need to choose the minimal-permission implementation. Offer-time current location can be implemented separately from later continuous historical GPS recording.

Do not silently turn route research into permanent background tracking.

### Wolt geocoding

Text addresses can already become unresolved pickup/drop-off waypoints. A real geocoder still needs to be chosen/deployed.

Prefer a self-hosted/open-data-compatible approach if practical, but validate Lithuanian address quality before integrating.

### Bolt coordinate recovery

Need several real datasets containing:

- new Accessibility tree dump;
- screenshot of the same offer/map;
- current phone GPS at capture time;
- eventual known pickup/drop-off ground truth.

Only then implement marker detection/scale/orientation inference.

### Map matching and historical learning

Need a Valhalla/Meili endpoint plus opted-in GPS traces before `MatchedEdgeTraversal` records are real rather than test fixtures.

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
                          +---------------------------+
                          |
             historical segment times / venue wait
                          |
                          v
                     OfferEconomics
                    €/km + effective €/h
```

## Tests in this branch

`RouteIntelligenceScaffoldingTest` verifies:

- parsed offers remain unresolved instead of receiving fabricated coordinates;
- Bolt projection is impossible without scale/orientation evidence;
- known pixel offsets project plausibly around Vilnius once transform evidence exists;
- route comparison preserves a surviving candidate when the other fails;
- personal segment ETA is gated by minimum real sample count;
- economics calculations include route + wait transparently.

## Merge policy

Keep this branch/draft PR unmerged until it is useful to the next implementation stage.

Before production merge, at minimum:

1. CI green;
2. Valhalla endpoint available or there is a concrete reason the contracts are needed on main;
3. real Bolt dump reviewed;
4. no production behavior activated accidentally;
5. no new location/network permission added without documented user-facing behavior.
