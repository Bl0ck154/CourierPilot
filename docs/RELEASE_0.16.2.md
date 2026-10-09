# CourierPilot 0.16.2 — venue names, new Wolt customer screen, door-code reminders

Android version: **0.16.2** · versionCode **138**

## Fixes

- **Full Bolt venue names.** Long venue names wrap onto two lines in Bolt's offer card
  (e.g. "Eat More Chinese & Shimai" / "Sushi (Palangos str.)"); only the second line was kept.
  The wrapped title is now joined when the second line carries Bolt's "(Street str.)" branch
  suffix. Map buttons and district labels are never glued on. History re-parses stored text, so
  past offers show the full name too.
- **No stray letters before Bolt pickup addresses.** The fork-and-knife pin read by OCR as
  `Y` or `W4` ("Y Vokiečių g. 12", "W4 Antakalnio gatvė 71") is stripped. Dotted initials such as
  "V. Kudirkos g." are left alone.
- **New Wolt customer screen supported.** Wolt replaced the `Dropoff to` sheet with a `Deliver to`
  sheet (Call / Message, address + city line, Suite/Floor, Customer notes, Meet in person,
  Navigate). Address, customer name, floor, notes and handover method are read again, the address
  is saved to Addresses, and the screen counts as an active task.
- **Door-code arrival reminders work again for Wolt.** The reminder is armed from the customer
  screen; with the redesign the screen was rejected, so no reminder was ever armed.

## Manual acceptance checklist — real device

- [ ] A Bolt offer from a venue with a long name shows the whole name in History.
- [ ] Bolt pickup addresses no longer start with `Y` / `W4`.
- [ ] Open a Wolt customer screen: the address appears in Addresses with floor/notes.
- [ ] For an address with a saved door code, the reminder arrives on approach (or by the ETA
      fallback when background location is off).
