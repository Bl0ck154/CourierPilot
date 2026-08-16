# CourierPilot 0.12.0

`versionCode 22`

0.12 fixes the first live-advisor Bolt UX and wires the first real Bolt routing path.

## Live advisor cleanup

The overlay is now platform-aware and compact:

- no `Wolt route` control while Bolt is foregrounded;
- one `Route ON/OFF` control applies to the current platform;
- voice is represented by a compact speaker icon;
- missing platform distance is omitted instead of rendered as `? km`;
- the internal phrase `platform data` is removed;
- `Calculated route: not requested` is removed;
- enabling Route while the current offer is still open immediately retries that offer instead of waiting for the next offer.

## Bolt routing

Bolt route intelligence remains conservative because the offer UI can expose the pickup address while withholding a textual customer destination.

With **Route ON** for Bolt:

1. CourierPilot obtains a fresh phone GPS fix;
2. it geocodes the textual Bolt pickup address;
3. it always attempts a real Valhalla comparison for `current location -> pickup`;
4. it inspects the active Bolt Accessibility map tree for semantic current/pickup/customer markers;
5. only when all three marker roles are trustworthy does it build a two-anchor screen-to-geo transform from:
   - current marker pixel <-> device GPS;
   - pickup marker pixel <-> geocoded pickup;
6. the customer marker is projected through that transform and sanity-checked;
7. a trusted customer point upgrades the route to `current -> pickup -> customer`;
8. otherwise the advisor explicitly stays **pickup-only** and does not fabricate total kilometers.

Both pedestrian-shortcut and cycleway-biased Valhalla candidates remain separate.

## Automatic clean Bolt evidence

When experimental Bolt routing is enabled, CourierPilot also stores the latest private Bolt research bundle automatically:

- Accessibility tree;
- the already-persisted clean offer screenshot;
- cached GPS metadata.

Using the persisted proof screenshot matters because it was captured before CourierPilot drew the advisor overlay.

This evidence is for future screenshot/map-registration work if Accessibility semantics are insufficient. It remains local unless deliberately shared.

## Still unresolved by design

A screenshot cannot yield a trustworthy customer coordinate merely because a map is visible. If Bolt does not render a customer marker or other independent geographic evidence on the offer screen, CourierPilot keeps the customer point unknown.

The next fallback research step is rendered-map registration / visual marker detection against the known GPS + pickup anchor, validated on real samples. No production total-distance value should be generated from an unvalidated pixel heuristic.
