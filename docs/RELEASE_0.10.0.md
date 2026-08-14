# CourierPilot 0.10.0

`versionCode 16`

0.10 introduces the first post-capture live-advisor workflow while preserving the existing clean priced-offer capture boundary.

## Added

- temporary Accessibility live-advisor card after a successful offer save;
- transparent platform €/km and platform-ETA-derived €/h range;
- optional voice summary;
- explicit `Wolt route ON/OFF` control, off by default;
- experimental Wolt `current GPS → captured Timeline stops → geocode → Valhalla` flow;
- preservation of sequential Wolt Timeline stop order for stacked routes;
- pedestrian-shortcut and cycleway-biased candidates shown separately with no automatic winner;
- isolated live-route run/waypoint/candidate provenance in `route_research.db` v2;
- certain `OFFER_CAPTURED` timeline event and conservative explicit monotonic delivery-state evidence;
- tests for advisor arithmetic, lifecycle progression and ordered stacked stop parsing.

## Capture safety

The clean proof screenshot and `courier_offers.db` insert happen before any advisor, geocoder, GPS or Valhalla work. Post-capture failures cannot roll back an archived offer.

## Still experimental

- generic Valhalla duration is not personalized scooter ETA;
- automatic Wolt routing requires explicit opt-in and a configured protected endpoint;
- one failed Wolt stop geocode fails the entire calculated route rather than silently omitting the stop;
- Bolt coordinate recovery still requires real private samples;
- delivery lifecycle cue coverage is intentionally conservative/incomplete;
- continuous GPS traces, map matching, venue-wait prediction and personal segment ETA remain later phases.
