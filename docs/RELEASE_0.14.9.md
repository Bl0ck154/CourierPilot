# CourierPilot 0.14.9

## Bolt overlapping-marker fallbacks

- Remember pickup addresses from a previously accepted Bolt task before a new add-on offer replaces the legacy lifecycle pointer.
- Feed those active pickup addresses into the new offer route, so a fully covered old blue pickup pin does not disappear from routing.
- Keep the active pickup first and deduplicate only the same building/address when the add-on comes from the same restaurant.
- Remove remembered active pickups when an explicit `picked up` screen for that building is observed; stale entries expire after 3 hours.
- Stop collapsing distinct stacked customer markers merely because projected coordinates are within 35 m.
- Reduce projected marker dedupe to effectively-identical points only.
- Make screenshot marker detection more tolerant of partially overlapping blue/green pins while retaining duplicate-tip suppression.
- Preserve fail-closed behavior: if Bolt says two drop-offs but only one customer pin is actually recoverable, CourierPilot still routes only the known pickups instead of inventing a full route.

## Tests

Regression coverage now includes active-pickup merge/dedupe, close-but-distinct customer stops, single-pin duplicate protection, and partially overlapping customer pins.
