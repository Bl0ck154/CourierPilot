# CourierPilot 0.15.74 audit fixes

This release follows the 0.15.73 dead-code cleanup and closes the concrete reliability/maintenance findings from the full-app audit.

## Arrival reminder durability

- Armed access-code reminders are persisted with their delivery identity, code set, TTL, and resolved destination.
- Process restart restores the reminder only while it is still inside the original TTL and the code still exists in address memory.
- Terminal delivery/cancellation, a changed delivery identity, code deletion, or TTL expiry clears both memory and persisted state.

## Learned entrance points

- `Works` feedback on a code reminder samples the courier's fresh accurate current location.
- A learned entrance is preferred only after repeated samples.
- The robust center rejects distant outliers and samples expire after 180 days.
- Geocoding remains the fallback when learned evidence is insufficient.

## Access-code quality

- Arrival notifications expose `Works` and `Wrong / old` actions.
- Rejected derived codes are deleted immediately while raw delivery-screen evidence stays intact.
- Fresh live evidence can relearn a previously rejected code.
- Single-observation hints older than 180 days are suppressed; repeatedly seen hints remain usable longer and confirmed hints remain usable.
- Reminder ordering prefers higher-confidence/recent evidence.

## Screenshot storage

- New installs use a 90-day screenshot retention period instead of unlimited accumulation.
- Cleanup scans only `Pictures/CourierOffers` and leaves offer/database history intact.
- A Storage app shortcut opens controls for 7/30/90-day or Forever retention, screenshot capture enable/disable, current file count/size, and manual cleanup.

## Naming

- Profitability models now use currency-neutral `moneyPerKilometer` / `effectiveMoneyPerHour` names instead of implying that every market is EUR.
- Scoring and displayed currency behavior are unchanged.

## Still intentionally retained

- Wolt/Bolt legacy parser compatibility branches remain. They protect staged/A-B courier-app UI rollouts and must not be removed merely because a symbol or label says `legacy`.
- Historical one-shot data repairs remain until the project declares an explicit minimum supported upgrade baseline.
