# Personal Route Learning

Status: long-term research / future implementation plan

Goal: improve CourierPilot route-time estimates and route choice from the courier's own real GPS history instead of pretending that a generic bicycle or pedestrian ETA is always correct.

## Core idea

The routing graph remains real OpenStreetMap/Valhalla data. Personalization changes the estimated traversal cost/time of routes based on observed history.

This should start as transparent statistics, not as an opaque ML model.

## Data collection

Only while an active courier work session/delivery is confidently detected and the user has enabled route learning, collect bounded location samples such as:

- timestamp;
- latitude/longitude;
- Android-reported accuracy;
- speed when available;
- bearing when available;
- source/platform context without customer PII.

Do not continuously collect location outside courier work just to build a bigger dataset.

## Map matching

Raw GPS points are noisy. Map-match completed traces to the Valhalla/OSM graph.

For each matched edge/segment retain aggregate observations such as:

- observed traversal seconds;
- distance;
- time bucket / day type;
- sample quality;
- direction;
- recency.

Do not treat one noisy traversal as truth.

## First useful estimator

Before any ML, maintain robust per-segment statistics:

- sample count;
- median traversal speed/time;
- trimmed mean;
- recent median;
- optional time-of-day buckets.

Use a minimum support threshold. When support is weak, blend strongly back toward the base Valhalla estimate.

Conceptually:

`personal_time = confidence * observed_time + (1 - confidence) * base_time`

where confidence rises with good recent sample support and never becomes 1 solely from a tiny sample.

## Restaurant waiting time

Route travel time is only part of delivery time. Separately learn pickup waiting distributions by merchant/location when CourierPilot can reliably detect arrival and departure/pickup events.

Useful metrics:

- number of observed pickups;
- median wait;
- 25th/75th percentile;
- time-of-day split when enough samples exist;
- recency.

Do not assign a restaurant penalty from one or two deliveries.

## Delivery-time prediction

Long-term estimate:

`predicted total delivery time = route-to-pickup + expected pickup wait + route-to-dropoff(s) + bounded handoff overhead`

Then:

`predicted effective €/h = offer price / predicted total hours`

Every term must carry provenance. If restaurant history is unavailable, omit that correction rather than inventing it.

## Learning shortcuts

Interesting future behavior: detect repeated user deviations from the stock Valhalla route.

If the courier repeatedly takes a different legal/routable OSM path and it is consistently faster/shorter, record that evidence for later route scoring.

Do not silently add nonexistent map edges. If a real path is missing from OSM, treat that as a map-data problem and optionally surface it for manual OSM correction/research.

## Privacy

GPS traces are sensitive.

Requirements:

- local-first storage;
- opt-in route learning;
- retention controls and delete-all function;
- no customer names stored with route-learning aggregates;
- remote self-hosted Valhalla receives only coordinates required for routing unless future on-device routing is implemented;
- diagnostics should expose aggregate stats, not raw historical traces by default.

## Validation

For each prediction generation, store enough local metadata to compare later:

- platform ETA if available;
- baseline Valhalla ETA;
- personalized predicted ETA;
- actual observed travel/completion time;
- prediction error.

Track whether personalization actually improves median absolute error. Do not ship a more complicated model merely because it sounds smarter.

## Future AI/ML

Only after a substantial clean dataset exists, consider models using features such as:

- matched road/path segments;
- time of day/day of week;
- weather if available from a trustworthy source;
- merchant wait history;
- stacked-delivery structure;
- route surface/road class/cycleway metadata.

Any learned model must be evaluated against simple historical baselines. A median-based model that works is preferable to an unvalidated neural model.
