# Route Intelligence Implementation Status

Status: 0.11 adds explicit foreground GPS ride traces on top of the 0.10 live advisor/Wolt route experiment. Bolt coordinate recovery, trace map matching and personalized ETA still require real evidence.

## Implemented now

### Fail-safe offer boundary

The clean priced-offer screenshot and `courier_offers.db` insert happen before advisor, GPS, geocoder or Valhalla work. Post-capture failures cannot roll back an archived offer.

### Live advisor + Wolt routing

CourierPilot can show transparent platform arithmetic and, when explicitly enabled, run a post-capture Wolt route comparison using:

`fresh one-shot GPS → captured ordered Timeline stops → bounded Android geocoding → protected Valhalla`.

Both pedestrian-shortcut and cycleway-biased candidates remain visible separately. No route winner, GOOD/BAD verdict or Accept/Decline automation is introduced.

### Ordered stacked stop model

`OfferParser.orderedRouteStops` preserves sequential Wolt Timeline order, including later pickups after earlier customer drop-offs. This prevents stacked routes from being silently reordered into all-pickups-then-all-drop-offs.

### Route provenance

`route_research.db` stores manual comparisons plus live advisor runs, ordered resolved waypoints, coordinate provenance/confidence and candidate summaries/failures.

### Conservative outcome timeline

`OFFER_CAPTURED` is certain after durable persistence. Later states require explicit courier-screen wording and monotonic progression. Missing evidence remains missing instead of being inferred from screen disappearance.

### Explicit GPS ride trace — 0.11

The repository now includes `RouteTraceActivity` + `GpsTraceService`:

- user starts recording from a visible screen;
- Android location foreground service with ongoing notification + Stop action;
- foreground location permission required;
- Android 13+ notification permission required by CourierPilot before Start so the trace remains visibly controllable;
- no `ACCESS_BACKGROUND_LOCATION`;
- `START_NOT_STICKY`, so process death/reboot does not silently resume tracing;
- requested updates around 2 s / 2 m;
- >80 m accuracy fixes and extreme GPS jumps ignored;
- service heartbeat detects an interrupted recorder independently from GPS fix availability;
- orphaned open DB sessions close on the next explicit start/stop;
- accepted points store time, coordinate, accuracy and reported speed in existing `gps_sessions/gps_samples`;
- latest local session shows point count, distance and average speed;
- latest trace can be deliberately exported as GeoJSON.

Practical access: long-press the CourierPilot launcher icon and choose **Ride trace**.

### Bolt research sample

The separate one-shot Bolt diagnostics Accessibility service still captures private tree + screenshot + cached GPS metadata when explicitly armed. No Bolt coordinate is fabricated from insufficient evidence.

### Dormant next-stage contracts/statistics

Compiled groundwork already exists for:

- Valhalla `/trace_attributes` map matching;
- matched-edge traversals;
- conservative personal segment statistics with minimum samples;
- venue wait statistics;
- route economics;
- Bolt screen→geo transforms that refuse projection without scale/orientation evidence.

## Still evidence-gated

### Real Wolt validation

Need real phone checks of Timeline order, Android-geocoded stops and both route candidates on known Vilnius jobs, including stacked/interleaved routes.

### Bolt marker coordinates

Need real private Bolt samples and ground truth. Investigate semantic marker data/view IDs/viewport geometry before screenshot-based projection. Unknown remains unknown.

### Map matching

The Android trace recorder is now present, but protected `/trace_attributes` still needs to be exposed/validated on the Valhalla gateway before completed traces can be map-matched in production-like research.

### Personalized scooter ETA

Do not feed a trace directly into offer-time ETA. First map-match multiple real rides, reject low-confidence/outlier traversals and meet per-segment minimum sample thresholds. Baseline routing remains fallback wherever personal support is thin.

### Venue wait / full effective €/h

Reliable pickup-wait statistics need better real lifecycle cue coverage and repeated observations per venue. Only then combine supported personal route time + supported venue wait + handoff overhead.

## Next order

1. Install/test 0.11 on real Wolt offers and several known scooter routes.
2. Record/export several real Ride trace sessions.
3. Collect several private Bolt map samples.
4. Expose/validate protected Valhalla `/trace_attributes` on the gateway.
5. Map-match completed traces and retain matched-edge confidence.
6. Accumulate conservative personal segment-time statistics.
7. Expand explicit delivery lifecycle cues from real platform screens.
8. Feed supported venue waits + personal route times into effective €/h only after sample thresholds.
9. Select/tune a preferred/custom Vilnius scooter profile from the real corpus.

## Safety invariants

- clean offer persistence precedes advisor/network work;
- automatic Wolt routing is off by default and independent from capture;
- real ride tracing starts only by explicit user action;
- no silent GPS restart or `ACCESS_BACKGROUND_LOCATION`;
- no auto-accept/auto-reject;
- platform and calculated metrics keep provenance;
- no fabricated Bolt coordinate;
- raw addresses/GPS stay out of privacy-safe diagnostics.
