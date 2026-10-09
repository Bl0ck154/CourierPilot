# CourierPilot 0.16.1 — Live card redesign and Bolt hotfix

Android version: **0.16.1** · versionCode **137**

## What's new

- **Redesigned live card (variant E).** The €/km number comes first and is never cut off any more
  (0.16.0 could show `€1.4…`). Walking/cycling distances sit on the right as two short lines.
  Colour follows the verdict: the best offers glow in saturated gold, worse ones fade towards grey
  until a terrible offer is barely visible; the verdict emoji fades the same way. `≈` now marks
  estimates only (ETA/platform-distance fallbacks), not route-verified rates. The app version moved
  from the route line into the developer debug lines.
- **Bolt: no more "courier dot on a shop icon".** Mapbox shop/station POI icons share the blue of
  the courier dot. A real offer anchored the map on a POI next to the customer, turning a ~3 km
  delivery into 0.55 km and €5.8/km 🔥. The map is now always positioned from the pickup pin matched
  to its geocoded address; the courier dot only provides scale when it is solid (no white glyph),
  strong and consistent with Bolt's customer-leg ETA. Otherwise the ETA sets the scale.
- **Bolt: ETA sanity gate.** A full route that is incompatible with Bolt's own total minutes
  (outside 0.25–3× the learned speed) is no longer shown as a verdict. The card shows the ETA
  estimate `≈ €X/km ⏳` instead, and the rejected route is not stored as a successful history route.
- **Bolt: card no longer restarts while the offer rings.** Bolt re-posts a ringing offer under new
  notification keys and its first re-capture frame is often sparse. Such frames restarted the card
  at "Route…" and recomputed the same number. A re-capture that contradicts nothing now stays in the
  same card; a different price or merchant still replaces it. Re-posted notifications are also
  coalesced when the same Bolt card (merchant/pickup + Decline) is still visible.

## Manual acceptance checklist — real device

- [ ] Bolt single offer where you stand at the restaurant: distance is in the same range as Bolt's
      minutes (or an `≈ … ⏳` estimate), never a few hundred metres for a 15+ minute offer.
- [ ] Watch a ringing Bolt offer for 30 s: the card does not return to the spinner or blink.
- [ ] Card shows the full number, e.g. `€1.43/km 👍`, with distances on the right.
- [ ] 🔥 offers are bright gold; 👎/💩 offers are grey and faint.
- [ ] Wolt single and batch offers: unchanged behaviour.
