# CourierPilot Route Intelligence Roadmap

Status: research / future implementation plan

This document preserves the product direction for turning CourierPilot from an offer recorder into a route-aware courier assistant. It is intentionally written as an AI-agent handoff: future agents should treat the constraints and open questions below as product requirements, not as already-solved facts.

## Product goal

At offer time, CourierPilot should reduce the courier's mental work without making the acceptance decision for them.

Desired output for a captured offer:

- platform offer price;
- platform-provided distance/time when available;
- independently calculated route distance;
- independently estimated route time;
- price per route kilometer;
- predicted effective earnings per hour once enough real history exists;
- compact optional voice summary;
- no automatic accept/reject action.

The user remains the decision maker.

## Routing philosophy

The courier uses a Ninebot-class electric scooter in Vilnius and often chooses shorter urban shortcuts rather than behaving like a standard road cyclist.

Desired route preference, in order:

1. short practical route;
2. avoid stairs/steps strongly;
3. prefer cycleways when they are useful and do not create a large detour;
4. allow ordinary pedestrian/path topology where practical;
5. do not blindly force a conventional bicycle route;
6. do not invent access through roads/paths that routing data marks as inaccessible.

Important: stock Valhalla does not expose one built-in costing mode that exactly means "pedestrian topology + strong stair avoidance + cycleway preference". The initial implementation must compare available costing models instead of pretending this custom profile already exists.

## Baseline Valhalla strategy

Run a self-hosted Valhalla instance using the Lithuania OpenStreetMap extract.

For each route test, obtain at least two candidates:

### Candidate A — pedestrian shortcut profile

Use `costing=pedestrian` with:

- a high `step_penalty` so stairs are strongly avoided;
- pedestrian/walkway topology available;
- no assumption that the resulting ETA represents actual scooter speed.

This candidate is intended primarily for route geometry and distance.

### Candidate B — cycleway-biased bicycle profile

Use `costing=bicycle` with:

- `bicycle_type=hybrid` or another type validated against Vilnius routes;
- low `use_roads` to increase preference for cycleways/paths;
- practical `avoid_bad_surfaces` value;
- speed treated as a preliminary estimate only.

### Validation

Do not promote either candidate as the CourierPilot route until it has been compared against real routes the user actually rides in Vilnius.

Build a small validation corpus of 10-20 real start/end pairs with the route the user would actually choose. Record:

- Valhalla pedestrian distance and geometry;
- Valhalla bicycle distance and geometry;
- platform route if visible;
- user's real route / GPS trace when available;
- obvious errors: stairs, inaccessible passage, pointless detour, missed cycleway/shortcut.

If neither stock profile is consistently good enough, investigate a custom Valhalla costing implementation/fork rather than stacking arbitrary heuristics in the Android client.

## Wolt route acquisition

When Wolt exposes textual pickup/drop-off addresses through Accessibility/OCR:

1. parse address/stops as CourierPilot already does;
2. geocode them to coordinates;
3. combine with current device GPS;
4. request route `current -> pickup -> drop-off(s)`;
5. keep platform distance/time and calculated distance/time as separate fields.

Never overwrite platform-provided data with calculated data; store provenance.

## Bolt route acquisition

Bolt is the harder platform because offer screens may show map markers without a textual destination address.

Use a staged approach:

1. inspect the Bolt Accessibility tree first;
2. if marker coordinates/semantic labels are exposed, use them directly;
3. if not, capture marker bounds and the map screenshot;
4. use the current device GPS as a known map anchor;
5. infer map transform / marker coordinates from the rendered map;
6. pass recovered latitude/longitude directly to Valhalla — a human-readable address is not required.

See `BOLT_MAP_COORDINATE_RECOVERY.md`.

## Current-location point

The user's current point is not an unsolved problem. CourierPilot can request Android location permission and obtain the current device location itself.

The map-recovery problem is only to determine the unknown Bolt pickup/drop-off marker coordinates. Knowing the user's GPS point is an important anchor that should simplify that task.

## Live advisor (future)

Once route extraction is trustworthy, a compact live offer card may show:

- offer price;
- platform km / platform ETA;
- calculated route km / route ETA;
- €/km;
- predicted €/h;
- restaurant historical wait adjustment when enough data exists;
- confidence/provenance labels.

Avoid simplistic GOOD/BAD ratings based only on price. Any future verdict must expose the numbers that produced it.

## Historical personalized routing

Long-term, record opted-in GPS traces during active courier work and map-match them to the OSM/Valhalla road graph.

Possible learned signals:

- user's actual traversal speed by road/path segment and time bucket;
- repeated shortcuts the stock router does not prefer;
- intersection/area delay patterns;
- actual restaurant pickup wait distribution;
- real completion time compared with platform and Valhalla estimates.

This is not an AI requirement. The first useful version can be statistical: median/trimmed-mean traversal time per matched segment with minimum sample thresholds and recency weighting.

Never claim personalized estimates until there is enough real data. Fall back to baseline routing when sample support is weak.

See `PERSONAL_ROUTE_LEARNING.md`.

## Privacy and data boundaries

- GPS history is sensitive and must be local-first by default.
- Raw customer addresses must not be sent to third-party analytics.
- A self-hosted Valhalla server only needs coordinates for routing.
- If the self-hosted router is remote, document that coordinates leave the phone and provide a way to disable route intelligence.
- Do not include customer address/GPS data in privacy-safe diagnostics.

## Implementation order

1. Deploy and validate self-hosted Valhalla for Lithuania.
2. Add an internal route-client abstraction to CourierPilot, initially disabled behind a feature flag.
3. Add Android current-location acquisition with explicit permission and bounded sampling.
4. Build a route test screen/debug harness that accepts coordinates and compares pedestrian vs bicycle candidates.
5. Inspect and capture the Bolt Accessibility tree on real offers.
6. Prototype Bolt marker coordinate recovery only after collecting real samples.
7. Integrate Wolt textual-address routing where coordinates can be obtained reliably.
8. Add live advisor UI only after route accuracy is validated.
9. Add outcome/GPS learning later, with explicit data-retention controls.

## Required evidence before production activation

- real Vilnius test routes, not only synthetic tests;
- no stair-heavy route presented as preferred when an obvious stair-free alternative exists;
- no fabricated Bolt destination coordinate;
- route provenance visible in diagnostics;
- timeouts and failure-safe behavior: routing failure must never block offer capture;
- feature can be disabled independently of core CourierPilot capture.

## Official references

- Valhalla docs: https://valhalla.github.io/valhalla/
- Valhalla route API: https://valhalla.github.io/valhalla/api/turn-by-turn/overview/
- Valhalla source: https://github.com/valhalla/valhalla
- Lithuania OSM extract: https://download.geofabrik.de/europe/lithuania.html
