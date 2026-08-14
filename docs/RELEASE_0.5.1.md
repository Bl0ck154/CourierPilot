# CourierPilot 0.5.1

0.5.1 is a small branding and public-repository polish release built on top of the 0.5.0 reliability/offer-details work.

## App branding

- Adds the first dedicated CourierPilot adaptive launcher icon.
- Keeps the canonical package name `com.block154.courierpilot`.
- Keeps the permanent CourierPilot release signing identity.

## Public repository

- Rebuilt README around the current 0.5 behavior instead of the old prototype description.
- Added repository artwork, accurate feature/privacy/build documentation and an architecture diagram.
- Added privacy-safe bug and feature request templates.
- Added contribution guidance.
- Added secret-free pull-request/main CI that runs unit tests and builds the debug APK.

## Functional behavior

Capture rules, database schema, reliability logic, offer parsing and statistics are unchanged from 0.5.0.

The core rule remains: **an offer is not finalized until a plausible non-zero price is present.**

## Version

- `versionCode`: 6
- `versionName`: 0.5.1
