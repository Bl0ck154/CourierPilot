# CourierPilot 0.17.2 — Wolt stacked routes without the drop-off click, clean proof screenshots

Android version: **0.17.2** · versionCode **141**

## Fixes

- **Wolt offers with several venues and several customers get a route again.** Wolt keeps the
  collapsed "Multiple drop-offs (N stops)" destinations in the card's Accessibility text as bare
  `street` / `Vilnius, 10300` rows. They were read as extra pickups, and "Vilnius" as a venue
  name, so the live card showed `—/km ⚠️` even after the sheet was opened (the hidden-row
  recovery then excluded the real customer addresses as "pickups"). These rows are now customer
  stops when they complete the announced count exactly, a city line is never a venue, and the
  announced drop-off count reserves its stops so customers can never become pickups.
- **No more tapping "Multiple drop-offs" when Wolt already provides the addresses.** With the
  rows read from the collapsed card the route is complete at once; the sheet is opened only when
  Wolt really hides the addresses.
- **No pale block on proof screenshots.** CourierPilot hides its own card in Wolt full-screen
  captures. The area was filled with one flat colour (near-white over a light map); it is now
  blended from the surrounding pixels on all four sides.
- Debug line: `LT-01130` postcodes are removed completely (no stray `LT-`).

## Manual acceptance checklist — real device

- [ ] Wolt offer with 2 venues + "Multiple drop-offs (2 stops)": €/km appears without the sheet
      opening; debug lines show 🍴 two venues and 👤 two customers.
- [ ] Proof screenshot in History shows no white rectangle where the card was.
