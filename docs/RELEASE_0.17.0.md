# CourierPilot 0.17.0 — new look, light & dark theme, every venue of stacked Wolt orders

Android version: **0.17.0** · versionCode **139**

## New design

- **Home, History and Addresses redesigned** as grouped lists (design D). Each offer row shows a
  platform badge, the venue, price · time · distance, and the €/km coloured like the live card:
  gold 🔥 for the best, fading to grey 👎, and a readable muted clay 💩 for the worst (no longer
  near-transparent).
- **Home** shows today's average €/km in a big graded number, offers, offers per hour and the best
  offer of the day, plus an online pill (platform · online time) and the settings button.
- **History** groups offers by day ("Today · 6 offers · avg €1.08/km") and adds All / Wolt / Bolt
  filters next to search. Venue names can use two lines.
- **Addresses** is one list: address, customer · platform · visits, a 🔑 door-code chip and a map
  button. Deleting an address is in the address details screen.
- **Theme: System / Light / Dark** in Settings → Appearance. It applies immediately to every screen,
  including status-bar icons. The live card over Wolt/Bolt always stays dark.
- History €/km colours use the live card's EUR baseline bands (≥ €1.25 fire, > €1.00 good,
  €0.85–1.00 ok, €0.70–0.85 bad, < €0.70 terrible). Other currencies stay neutral.

## Fixes

- **Stacked Wolt offers keep every venue.** With two venues where only one has a branch suffix
  (`Ponas Mėsainis (Kauno g.)` + `The Urban Garden`), History kept only the first. Now all venues
  are kept in pickup order and lists show them joined (`A + B`). Old rows are repaired on read.
- **"Venue unknown" recovery** now finds one title per pickup in the stored screen text instead
  of only the first.

## Manual acceptance checklist — real device

- [ ] Settings → Appearance: Light / Dark / System switch instantly; status-bar icons stay visible.
- [ ] With System, toggling the phone's dark mode re-themes the app.
- [ ] History rows: €/km colours match the live card verdict; the worst offers are readable.
- [ ] A double-venue Wolt offer shows both venues in History and details.
- [ ] Wolt / Bolt filter chips filter History; search still works with a filter.
- [ ] Small phones: bottom bar labels fit, rows do not overlap.
