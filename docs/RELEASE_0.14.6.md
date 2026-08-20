# CourierPilot 0.14.6

## Bolt offer capture

- Bolt OCR is restricted to the lower offer card so Mapbox labels cannot become merchant metadata.
- One live Bolt offer is deduplicated from stable card fields before a second history row or Gallery screenshot is persisted.
- Historical repair revision 7 re-runs cleanup once on upgrade and removes already-stored duplicate rows plus their duplicate screenshot URIs on a best-effort basis.
- No merchant-name length or wording heuristic is used; short venue names remain valid.

## Bolt route recovery

- Current Bolt builds can expose only an empty Accessibility container for the map.
- CourierPilot therefore keeps Accessibility marker semantics as a fallback and can recover current/pickup/customer marker positions from the persisted clean offer screenshot.
- Full Bolt route recovery remains confidence-gated and falls back to current -> pickup when the customer marker or transform cannot be trusted.
- Manual Bolt diagnostics keep the one-shot arm active through splash/loading frames until a real three-marker offer map is visible.

## Route research / Valhalla

- Route Research screenshots are allowed again.
- The protected endpoint card now shows an explicit `TOKEN MISSING`, `ROUTE REQUESTS DISABLED`, `ENDPOINT CONFIG INVALID`, or `READY` state.
- Pressing Compare with missing provisioning now explains exactly what to fix instead of only reporting `Route intelligence is disabled`.
- The private Valhalla bearer token remains app-private and intentionally excluded from Android backup and from the repository/APK. Reinstalling CourierPilot or clearing app data therefore requires pasting the token again; normal updates preserve it.

## Version

- `versionName = 0.14.6`
- `versionCode = 33`
