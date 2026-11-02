# CourierPilot 0.17.12

- Add **local-only personal platform-distance offer trends** in Pay: Wolt/Bolt platform-displayed kilometres and payout, grouped by day, week or month, last 90 days.
- Preserve clear boundaries: these provisional observations never contribute to route-verified city scores, sharing queues or route-verdict emojis. Exclude Wolt incremental add-ons from full-offer rates.
- Respect native currency and zero-decimal currencies; omit missing/zero distance, zero payout, and expired entries.
- Document realistic public Valhalla rollout and bounded routing alternatives, without enabling third-party data transfer or publicising the private routing token.

The verified route analysis is unchanged. There is no new routing backend dependency and no auto-accept.
