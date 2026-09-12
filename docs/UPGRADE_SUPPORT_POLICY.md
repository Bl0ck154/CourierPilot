# CourierPilot upgrade support policy

## Current rule

CourierPilot does **not** currently declare a minimum directly-upgradable historical version. Therefore one-shot `AddressDataRepair` and `OfferDataRepair` revisions are compatibility code, not dead code, and must stay in the app.

Do not remove an old repair merely because every current development install has already executed it.

Current regression coverage also force-runs the latest address and offer repairs a second time over
their own repaired output. That proves current repair idempotency under revision-marker replay, but it
**does not** define a minimum supported upgrade version or prove that an arbitrarily old schema can
upgrade directly to current. Those remain separate product-support and baseline-fixture decisions.

## When an old repair may be retired

A repair can be deleted only when all of the following are true:

1. The project explicitly declares a minimum supported source version for in-place upgrades.
2. The repair only targets versions older than that minimum.
3. Upgrade/replay tests cover the declared minimum version through the current schema/parser state.
4. No currently supported data format depends on the repair as a canonicalization or dedupe safety net.
5. The removal is shipped as its own behavior-neutral cleanup PR so rollback is straightforward.

Until those conditions are met, preserving a small idempotent migration is cheaper and safer than risking corrupted offer/address history on an older installed APK.

## Parser compatibility is separate

This policy does not authorize removing Wolt/Bolt parser branches marked `legacy`. Courier apps may expose older and newer UI variants concurrently through staged or A/B rollouts. Parser compatibility requires replay/production evidence of retirement, independently of database upgrade support.
