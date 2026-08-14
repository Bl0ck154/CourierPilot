# CourierPilot Route Research — real phone testing

This is the practical test workflow for CourierPilot 0.9 route intelligence. The research tools are deliberately separate from production offer capture.

## 1. One-time Valhalla setup

Open:

`CourierPilot → Reliability → Open route comparison`

The base URL should be:

`https://valhalla.zivkr.pp.ua`

Enter the VPS bearer token, enable **Enable route research requests**, then press **Save endpoint**.

The token is stored in app-private no-backup storage. Do not put it in screenshots, GitHub issues or public diagnostics.

## 2. Test a route you already know in Vilnius

1. Press **Use my current location**.
2. Grant location permission when Android asks.
3. Check that the status shows a location fix and approximate accuracy.
4. Enter a destination address that you personally know how to reach.
5. Press **Resolve address to coordinates**.
6. Press **Compare pedestrian vs cycleway**.

CourierPilot asks the self-hosted Valhalla server for two candidates:

- orange: pedestrian shortcut with a strong stair penalty;
- blue: cycleway-biased bicycle route.

The screen shows distance, generic Valhalla ETA, warnings and a geometry preview. The preview intentionally has no map tiles; its purpose is to make detours and candidate differences visible without adding another mapping provider.

## 3. Save your real judgement

After every useful comparison, choose exactly one:

- **Pedestrian is better**
- **Cycleway is better**
- **Both are usable**
- **Both are bad**

Optionally add a short note, for example:

- `stairs here`
- `unnecessary detour`
- `I normally cut through the courtyard`
- `cycle path is actually faster`
- `pedestrian route uses an impossible passage`

The comparison, both route candidates and your verdict are stored only in the separate local `route_research.db`.

Aim for 10–20 routes you genuinely know. Include several awkward routes: Old Town, river crossings, stairs/steep paths, courtyards and routes where a bike path competes with a shorter normal street.

## 4. Share a suspicious route

Press **Share comparison as GeoJSON**.

This shares:

- start/end coordinates;
- both route summaries;
- decoded route geometry as GeoJSON.

Treat it as private location data. Send it only deliberately.

## 5. Collect one real Bolt map sample

Bolt does not reliably expose pickup/drop-off addresses as text, so we need real map samples before implementing coordinate recovery.

One-time setup:

1. In Route Research press **Open Android Accessibility settings**.
2. Enable **CourierPilot Bolt diagnostics**.
3. Back in Route Research, press **Use my current location** once so CourierPilot has location permission and Android has a recent fix.

For a real incoming Bolt offer:

1. Before/while waiting for an offer, press **Arm next Bolt offer/map screen**.
2. Switch to Bolt.
3. When Bolt produces the next relevant screen event, the research service consumes the arm exactly once.
4. Return to Route Research.
5. Check the Bolt sample status.

A successful sample can contain:

- `accessibility-tree.txt` — Bolt Accessibility hierarchy including node bounds;
- `screen.png` — the same research screen screenshot, when Android permits it;
- `metadata.json` — timestamp, screen dimensions and the best cached phone GPS fix with age/accuracy/provider when available.

Press **Share full Bolt sample** to deliberately export those files.

The bundle may contain customer, merchant and exact location information. Do not attach it to a public GitHub issue. Send it privately for analysis, then use **Clear Bolt sample** if you do not want it left on the phone.

## 6. What is intentionally NOT happening yet

CourierPilot 0.9 does not:

- automatically query Valhalla for every Wolt/Bolt offer;
- keep continuous/background GPS tracking;
- auto-accept or auto-reject offers;
- claim that generic Valhalla ETA is your real scooter ETA;
- guess Bolt pickup/drop-off coordinates from a screenshot without evidence;
- use the future personal-route history in production decisions.

Those steps depend on the validation data gathered above.

## 7. What to send back for development

The most useful real-world inputs are:

1. a shared GeoJSON comparison where a route is obviously wrong or surprisingly good;
2. a full Bolt research sample from a real offer;
3. a short note describing how you would actually ride that route.

With those samples we can tune the route profile, verify Bolt marker semantics/geometry and decide when the future automatic offer-time routing is trustworthy enough to wire into the live offer flow.
