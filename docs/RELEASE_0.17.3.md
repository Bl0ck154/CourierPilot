# CourierPilot 0.17.3 — card comes back after reopening Wolt, smoother proof screenshots

Android version: **0.17.3** · versionCode **142**

## Fixes

- **Swiped card returns when you reopen the courier app.** After swiping the card away, leaving
  Wolt/Bolt (home screen, another app, or closing it) and coming back now ends the dismissal: the
  card shows again and stays. Before, one path showed the card and the dismissal check hid it
  again a moment later. The notification shade and screenshots do not count as leaving.
- **No streaks on proof screenshots.** 0.17.2 blended the area under CourierPilot's own card
  from the neighbouring pixels, but a road, pin or notification banner touching the edge was
  dragged across as vertical streaks. The edge colours are now heavily smoothed, giving a soft
  patch instead.

## Known behaviour

- On the current Wolt build the collapsed "Multiple drop-offs" card does not always keep the
  customer addresses in Accessibility. Then CourierPilot still opens the sheet once to read them
  (and closes it); this is the designed fallback.

## Manual acceptance checklist — real device

- [ ] Swipe the card away, close and reopen Wolt on the same offer: the card returns and stays.
- [ ] Swipe the card away and stay in Wolt: the card stays hidden.
- [ ] Proof screenshot: the card area is a soft blur, no white block or streaks.
