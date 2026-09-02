<p align="center">
  <img src="docs/assets/courierpilot-banner.svg" alt="CourierPilot — Android companion for Wolt and Bolt couriers" />
</p>

<p align="center">
  <a href="https://github.com/Bl0ck154/CourierPilot/actions/workflows/ci.yml"><img src="https://github.com/Bl0ck154/CourierPilot/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/version-0.11.0-53E09C" alt="Version 0.11.0">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/data-local--first-1f6feb" alt="Local-first">
</p>

> **Unofficial project.** CourierPilot is not affiliated with, endorsed by, or sponsored by Wolt, Bolt, or their affiliates.

## What it is

CourierPilot is a local-first Android companion for Wolt/Bolt courier work. It archives priced offers shown on the phone, keeps local history/address context, tracks platform presence, provides a transparent post-capture advisor, and can now explicitly record real ridden GPS traces for later personal-route learning.

**The clean offer is captured first.** The proof screenshot and offer DB row are saved before CourierPilot draws advisor UI or starts offer-time routing.

## 0.11 highlights

| Feature | Behavior |
|---|---|
| 💶 Offer history | Saves only after a plausible non-zero price is visible |
| 📊 Live advisor | Price, platform km/ETA, €/km and platform-ETA-derived €/h range |
| 🧭 Wolt route experiment | Explicit opt-in `fresh GPS → captured Timeline stops → geocode → Valhalla` |
| 📦 Ordered stacked routes | Sequential Wolt Timeline stop order is retained |
| 🟠🔵 Route candidates | Pedestrian-shortcut and cycleway-biased results stay separate; no automatic winner |
| 🔊 Voice | Optional spoken offer summary, off by default |
| 🛰 Ride trace | Explicit foreground GPS recording into local `gps_sessions/gps_samples` |
| 📤 Trace export | Latest ride trace can be shared deliberately as GeoJSON |
| 🧪 Bolt research | Private one-shot Accessibility tree + screenshot + cached GPS bundle |
| 🧾 Outcome groundwork | Certain `OFFER_CAPTURED` + explicit monotonic delivery-state cues only |

CourierPilot does not auto-accept/auto-reject offers and does not convert its metrics into a hidden GOOD/BAD verdict.

## Live advisor

After the clean offer has been archived, CourierPilot can show a temporary Accessibility overlay such as:

```text
Wolt · €6.40 · 4.0 km · 20–30 min
€1.60/km · €12.8–19.2/h · platform data

Calculated route · 3 points
🟠 pedestrian: 4.72 km · generic 56.0 min
🔵 cycleway: 5.01 km · generic 18.4 min
No route winner selected
```

Platform-provided and independently calculated numbers keep their provenance. Generic Valhalla duration is not presented as personalized scooter ETA.

The card includes `Wolt route ON/OFF` and `Voice ON/OFF`; both are independent from offer capture and are off by default where privacy-sensitive.

## Experimental Wolt routing

With Wolt routing explicitly enabled, CourierPilot can run **after** an offer is already stored:

1. obtain one bounded fresh phone-location fix;
2. preserve the parsed Wolt Timeline stop order, including interleaved stacked stops;
3. resolve textual stops through the device Android `Geocoder` with deadlines;
4. fail the calculated run if a required stop cannot be resolved;
5. send the ordered coordinates to the configured protected self-hosted Valhalla endpoint;
6. request pedestrian-shortcut and cycleway-biased candidates;
7. store waypoint/candidate provenance locally in `route_research.db`.

Calculated metrics never overwrite platform distance/ETA.

## Ride trace — 0.11

0.11 adds an explicit route-learning recorder for real scooter rides.

Open it by **long-pressing the CourierPilot launcher icon → Ride trace**. The screen provides Start/Stop, current point count/distance, latest-session summary and deliberate GeoJSON sharing.

When Start is pressed from the visible screen:

- runtime foreground location permission is required;
- on Android 13+ notification permission is also required so recording stays visibly controllable;
- CourierPilot starts a `location` foreground service with an ongoing notification and **Stop trace** action;
- the requested cadence is about 2 seconds / 2 meters;
- very poor-accuracy points (>80 m) and extreme GPS jumps are ignored;
- accepted points, accuracy and reported speed stay in local `route_research.db`;
- a service heartbeat distinguishes a temporarily bad GPS fix from a dead/interrupted recorder;
- the service uses `START_NOT_STICKY`: it is not silently resurrected after a process kill/reboot.

0.11 does **not** upload traces, automatically map-match them, or feed them into offer decisions yet. The purpose is to collect trustworthy real evidence for the next phase.

## Outcome groundwork

A persisted offer creates a certain `OFFER_CAPTURED` event. Later events are accepted only from explicit courier-screen wording and monotonic transitions:

```text
OFFER_CAPTURED → ACCEPTED → ARRIVED_PICKUP → PICKED_UP → ARRIVED_DROPOFF → DELIVERED
```

Screen disappearance is not acceptance/completion evidence. Uncertain cases remain missing rather than being guessed.

## Bolt route research

Bolt still needs real marker-coordinate evidence. The separate explicitly armed diagnostics service can save a private Accessibility tree + matching screenshot + cached phone GPS metadata. CourierPilot does not fabricate Bolt coordinates when viewport/scale/orientation evidence is insufficient.

See [`docs/BOLT_MAP_COORDINATE_RECOVERY.md`](docs/BOLT_MAP_COORDINATE_RECOVERY.md) and [`docs/ROUTE_RESEARCH_TESTING.md`](docs/ROUTE_RESEARCH_TESTING.md).

## Local data and privacy

- `courier_offers.db` — priced offer history;
- `courier_meta.db` — platform presence, address visits/context and access-code memory;
- `route_research.db` — route validation, live-route provenance, lifecycle research and GPS ride traces;
- `Pictures/CourierOffers` — final priced proof screenshots.

Automatic Wolt routing is off by default. Textual stops are passed to the device Android Geocoder implementation; resolved ordered coordinates are sent to the configured protected self-hosted Valhalla server. Manual ride traces remain local unless deliberately shared. There is no `ACCESS_BACKGROUND_LOCATION`, CourierPilot account or cloud sync.

Raw offer text, exact addresses, GPS points and Bolt samples are sensitive data and stay out of privacy-safe Reliability diagnostics.

**Remote diagnostics are optional and off by default.** When explicitly enabled in Reliability, CourierPilot uploads only bounded technical event metadata (random app-local install/session IDs, app/device version, platform, stage and sanitized message) to the project's self-hosted diagnostics endpoint. Notification body text is redacted before upload; address/GPS diagnostic stages are reduced to a redacted marker; screenshots, OCR frames, customer names, exact addresses and GPS coordinates are never included. Disabling the toggle clears the pending remote queue. Local diagnostics and normal offer capture continue to work with remote diagnostics disabled.

## Installation

1. Install a release-signed CourierPilot APK.
2. Enable Notification access.
3. Enable **Accessibility → CourierPilot screen capture**.
4. Grant foreground location only for route features/ride traces.
5. For ride traces on Android 13+, allow notifications so the recorder remains visibly controllable.
6. Enable **CourierPilot Bolt diagnostics** separately only while collecting Bolt research samples.

## Stack

Android 11+ · Kotlin · Android SDK 35 · Java 17 · Jetpack Compose / Material 3 · NotificationListenerService · AccessibilityService · ML Kit Text Recognition · Android LocationManager/Geocoder · location foreground service · protected self-hosted Valhalla · SQLiteOpenHelper · JUnit/Robolectric · GitHub Actions.

## Build

```bash
gradle testDebugUnitTest assembleDebug
```

Release signing is documented in [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md).

## Current release

**CourierPilot 0.11.0** (`versionCode 17`)

0.11 adds the first explicit real-ridden GPS corpus needed for future map matching and personal scooter ETA while keeping trace recording user-controlled and local-first.

## Next work

- validate real Wolt stacked Timeline/geocoding/routes on the phone;
- recover Bolt marker coordinates from real private samples;
- expand explicit delivery lifecycle cue coverage;
- expose/protect Valhalla `/trace_attributes` for trace map matching;
- convert matched real traces into conservative personal segment-time statistics;
- combine supported personal ride time + real venue waits into effective €/h only after minimum sample thresholds;
- tune/select a preferred/custom Vilnius scooter routing profile from evidence, not assumptions.

See [`docs/ROUTE_INTELLIGENCE_ROADMAP.md`](docs/ROUTE_INTELLIGENCE_ROADMAP.md).

---

<p align="center">Built for real courier use: inspectable numbers, explicit provenance, no invented certainty.</p>
