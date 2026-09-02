# Global Adaptive Market Scoring Plan

Status: design plan for the post-0.15.15 market model, audited against CourierPilot `main` at `ed6ab3a`. This document intentionally separates design from implementation.

## Product goal

CourierPilot should rate a newly offered job relative to what the local courier market is paying **now**, not against a hard-coded amount such as `€1/km`.

The same scoring logic must work in Vilnius, Warsaw, Stockholm, London or any other supported city without changing numeric thresholds for each currency.

The live verdict remains based on money per independently calculated full Valhalla route kilometre. It is a **relative market-quality rating**, not a promise that an order is personally profitable. Personal route time, venue wait and operating costs remain separate signals.

## Audit of the current PR #48 model

PR #48 was a useful first local-first step, but it is not the final global model.

Current behavior still has several currency- and market-specific assumptions:

- `OfferDecisionThresholds.DEFAULT = 0.70 / 0.85 / 1.00 / 1.25` remains the final fallback.
- `LocalMarketScoring` only becomes available at 6 samples; before that a city profile or the fixed EUR fallback is used.
- local history is a rolling 21-day window, not the requested 30-day live-market window.
- local weighting jumps through fixed buckets (`55%`, `65%`, `75%`, `85%`, `90%`) instead of increasing smoothly with evidence.
- the client and server call the metric `medianEurPerKm` / `eur_per_km`.
- the parser currently recognizes only `€` / `EUR`, and `priceCents` assumes a two-decimal currency.
- client rate validation (`0.15..10.0`), client edge spacing (`+0.03`), server default edges, server rate validation and server edge spacing (`+0.05`) are all absolute currency-unit assumptions.
- when platform-local history is sparse, the current client can fall back to all local platforms. Wolt and Bolt distributions should not be treated as equivalent by default.
- the server keeps recent raw market rows but there is no long-term day/week/month market history model or dedicated market-history UI.

Market Scoring v2 removes those assumptions rather than adding more country-specific exceptions.

## Core invariant: no universal money-per-kilometre threshold

There must be no production rule equivalent to:

```text
< 0.70 = terrible
< 0.85 = bad
<= 1.00 = OK
< 1.25 = good
>= 1.25 = fire
```

Instead, every eligible offer is compared with a recent reference distribution for the same market cohort.

The five live bands are percentile positions, not currency values:

- bottom 15% -> `TERRIBLE`
- 15th–35th percentile -> `BAD`
- 35th–65th percentile -> `OK`
- 65th–85th percentile -> `GOOD`
- top 15% -> `FIRE`

The percentile boundaries are dimensionless, so the same logic works for EUR, PLN, SEK, GBP, USD, JPY, HUF and other currencies.

If the whole local market moves up or down, the native-currency values represented by those percentiles move with it automatically.

## Money model and currency support

`priceCents` is not a global money type. Replace market-facing money handling with an explicit model such as:

```text
MoneyAmount
- amountMinor: Long
- currencyCode: ISO-4217 string
- fractionDigits: Int
```

Examples:

- EUR `4.38` -> `438`, `EUR`, `2`
- PLN `18.50` -> `1850`, `PLN`, `2`
- JPY `620` -> `620`, `JPY`, `0`
- KWD `1.250` -> `1250`, `KWD`, `3`

The derived market rate is:

```text
nativeMoneyPerKm = amountMajor / (fullRouteMeters / 1000)
```

No FX conversion is required for scoring because samples are never mixed across currencies.

### Currency detection

1. Parse an explicit ISO code when the courier app exposes one.
2. Parse known currency symbols.
3. Resolve ambiguous symbols such as `$` using the resolved country/city and platform locale context.
4. Use `java.util.Currency` / ISO-4217 fraction digits for storage and formatting.
5. If the currency cannot be resolved confidently, keep the offer history but do not use/upload it as a market-scoring sample.

Existing market rows produced by the EUR-only parser can be migrated as `EUR`, fraction digits `2`.

## Eligible market observation

A sample may influence scoring only when all of the following are true:

- Wolt or Bolt offer was successfully captured;
- price and currency are known;
- city is resolved;
- a **FULL** independent Valhalla route is available;
- route distance is positive and structurally valid;
- the route is not a Bolt `PICKUP_ONLY` result;
- the observation is not a duplicate of the same captured offer.

Record **all eligible presented offers**, not only accepted offers. Using only accepted jobs would create selection bias: a courier who accepts mostly good offers would teach CourierPilot that unusually good pay is normal.

The candidate being rated must be evaluated against the existing reference profile **before** that candidate is inserted into the profile.

## Cohort isolation

The primary cohort key is:

```text
city + currency + platform
```

Examples:

- `lt-vilnius + EUR + Wolt`
- `lt-vilnius + EUR + Bolt`
- `pl-warsaw + PLN + Wolt`

Do not mix currencies. Do not mix Wolt and Bolt into a normal personal profile.

A same-city/same-currency all-platform aggregate may exist on the server for exploration or a deliberately low-confidence bootstrap, but it must not silently masquerade as a platform-specific profile.

Time-of-day / weekday refinement should be added only when the parent cohort has enough data. Sparse subgroups must fall back to the broader same-platform cohort rather than fragmenting the model.

## Live scoring window vs historical retention

These are deliberately different concepts.

### Live scoring

Only the last **30 rolling days** can influence today's rating.

Within those 30 days, newer observations receive somewhat more weight. Initial target:

```text
recencyWeight = 0.5 ^ (ageDays / 10)
```

This gives approximately a 10-day half-life while still allowing the whole month to contribute. Data older than 30 days has exactly zero live-scoring weight.

### Historical analytics

Older data is retained for charts and comparisons but never leaks back into the current live score.

Local raw observations should be retained until the user clears history/app data. The UI can aggregate them into:

- daily buckets for recent detail;
- weekly buckets for medium-term trend;
- monthly buckets for long-term tariff history.

Server raw samples can retain a bounded window (initially 90 days), while privacy-safe daily/weekly/monthly aggregate history is retained much longer (target: at least 24 months).

## Personal learning and bootstrap behavior

There is no fixed-rate fallback.

### No collective city data available

- `0–4` eligible personal samples: `LEARNING`, no invented five-band verdict.
- `5–9`: personal market rating becomes available with `LOW` confidence.
- `10–24`: `MEDIUM` personal confidence.
- `25+`: `HIGH` personal confidence, subject to effective sample age/quality.

The five-sample threshold is the point where personal scoring may begin, not a claim that five samples fully define the market.

### Collective city data already available

A new courier can receive a city-based rating from the first eligible order if the server already has a usable profile for the exact city/currency/platform cohort.

Initial collective confidence targets:

- fewer than 10 eligible cohort samples: `NOT_READY`;
- 10–29 samples: `LOW`;
- 30+ samples from at least 3 installations: `MEDIUM`;
- 100+ samples from at least 8 installations: `HIGH`.

These are evidence gates, not monetary thresholds, and can be tuned from real production data. A low-confidence collective profile may bootstrap a new courier, but the UI must identify it as low confidence.

As personal evidence grows, personal history progressively takes over.

Use a smooth evidence weight instead of fixed sample-count steps. Initial design:

```text
localWeight = nEffective / (nEffective + 8)
```

where `nEffective` is the recency-weighted effective sample size. The exact prior-strength constant is test-tunable, but the behavior must remain monotonic and smooth.

If no server profile exists but at least five personal samples exist, the personal profile can stand alone and its confidence remains visible.

## Robust statistics

Raw observations are retained unchanged. Outlier handling affects only profile calculation.

Requirements:

- use weighted quantiles / percentile ranks rather than a simple mean;
- cap each installation's influence on the collective server profile so one heavy courier cannot define a city;
- use scale-invariant robust outlier treatment (for example median/MAD or percentile winsorization), never currency-specific hard limits such as `0.15..10 EUR/km`;
- do not force percentile edges apart with absolute additions such as `+0.03` or `+0.05` currency units;
- expose sample count and confidence so a thin profile is visibly different from a mature one.

For a candidate rate, the engine should ideally compute/report an approximate percentile as well as the five-band enum, for example:

```text
€0.92/km · 31st percentile · BAD · 18 personal samples
```

The displayed currency symbol/code is locale-aware; the percentile is universal.

## Reference profile hierarchy

For an offer in a known city/currency/platform:

1. personal exact cohort, last 30 days;
2. collective server exact cohort, last 30 days;
3. optional explicitly low-confidence same-city/same-currency cross-platform bootstrap;
4. otherwise `LEARNING`.

There is **no absolute monetary fallback** at the bottom of this chain.

When personal and collective exact profiles both exist, blend evidence/confidence, not fixed EUR defaults.

## Trend model

Live scoring and trend reporting use related data but different comparisons.

Suggested trend summaries:

- `7d vs previous 7d` for a responsive short trend;
- current rolling 30-day median vs previous rolling 30-day median;
- calendar month summaries for historical browsing.

Each bucket should expose at least:

- sample count;
- median native money/km;
- 25th and 75th percentiles;
- median offer price;
- median full-route distance;
- platform;
- currency;
- confidence / unique installations for collective server data.

Future additions can include daypart and weekday breakdowns once enough data exists.

## Dedicated Market screen

Do not hide this feature as a few lines in Settings. Add a dedicated `Market` / `Pay trends` section reachable from the main CourierPilot dashboard.

### Overview

Per platform:

- current local median in native currency/km;
- current collective city median;
- sample counts;
- confidence;
- `7d` trend arrow/percentage;
- current scoring source, e.g. `City`, `Personal + City`, `Personal`, `Learning`.

### History

A chart/list with `Day / Week / Month` switching:

- median money/km line;
- optional P25–P75 range;
- number of observed offers per bucket;
- comparison to the previous equivalent period.

The historical view can show old months even though only the latest 30 days affect today's rating.

### Learning state

When the personal profile is not ready, show useful progress rather than a fake rating, for example:

```text
Learning your Bolt market · 3 / 5 eligible full-route offers
City profile: available / unavailable
```

No user-facing configuration for absolute rating thresholds is required.

## Local database design

Prefer a dedicated derived market-observation table instead of continuing to overload the main `offers` row with every market concern.

Proposed fields:

```text
market_observations
- offer_id (unique)
- captured_at
- city_key
- city_name
- country_code
- platform
- currency_code
- price_minor
- currency_fraction_digits
- full_route_distance_m
- route_source
- delivery_count
- local_hour
- local_weekday
- uploaded_at / sync_state
```

The main offer history remains the canonical captured-offer record. `market_observations` is the clean, eligibility-gated dataset used by market scoring and analytics.

Indexes should support:

- city + currency + platform + captured time;
- captured time for historical buckets;
- upload/sync state.

## Server schema v2

The current server schema is explicitly EUR-based. Introduce a v2 market schema while preserving temporary v1 compatibility during rollout.

### Upload sample

Privacy-minimal fields only:

```text
schema
install_id (pseudonymous CourierPilot id)
offer_id
captured_at
city_key / city_name / country_code
platform
currency_code
price_minor
currency_fraction_digits
full_route_distance_m
route_source
delivery_count
local_hour / local_weekday
app_version / version_code
```

Never upload:

- customer or merchant names;
- addresses;
- OCR/raw offer text;
- screenshots;
- exact GPS coordinates.

### Profile API

Request key includes:

```text
city + currency + platform (+ optional hour/daypart)
```

Response should return market distribution data rather than EUR-specific defaults:

- sample count;
- effective sample count;
- unique installations;
- median native money/km;
- P15/P35/P65/P85 (and P25/P75 for analytics);
- confidence;
- short trend;
- profile time window and generated timestamp.

If the cohort is too thin, return `ready=false`; do not return fake default monetary edges.

### Historical API

Add aggregate history retrieval for day/week/month views. New clients receive aggregate city history, not other couriers' raw rows.

### Collective influence control

Retain the useful PR #48 concept of reducing the influence of installations that contribute many more samples than everyone else. Collective market statistics should represent a city, not the busiest single device.

## Sync semantics

Anonymous sharing remains opt-in.

- Local personalization works with sharing disabled.
- Collective city profiles can be downloaded even when the user does not share.
- When sharing is enabled, eligible market observations are queued and retried safely.
- The server is a **collective aggregate**, not a personal cloud backup/account system.
- Reinstall/cross-device personal-history sync is a separate future feature if ever needed.

## Migration and compatibility

1. Add explicit currency fields without destroying existing offer history.
2. Backfill existing PR #48 market observations as EUR where they came from the existing EUR-only parser.
3. Keep reading v1 server profiles during a short transition, but never use their hard-coded EUR defaults for v2 scoring.
4. Server accepts old v1 uploads long enough for 0.15.x clients to age out.
5. New client writes v2 samples and prefers v2 profiles/history.
6. Remove old `medianEurPerKm`, `eur_per_km`, `priceCents` market semantics and `OfferDecisionThresholds.DEFAULT` from the production scoring path once migration is complete.

## Implementation phases

### Phase 1 — money + data model

- introduce `MoneyAmount` / ISO-4217 currency parsing;
- migrate local DB to explicit currency-aware market observations;
- keep existing EUR captures backward compatible;
- add parser tests for representative currencies and fraction digits.

### Phase 2 — adaptive scoring engine v2

- replace absolute `OfferDecisionThresholds.DEFAULT` fallback with `LEARNING`;
- change active window from 21 to 30 days;
- add recency weighting and effective sample size;
- start personal model at 5 samples;
- map the candidate to market percentile/five-band rating;
- remove absolute rate filters and absolute edge spacing;
- score before inserting the current sample;
- keep Wolt/Bolt cohorts isolated.

### Phase 3 — server market schema v2

In `Bl0ck154/wolt-discount-monitor`:

- add currency-aware columns/API payload;
- migrate existing EUR rows;
- remove server `DEFAULT_EDGES` from v2;
- compute 30-day weighted profiles;
- keep per-install influence control;
- add long-lived aggregate history tables/API;
- retain temporary v1 compatibility.

### Phase 4 — client collective bootstrap + sync

- fetch exact city/currency/platform profile;
- new courier can score from collective city data immediately when ready;
- smoothly blend personal and collective evidence as local history grows;
- show source and confidence in diagnostics/UI;
- keep sharing optional and privacy-minimal.

### Phase 5 — dedicated Market / Pay trends UI

- dashboard entry;
- platform selector;
- current personal vs city medians;
- sample/confidence/source;
- 7-day trend;
- day/week/month history;
- learning progress state.

### Phase 6 — rollout and evidence gates

- ship with diagnostics for profile source, eligible-sample count, effective sample count, currency and percentile **without** addresses/GPS;
- compare live ratings against known Vilnius examples;
- verify a non-EUR synthetic/test market produces identical percentile behavior after currency-scale changes;
- verify old observations are visible in history but excluded from the 30-day live profile;
- only then remove remaining legacy v1/fixed-threshold code.

## Parallel implementation layout

The implementation should be run as an orchestrated multi-agent job, not as several agents editing the same branch. The goal is to maximize parallel work while keeping merge conflicts and hidden contract drift low.

### Wave 0 — orchestrator contract freeze

Before spawning implementation agents, the orchestrator creates one short-lived integration branch from current `main` and freezes these cross-cutting contracts in either a small contract commit or an implementation note attached to the work:

- `MoneyAmount` semantics: `amountMinor`, `currencyCode`, `fractionDigits`;
- market cohort key: `city + currency + platform`;
- eligible observation fields and FULL-route requirement;
- percentile bands: P15/P35/P65/P85;
- `LEARNING` semantics and confidence vocabulary;
- 30-day live window and historical-retention separation;
- server v2 request/response field names;
- client-facing profile/history repository interfaces used by UI and sync.

Agents may implement behind these contracts, but must not independently rename or reinterpret them. Any required contract change is reported to the orchestrator instead of being silently changed in one branch.

### Shared-hotspot rule

The following existing files are integration hotspots and should be edited only by the orchestrator unless a task below explicitly assigns ownership:

- `OfferDecision.kt`;
- `MarketIntelligence.kt`;
- `StableLiveOfferAdvisor.kt`;
- `CourierPilotDashboardActivity.kt`;
- `app/build.gradle.kts`;
- Android manifest/resources that are shared by multiple features.

Sub-agents should prefer new focused files and tests. The orchestrator performs the final wiring into these hotspots after merging each workstream.

### Agent A — Android money, parsing and local storage

Repository: `Bl0ck154/CourierPilot`.

Owns:

- new currency-aware money types/utilities;
- Wolt/Bolt price parsing for ISO codes/symbols/fraction digits;
- dedicated `market_observations` local table and DB migration;
- migration/backfill of existing EUR market data where provenance makes that safe;
- local queries for exact `city + currency + platform` cohorts and historical day/week/month buckets;
- dedicated parser/storage/migration tests.

Must not:

- implement rating bands;
- implement server networking;
- edit live overlay/UI integration hotspots;
- mix Wolt/Bolt cohorts.

Expected handoff: stable local data interfaces plus tests, with no live scoring behavior change by itself.

### Agent B — pure adaptive scoring engine

Repository: `Bl0ck154/CourierPilot`.

Owns new pure Kotlin scoring/statistics files and their tests only.

Implements:

- 30-day eligibility window;
- recency weighting and effective sample size;
- robust scale-invariant outlier handling;
- weighted percentile/percentile-rank calculations;
- P15/P35/P65/P85 five-band mapping;
- `LEARNING` / confidence behavior beginning at five personal samples;
- smooth personal evidence weighting such as `nEffective / (nEffective + priorStrength)`;
- candidate-before-insert semantics as a pure API contract;
- currency-scale invariance tests and outlier/trend regression tests.

Must not:

- read SQLite directly;
- perform HTTP;
- edit parser, dashboard or overlay files;
- contain absolute currency-unit fallback thresholds.

Expected handoff: a deterministic engine that consumes currency-agnostic normalized observations/profile inputs and returns rating, percentile, confidence and source metadata.

### Agent C — server market schema/API v2

Repository: `Bl0ck154/wolt-discount-monitor`.

This workstream is independent from Android file ownership and can start immediately after Wave 0 contracts are frozen.

Owns:

- v2 currency-aware ingest schema;
- safe EUR migration/compatibility for v1 rows/uploads;
- exact city/currency/platform cohort profiles;
- 30-day weighted collective profiles with per-install influence control;
- `ready=false` for insufficient evidence with no fake default monetary edges;
- long-lived privacy-safe day/week/month aggregate history;
- profile/history endpoints and server tests;
- retention/cleanup behavior for raw versus aggregate rows.

Must preserve privacy boundaries: no addresses, names, OCR, screenshots or exact GPS.

Expected handoff: documented v2 API examples, migration notes and green server tests.

### Agent D — Android server sync and collective repository

Repository: `Bl0ck154/CourierPilot`.

Owns new networking/DTO/repository files for market v2 and dedicated tests.

Implements:

- v2 upload DTOs using explicit currency;
- exact cohort profile fetch;
- day/week/month history fetch;
- queue/retry/dedup behavior for eligible observations;
- v1 compatibility reader only where required during rollout;
- local cache of collective profiles/history;
- privacy tests/serialization tests ensuring forbidden fields cannot enter payloads.

Must not:

- decide rating bands;
- edit `MarketIntelligence.kt` directly unless the orchestrator explicitly delegates it;
- own dashboard UI.

Expected handoff: a repository/service API that the orchestrator can connect to Agent B's scoring engine and Agent E's UI.

### Agent E — Market / Pay trends UI

Repository: `Bl0ck154/CourierPilot`.

Owns new Compose screen/components, presentation models and UI tests/previews.

Build against the frozen repository/view-state interfaces, using fakes if the backend implementations are not merged yet.

Implements:

- dedicated Market / Pay trends screen;
- Wolt/Bolt selector;
- current personal vs city median in native currency;
- percentile/rating source/confidence/sample count;
- `Learning X / 5` state;
- 7-day trend;
- day/week/month history with P25–P75 and sample counts;
- empty/loading/offline states.

Must not:

- implement statistics;
- perform HTTP/SQLite access directly;
- edit the main dashboard entry point. The orchestrator adds the final navigation hook.

Expected handoff: a screen driven entirely by presentation state with no market-business logic embedded in Compose.

### Agent F — independent regression/compatibility suite

Repository: primarily `Bl0ck154/CourierPilot`; server contract fixtures may also be added in `Bl0ck154/wolt-discount-monitor` if the orchestrator gives a separate worktree.

Owns uniquely named test/fixture files; does not edit production logic.

Covers the required regression matrix below, especially:

- EUR vs PLN scale invariance;
- 0- and 3-fraction-digit currencies;
- 0–4 samples -> `LEARNING`;
- fifth sample activation;
- 30-day exclusion with historical retention;
- Wolt/Bolt isolation;
- candidate-before-insert;
- Bolt `PICKUP_ONLY` exclusion;
- one-install collective domination resistance;
- v1/v2 migration fixtures;
- no forbidden private fields in v2 payloads.

This agent should report failures as integration evidence rather than modifying production code to make tests pass.

### Orchestrator — integration and final ownership

The orchestrator is the only owner of cross-workstream wiring and release decisions.

Recommended merge order:

1. rebase/check all workstreams against current `main`;
2. merge Agent A local money/data model;
3. merge Agent B pure scoring engine;
4. merge Agent C server v2, deploy it and verify real endpoints before client activation;
5. merge Agent D client sync/repository;
6. wire A+B+D into `MarketIntelligence.kt` / `OfferDecision.kt` / `StableLiveOfferAdvisor.kt`;
7. merge Agent E UI and add dashboard navigation;
8. merge Agent F regression suite and resolve any failures without weakening tests;
9. remove the old fixed-threshold production path only after the new path passes all gates;
10. run full Android tests/build plus server tests, inspect diffs for contract drift/privacy regressions, then release.

The orchestrator must explicitly verify that there is no remaining production path where missing evidence falls back to `0.70/0.85/1.00/1.25` or any other universal money/km constants.

### Branch/worktree convention

Use one isolated branch/worktree per workstream. Suggested names:

```text
feat/market-v2-money-storage
feat/market-v2-scoring-engine
feat/market-v2-server
feat/market-v2-client-sync
feat/market-v2-ui
test/market-v2-regression
integration/market-v2
```

Agents commit only to their own branch. They do not merge each other, force-push other branches, or resolve conflicts in files owned by another workstream. The orchestrator cherry-picks/merges reviewed commits into `integration/market-v2`, resolves shared-hotspot wiring, and only then opens the final implementation PR(s).

### Parallelism dependency graph

```text
                 Wave 0 contract freeze
                         |
        +----------------+------------------+
        |                |                  |
        v                v                  v
   Agent A          Agent B             Agent C
 money/storage      scoring             server v2
        |                |                  |
        +-------+--------+                  |
                |                           |
                v                           v
              Agent D <---------------- server contract
            client sync
                |
                +-------------+
                              |
                              v
                           Agent E
                              UI

Agent F regression work runs alongside A-E from frozen fixtures/contracts.
The orchestrator integrates only when upstream contracts/tests for each edge are satisfied.
```

Agent E can begin immediately with fake presentation-state fixtures; it does not need to wait for Agent D to finish. Agent D can begin DTO/cache/queue work from the frozen v2 contract while Agent C implements the real server. This keeps the workstreams parallel without making them edit the same production files.

## Required regression tests

At minimum:

1. `0–4` personal samples + no city profile -> `LEARNING`, never fixed thresholds.
2. fifth eligible sample enables a low-confidence personal profile.
3. city profile can score a brand-new install before five personal samples.
4. increasing personal evidence monotonically increases personal influence.
5. an extreme one-off offer does not redefine the profile.
6. sustained market movement does move the profile.
7. samples older than 30 days do not affect live rating.
8. those same old samples remain visible in month/history analytics.
9. Wolt samples do not silently define Bolt thresholds.
10. EUR and PLN datasets with identical relative distributions produce identical percentile bands.
11. currencies with 0 and 3 fraction digits parse/store correctly.
12. unknown/ambiguous currency is excluded from scoring/upload rather than guessed.
13. Bolt `PICKUP_ONLY` never becomes a market sample.
14. candidate offer is scored before it is added to its own reference distribution.
15. accepted-only selection does not drive the market corpus; all eligible presented offers are retained.
16. server profile with insufficient evidence returns `ready=false` and no fake monetary defaults.
17. collective profile cannot be dominated by one installation with a very high offer count.

## Definition of done

Market Scoring v2 is complete when:

- no live rating path contains a universal currency amount per kilometre;
- a user with no market evidence sees `LEARNING`, not a guessed rating;
- a mature city profile can bootstrap a new courier;
- five or more personal samples begin personal adaptation and that influence grows smoothly;
- only the latest 30 days affect live scoring;
- older data remains available in historical day/week/month views;
- server/client schemas carry explicit currency and never mix currency cohorts;
- Wolt/Bolt profiles remain distinguishable;
- market sharing remains privacy-minimal and optional;
- the dedicated Market screen explains current median, trend, sample count, confidence and scoring source.
