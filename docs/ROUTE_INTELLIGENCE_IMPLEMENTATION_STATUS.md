# Route Intelligence Implementation Status

Status: groundwork implemented behind research-only boundaries

This file describes what is already in the Android repository versus what still requires a real Valhalla server and real-device Bolt samples.

## Implemented now

### Provider-neutral route domain

`RouteIntelligence.kt` defines:

- route points;
- route requests/results;
- pedestrian-shortcut and cycleway-biased research profiles;
- a provider-neutral `RouteProvider` boundary;
- validation constraints;
- `PRODUCTION_ENABLED = false`.

The production offer capture pipeline does not depend on routing and must remain functional when route intelligence is absent or broken.

### Valhalla JSON contract

`ValhallaContract.kt` can already:

- build the pedestrian candidate payload with strong `step_penalty`;
- build the cycleway-biased bicycle candidate payload;
- parse Valhalla distance, duration and per-leg encoded shapes;
- preserve provider/profile provenance.

There is deliberately no HTTP client wired into production yet. The app manifest still has no `INTERNET` permission.

### Bolt Accessibility tree collection

A separate research-only service, `BoltAccessibilityDiagnosticsService`, is registered and scoped to:

`com.bolt.deliverycourier`

It is independent from `OfferAccessibilityService` so route research cannot mutate the production offer-capture state machine.

From **Reliability → Route intelligence research** the user can:

1. enable `CourierPilot Bolt diagnostics` in Android Accessibility settings;
2. arm exactly one Bolt tree dump;
3. open/wait for a real Bolt offer/map screen;
4. return to Reliability;
5. share the saved tree deliberately or delete it.

The serializer records, with bounded output:

- node depth/index;
- class;
- text;
- content description;
- view resource ID;
- screen bounds;
- visible/clickable/focusable/scrollable/enabled flags;
- child count.

The dump is capped at 1,500 nodes / about 180k characters and stays in app-internal storage until explicitly shared.

IMPORTANT: the raw tree can contain customer/merchant/private delivery data. It is not included in the normal privacy-safe Capture event log.

## Intentionally not implemented yet

### Network / Valhalla HTTP

Do this only after the self-hosted endpoint is deployed and validated.

Then add:

- `INTERNET` permission;
- HTTPS endpoint configuration;
- authentication handling;
- timeouts/cancellation;
- a `ValhallaRouteProvider` implementation that fulfills the existing `RouteProvider` contract;
- feature flag / settings gate;
- failure-safe asynchronous execution.

Routing failure must never delay or block offer persistence.

### Device GPS acquisition

Do not casually add background location permissions merely to make a prototype work.

Before implementation, choose an Android-compliant acquisition model for offer-time location. The likely options are bounded current-location acquisition while CourierPilot is legitimately active, or a foreground location component while route intelligence is explicitly enabled.

The app should request the minimum permissions required and must not continuously track location unless the user explicitly enables the later personalized-route feature.

### Bolt map-coordinate recovery

The next dependency is real data.

Collect several Bolt offer trees using the new diagnostics service. For each sample, also retain the matching offer screenshot and eventual ground-truth pickup/drop-off location when practical.

Investigate in this order:

1. hidden marker semantics / content descriptions;
2. stable map/marker view IDs and bounds;
3. current-location marker and route-line geometry;
4. map viewport/zoom/orientation clues;
5. only then screenshot-to-map coordinate recovery.

Never fabricate a Bolt coordinate.

### Live advisor / historical learning

These remain later phases after route geometry is trustworthy. See:

- `ROUTE_INTELLIGENCE_ROADMAP.md`
- `BOLT_MAP_COORDINATE_RECOVERY.md`
- `PERSONAL_ROUTE_LEARNING.md`
- `VALHALLA_SELF_HOSTING_PLAN.md`

## Validation gates before merge into active routing behavior

- Android CI/tests green;
- one-shot Bolt dump works on a real Bolt offer;
- Valhalla server passes Vilnius pedestrian and bicycle smoke tests;
- 10–20 real Vilnius route comparisons collected;
- stair-heavy/pathologically long candidates rejected during validation;
- production capture remains independent;
- no raw Bolt tree is added to normal diagnostics or logs;
- network/location permissions are documented when eventually introduced.
