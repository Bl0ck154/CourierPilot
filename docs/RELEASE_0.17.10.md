# CourierPilot 0.17.10

## Market comparison clarity

- Show the right native money-per-kilometre unit in the Pay screen for non-EUR markets.
- Explicitly label a published trend as the **city trend**, rather than implying it describes the courier's own offer history.
- Hide city medians/trends when the city profile has not passed its readiness gate; distinguish personal and city sample counts in the summary.
- Add regression checks for money units and sample-source labels.

## Wolt capture reliability

- Recognize a legacy Wolt earnings caption split across adjacent OCR lines, including the trusted-offer classification path; regression test included (incorporated from PR #173).

## Repository and developer experience

- Prevent redundant expensive signed release builds when a release with the same app version already exists. Normal Android CI still runs after source changes.
- Publish a privacy-preserving Android field-testing template and a contribution checklist.
- Expand factual website FAQ and search metadata.

## Unchanged

- No auto-accept or auto-decline.
- No public Valhalla token and no new external data-sharing behavior.
- Route-based rate analysis still needs a configured protected routing endpoint. Observational offer €/km is not a guaranteed net income per hour.

[Installation](GETTING_STARTED.md) · [Market methodology](MARKET_ANALYSIS.md) · [Privacy](PRIVACY_AND_PERMISSIONS.md)
