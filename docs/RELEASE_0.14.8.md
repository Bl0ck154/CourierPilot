# CourierPilot 0.14.8

## Bolt stacked / double-order routing

- Reads Bolt `Drop-off points: N` and preserves the expected number of customer stops.
- Preserves multiple merchant/pickup rows from the Bolt bottom sheet instead of collapsing the offer to one restaurant.
- Detects multiple blue pickup pins and multiple green customer pins in the clean Bolt offer screenshot.
- Supports same-restaurant doubles such as 1 pickup + 2 drop-offs.
- Supports ordinary doubles with 2 textual restaurants / 2 pickup addresses + 2 drop-offs.
- Supports the harder add-on flow where Bolt offers another order while an earlier order is already active: if the card exposes only the new restaurant but the map has an additional blue pickup pin, CourierPilot can recover that hidden pickup from map geometry.
- Matches known geocoded pickup addresses to map pins using current GPS + local map transform rather than merchant-name guesses.
- If the expected drop-off marker set is incomplete, CourierPilot fails closed to the known pickup route instead of pretending a partial customer route is complete.
- The live Bolt overlay now shows stop counts such as `Full route · 2P/2D`.

## Parsing safety

- Legitimate short merchant names remain valid.
- Map/street-like words are ranking signals only; they are not hard bans on merchant names. A real venue containing words such as `Park` is preserved when it is the actual card-title candidate.

## Validation

- Added regression coverage for 1P/2D same-restaurant doubles, 2P/2D two-restaurant doubles, hidden add-on pickup recovery, and multi-marker screenshot extraction.

## Version

- `versionName = 0.14.8`
- `versionCode = 35`
