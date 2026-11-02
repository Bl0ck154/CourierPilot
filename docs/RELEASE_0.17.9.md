# CourierPilot 0.17.9

## Privacy and onboarding

- Prevent backup of private app-data directories during Android cloud backup and device-to-device extraction.
- Publish a clear guide for Android 11+ installation and feature limitations.
- Describe existing sensitive permissions, including optional background location for arrival reminders and the install-from-source capability for GitHub APK updates.
- Publish compatibility, market-analysis and privacy documentation, plus a preliminary landing page.

## Important

- This release does **not** change the protected Valhalla endpoint or offer public routing tokens.
- Offer capture/history remain available without setting up routing. Route-aware estimates still require the private HTTPS endpoint and token.
- Previously stored local history and door codes are not removed by the update. Disabling Android backup also means Android device transfer and cloud restoration of private app data are unavailable.
- Real-device field validation of Wolt/Bolt layouts remains ongoing; no universal compatibility claim is made.

[Install](GETTING_STARTED.md) · [Privacy](PRIVACY_AND_PERMISSIONS.md) · [Market analysis](MARKET_ANALYSIS.md)
