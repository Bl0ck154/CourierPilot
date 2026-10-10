# CourierPilot 0.17.5 — every screen in the new design

Android version: **0.17.5** · versionCode **144**

## Redesign (design D everywhere)

- **Offer details**: venue summary, big price with the graded €/km and verdict emoji, fact tiles
  (route, deliveries, ETA), numbered P1/P2 pickups and D1/D2 drop-offs as one list with map buttons
  and a "Saved address" chip, proof screenshot and captured text as list rows.
- **Address details**: address block with visits / customers / codes tiles and an "Open in maps"
  button, 🔑 access codes, grouped customers with "Show all", and "Delete address" as a red row
  at the bottom.
- **Reliability Center** and **App updates**: grouped sections, status rows, metric tiles; the
  Settings update card shares the same panel.
- **Developer tools**, **Route research**, **Ride trace** and **Storage**: the last three were
  still old Android widgets without the app theme; all four are now Compose in design D, light
  and dark, with the same functions.
- No white strip under the 3-button navigation bar; text outside cards is readable in dark mode.

## Fixes

- **Wrapped Wolt venue titles**: "Eat More Chinese & Shimai Sushi (Palangos" / "g.)" no longer
  creates a fake venue "g.)". Saved offers repair themselves when opened.
- **Duplicate pickups from OCR**: `LTO1117` is read as `LT01117`, so the same pickup no longer
  appears twice.

## Manual acceptance checklist — real device

- [ ] Open an offer and an address from History/Addresses in light and dark theme.
- [ ] Settings → Reliability Center and App updates; storage and ride-trace launcher shortcuts.
- [ ] A Wolt offer with a long two-line venue name shows one venue, not "g.)".
