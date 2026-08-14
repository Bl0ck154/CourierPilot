# Bolt Map Coordinate Recovery

Status: experimental research plan

Purpose: recover Bolt pickup/drop-off coordinates when the offer UI shows only map markers and does not expose a useful textual address.

## First principle

Do not start with computer vision unless necessary.

The first experiment is to inspect exactly what Android Accessibility exposes for the real Bolt offer screen. A visual map marker may still have useful Accessibility semantics (`text`, `contentDescription`, `viewIdResourceName`, bounds, or hidden labels).

## Test 1 — external ADB accessibility/UI dump

While a real Bolt offer is visible and before it expires:

```bash
adb devices
adb shell uiautomator dump /sdcard/bolt-offer.xml
adb pull /sdcard/bolt-offer.xml
```

Open `bolt-offer.xml` and search for strings such as:

- `Bolt`;
- `map` / `marker`;
- merchant name;
- price;
- time;
- any street/address text;
- coordinate-looking values;
- content descriptions around marker nodes.

Record useful node properties:

- `text`;
- `content-desc`;
- `resource-id`;
- `class`;
- `bounds`;
- clickable/focusable state.

A single dump is not enough. Collect several single and stacked offers because Bolt may render different trees.

## Test 2 — CourierPilot-native accessibility dump

This is more authoritative than `uiautomator dump` because it uses the same `AccessibilityService` that CourierPilot relies on.

Add a debug-only action that recursively walks `rootInActiveWindow` while Bolt is foregrounded and records a bounded node snapshot containing:

- depth/path index;
- class name;
- package name;
- text;
- content description;
- view ID resource name;
- screen bounds;
- clickable/focusable/visible flags;
- child count.

Requirements:

- debug/diagnostic feature only;
- local file or Android share sheet, never automatic upload;
- prominent warning that raw tree text may contain customer information;
- no raw dump copied into privacy-safe Reliability logs;
- redact only after we have a raw local test artifact, because over-redaction can hide the marker metadata we are trying to discover.

## Known anchor: current device location

CourierPilot can obtain the courier's current Android GPS location. Therefore the current position is a known geographic anchor, not an unknown variable.

If Bolt also renders a "you are here" dot at pixel coordinate `(x_me, y_me)`, we have a known correspondence:

`screen pixel (x_me, y_me) <-> GPS (lat_me, lon_me)`

The unknowns are primarily map scale/zoom, translation, possibly bearing/rotation, and the geographic coordinates of pickup/drop-off markers.

## Fallback: marker geometry from screenshot

If Accessibility exposes marker nodes only as generic map elements, capture:

- full Bolt offer screenshot;
- exact visible map bounds in screen pixels;
- current-location marker pixel center;
- pickup marker pixel center(s);
- drop-off marker pixel center(s);
- any route/polyline geometry if visible;
- any map labels OCR can read.

Do not infer coordinates from raw pixel distance alone until map orientation and scale are known.

## Coordinate recovery approaches

### A. Map implementation metadata

Before image matching, identify what map SDK Bolt uses in the current Android build if this can be inferred lawfully from UI/resource metadata or public package dependencies. Some map views expose semantic information or predictable projection behavior.

Do not modify or reverse-engineer the Bolt APK as a prerequisite for CourierPilot.

### B. Current-point anchored projection

If all of these are true:

- map is north-up;
- tilt is zero;
- current-location dot is visible;
- zoom can be inferred reliably;

then convert pixel offsets from the current-location dot into geographic offsets using the map projection.

This needs real screenshots to validate whether Bolt keeps north-up and how it performs fit-to-route zooming.

### C. Rendered-map registration against OSM

If zoom/orientation are unknown, align the Bolt map crop to a locally/rendered OSM reference around the known GPS point.

Potential visual anchors:

- road/intersection geometry;
- river shape and bridges;
- parks/water;
- map labels;
- visible route polyline;
- blocks/major paths.

Once the image-to-map transform is solved, transform pickup/drop-off marker pixels to latitude/longitude.

This must output a confidence score and fail closed when the match is ambiguous.

## Required sample set

For implementation, collect at least 5-10 Bolt offers with, for each sample:

1. screenshot while the complete offer map is visible;
2. Accessibility tree dump from the same moment;
3. device GPS coordinate at roughly the same timestamp;
4. marker meaning if known (pickup/drop-off/current position);
5. after accepting or navigating, the real destination/pickup coordinate or address if Bolt later reveals it, so recovered coordinates can be measured against ground truth.

Ground truth is essential. A visually plausible marker reconstruction is not enough.

## Accuracy targets

Before using recovered Bolt coordinates for route scoring:

- report error in meters against known ground truth;
- distinguish pickup and drop-off reliably;
- fail instead of inventing a coordinate when confidence is low;
- test map layouts across zoom levels and stacked orders;
- keep the platform screenshot as evidence for local debugging.

Suggested initial acceptance target: median coordinate error comfortably below normal urban block size, then tighten after real testing. Do not hardcode a production threshold before measuring the sample distribution.

## Future Android integration

Potential components:

- `LocationProvider` — bounded current GPS acquisition;
- `AccessibilityTreeSnapshotter` — debug-only tree export;
- `BoltMapDetector` — finds map viewport and marker nodes/pixels;
- `BoltMapGeoreferencer` — maps screen coordinates to lat/lon;
- `RoutePoint` model carrying coordinate provenance/confidence;
- route requests blocked when point confidence is insufficient.

## Non-goals

- no automatic order acceptance;
- no fake addresses generated from marker positions;
- no assumption that the current Bolt UI will stay unchanged;
- no routing decision based on an unvalidated map transform.
