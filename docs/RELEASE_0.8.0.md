# CourierPilot 0.8.0

Version: `0.8.0`
Version code: `14`

## Combined release scope

- searchable, paginated local offer history;
- richer local address database with visit/customer/delivery context;
- address details and map navigation;
- redesigned offer details and interactive dashboard/statistics;
- protected self-hosted Valhalla research client;
- manual Vilnius pedestrian-versus-bicycle route comparison;
- HTTPS-only endpoint validation and device-local bearer-token gate;
- HTTP status, distance, generic ETA, warnings and encoded route geometry in diagnostics;
- no production offer-routing dependency and no automatic GPS acquisition.

## Valhalla boundary

The release does not claim a custom CourierPilot costing profile. It compares the documented stock
pedestrian-shortcut and cycleway-biased bicycle candidates. Production routing remains disabled
until a real 10–20 route Vilnius validation corpus has been reviewed.

The protected endpoint token is not part of the repository or APK. Configure it manually through:

`Reliability → Route intelligence research → Open route comparison`

## Verification

- Android unit tests;
- debug APK build with JDK 17 / Android 35 / Gradle 8.10.2;
- manifest verification for `INTERNET`, `usesCleartextTraffic=false` and non-exported research UI;
- protected VPS `/status` and real pedestrian/bicycle `/route` requests;
- unauthenticated 401, action allowlist 404 and burst rate-limit 429 checks.
