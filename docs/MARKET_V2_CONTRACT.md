# Market Scoring v2 Frozen Contracts

This note is the Wave 0 cross-workstream contract for the Market Scoring v2 implementation.

## Money

`MoneyAmount` stores `amountMinor: Long`, `currencyCode: String` (ISO-4217), and `fractionDigits: Int`. Market rates are native-currency major units per independently calculated full Valhalla route kilometre. Samples are never mixed across currencies; unknown or ambiguous currency is retained only for history and is ineligible for scoring/upload.

## Cohort and eligibility

The exact cohort key is `cityKey + currencyCode + platform` (Wolt and Bolt remain isolated). An eligible observation has a captured Wolt/Bolt offer, known price/currency/city, a positive structurally valid FULL independent Valhalla route, is not Bolt `PICKUP_ONLY`, and is not a duplicate captured offer. All eligible presented offers are retained, and a candidate is scored before insertion into its reference profile.

## Scoring

Live scoring uses only the rolling 30 days with `recencyWeight = 0.5 ^ (ageDays / 10)` and weighted effective sample size. Bands are percentile positions: `<P15 TERRIBLE`, `P15..P35 BAD`, `P35..P65 OK`, `P65..P85 GOOD`, `>=P85 FIRE`. With no usable profile and fewer than five personal eligible samples, verdict is `LEARNING`; personal confidence starts `LOW` at five samples, `MEDIUM` at 10, and `HIGH` at 25. Personal influence blends smoothly as `nEffective / (nEffective + priorStrength)`. No universal money/km fallback or absolute edge spacing is permitted.

## Server v2 API

Upload fields: `schema`, pseudonymous `installId`, `offerId`, `capturedAt`, city identity, `platform`, `currencyCode`, `priceMinor`, `currencyFractionDigits`, `fullRouteDistanceM`, `routeSource`, `deliveryCount`, local hour/weekday, app version/version code. Never send names, addresses, OCR/raw text, screenshots, or exact GPS. Profile requests use exact city/currency/platform and responses expose sample count, effective sample count, unique installations, native median and P15/P35/P65/P85 (+P25/P75 for analytics), confidence, trend, window, generated timestamp; insufficient cohorts return `ready=false` with no monetary defaults. History is day/week/month aggregate only.

## Client repository interfaces

The client sync/repository layer must provide explicit-currency upload queueing and exact-cohort profile/history reads, including readiness, confidence, trend, and source metadata. UI consumes presentation state/fakes and never accesses HTTP or SQLite directly. The orchestrator owns final wiring into `MarketIntelligence.kt`, `OfferDecision.kt`, `StableLiveOfferAdvisor.kt`, dashboard navigation, and shared Gradle/manifest/resources.
