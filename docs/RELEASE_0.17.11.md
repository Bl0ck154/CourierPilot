# CourierPilot 0.17.11

## Faster Android setup

- The Home screen now links directly to the Android settings screens for whichever capture permissions are missing. Each shortcut disappears when its permission is enabled.
- Explain on the Home screen that there is no CourierPilot account or routing-service setup for ordinary offer capture.
- Clarify in Settings that Wolt/Bolt advanced routes are optional, keeping route switches disabled if a private endpoint has not been provisioned.

## Document the actual basic mode

- Capture and offer history work without a server.
- When an offer includes both a payout and a platform-reported distance, the overlay shows a **provisional** payout/km estimate without assigning a route-verified rating.
- A working optional protected Valhalla service is required for walking/cycling route verification and for market analysis based on those verified routes.
- The README, website and quick start now explain both modes separately.

No background permissions are silently requested. No automatic acceptance or decline is added. No shared Valhalla token or anonymous route access is introduced.

[Get started](GETTING_STARTED.md) · [Privacy](PRIVACY_AND_PERMISSIONS.md) · [Market methodology](MARKET_ANALYSIS.md)
