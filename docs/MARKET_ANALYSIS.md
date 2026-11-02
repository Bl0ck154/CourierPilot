# CourierPilot market intelligence

**Goal:** help couriers be selective rather than maximize the number of orders. A fixed rule like "accept every offer above €5" is insufficient: a €5 job might add 1 km or 15 km, and the job may consume very different amounts of time.

## What the app actually measures

- A captured offer's payout, currency and calculated route distance (when verifiable).
- Offer payout **per routed kilometre**, subject to route quality and the presenter's provenance/estimate labels.
- Personal recent distribution and confidence-aware scoring. The app uses a 30-day observation window, 10-day recency half-life and robust weighted quantiles. A minimum of five eligible personal samples is required for a stable local baseline; confidence increases with sample count.
- City comparison when aggregated market data passes the backend's eligibility checks. A city comparison is **not** a guarantee of better orders for any single courier.
- Day/week/month histories, quantiles and median trend when observations are available.

Local offer-history capture works without a route endpoint. **Verified route €/km observations require a working protected Valhalla route**; an incomplete route must not be presented as verified.

## What it does not yet know

- Market offer data is observational and may have selection bias: active users, apps, places, times and available offers are not a random sample.
- Offer €/km is **not net €/hour**. It does not by itself subtract waiting, taxes, battery/vehicle maintenance or unpaid return kilometres.
- A high headline payout cannot compensate for a costly route automatically. When distances are unknown the app shows uncertainty, not a fabricated profitable rate.
- It does not accept/decline orders for you, and does not claim to predict future payouts.

## Roadmap — measured economics, not automation

1. **Reliability and field evidence first.** Maintain redacted real-device Wolt/Bolt parsing fixtures, accurate currencies and correct stop order.
2. **Expose offer composition.** Separate travel to pickup, delivery route and incremental add-on distance wherever source data supports it; avoid double-counting.
3. **Net €/hour after sufficient evidence.** Combine personally observed travel times, restaurant waits and controllable expenses only after explicit sample and validity gates.
4. **Personal trends.** Show pay distribution over time by platform and market, with confidence, time window and sample counts always visible.
5. **Privacy-protected market.** Aggregated comparisons only for eligible groups and opt-in upload; never publish a courier's exact route, address, raw OCR or access code.

**Product principle:** fewer, more economically sensible orders over blindly accepting anything above a fixed price. No auto-accept functionality is required for this value proposition.

Relevant implementation: `AdaptiveMarketScoring.kt`, `MarketIntelligence.kt`, `MarketScreen.kt`, `OfferDecision.kt`, `MarketRoutePersistencePolicy.kt`.
