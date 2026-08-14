# Route Intelligence Implementation Status

Status: protected Valhalla research client implemented behind an explicit device-local gate

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

The contract is used by a manual research-only HTTPS client. Production offer capture still does not
call it.

### Protected Valhalla HTTP research client

`ValhallaRouteProvider` now fulfills the existing `RouteProvider` contract and:

- accepts only an explicitly enabled `https://` endpoint;
- sends the token in `Authorization: Bearer ...`;
- applies bounded connect/read timeouts and a 2 MiB response limit;
- returns non-2xx responses as failures without logging the token or coordinates;
- preserves HTTP status, Valhalla warnings and encoded per-leg shapes;
- remains synchronous at the provider boundary while the research UI runs it off the main thread.

Endpoint URL/token are entered on-device and stored in app-private `noBackupFilesDir`. They are not
BuildConfig values, repository files, or part of shared diagnostics. Android cleartext traffic is
disabled globally.

From **Reliability → Route intelligence research → Open route comparison**, the user can:

1. enter the protected self-hosted HTTPS base URL and bearer token;
2. explicitly enable manual research requests;
3. enter two coordinates (the validated Vilnius pair is prefilled);
4. request pedestrian-shortcut and cycleway-biased candidates;
5. inspect HTTP status, distance, generic ETA, warnings and full encoded polylines.

The screen uses `FLAG_SECURE` because it can display a token and sensitive coordinates. It does not
request device location.

### Deployed research endpoint

The validated deployment is available at `https://valhalla.zivkr.pp.ua` with a VPS-only bearer
token. A localhost-only Nginx gateway restricts the public surface to `/route` and `/status`, applies
per-client rate/concurrency/body limits and removes the Authorization header before proxying to
Valhalla. Caddy provides public TLS. The token is generated and retained only under
`/opt/valhalla/secrets/` on the VPS and must be entered manually on the device.

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

### Production offer-time routing

`RouteIntelligencePolicy.PRODUCTION_ENABLED` remains `false`. The manual research screen is the only
caller of the network provider, and routing failure cannot delay or block offer persistence.

Do not connect Valhalla to automatic offer handling until the 10–20 route Vilnius validation corpus
has been reviewed and an Android-compliant bounded current-location model has been selected.

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
- Valhalla server passes Vilnius pedestrian and bicycle smoke tests; **completed 2026-08-14**;
- 10–20 real Vilnius route comparisons collected;
- stair-heavy/pathologically long candidates rejected during validation;
- production capture remains independent;
- no raw Bolt tree is added to normal diagnostics or logs;
- network/location permissions are documented when eventually introduced.
