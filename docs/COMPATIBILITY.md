# Compatibility and field validation

CourierPilot is an Android 11+ application developed primarily around **Wolt Partner** and **Bolt Food** offer screens. It is *not* an official Wolt/Bolt integration and the platforms can change their UI at any time.

| Surface | Implementation | Public confidence |
| --- | --- | --- |
| Wolt priced-offer capture | Notification/accessibility/OCR parser with regression tests | Device/locale-specific field testing still required |
| Wolt stacked offers / add-ons | Explicit route-stop and payout guards | Conditional on current screen layout; verify on device |
| Bolt Food priced-offer capture | Accessibility and OCR | Device/locale-specific field testing still required |
| Bolt customer map pins | Screenshot-based recovery with sanity gates | Experimental; unknown/conflicting positions must not be presented as facts |
| Android | Minimum API 30 (Android 11), target API 35 | No complete device/manufacturer compatibility matrix published |
| Routing | User-configured HTTPS Valhalla gateway | Requires protected working endpoint + token; not bundled for public use |

Do **not** market a city or language as field-verified merely because parser unit tests pass. A public compatibility matrix should record the actual **country, app version, Android version, device, date, offer type, tested behaviors and known gaps**, without exposing any delivery/customer data.

Testers can submit a redacted [compatibility/bug report](https://github.com/Bl0ck154/CourierPilot/issues/new/choose).

## Suggested field-test checklist

- Single priced offer captured once; fee/currency correct
- Stacked/add-on offer: correct payout and stop order
- Decline/accept buttons remain unobstructed
- Capture continues after screen transitions and battery optimization
- Route missing / failed: unknown or estimate shown, never made-up verification
- No customer names, addresses, apartment codes or GPS tracks in bug reports
