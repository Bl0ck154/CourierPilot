# Getting started with CourierPilot

## Install the APK

1. Open the [latest release](https://github.com/Bl0ck154/CourierPilot/releases/latest) and download the signed `CourierPilot-v*.apk`. The checksum is published beside it.
2. Verify that you downloaded the release from **Bl0ck154/CourierPilot**, not an unofficial mirror. Android 11 or newer is required.
3. Install the APK using Android's package installer. Your phone may ask you to allow installation from this source; enable this only for the source you trust.
4. Open CourierPilot. The Home screen shows **Notifications** and **Accessibility** shortcuts for any missing Android access. Read [what these sensitive permissions expose](PRIVACY_AND_PERMISSIONS.md) before granting them. On Android 13+, read the [restricted-settings help](ANDROID_RESTRICTED_SETTINGS.md) if Android blocks a permission.
5. Open Wolt Partner or Bolt Food and inspect **Home** and **History** after a priced offer. The app must not accept or decline orders for you.

## Basic: install, capture, and see an offer estimate

No CourierPilot account, special app registration, Valhalla token, or separate server setup is required for daily offer capture. The live offer card is enabled by default. When Wolt or Bolt provides **both payout and distance** on the offer, CourierPilot shows a **provisional pay-per-kilometre estimate** based on that platform distance (without a route-verified rating emoji). Offers can still be saved locally when distance is missing. Never treat a provisional platform kilometre as a Valhalla-calculated walking/cycling route.

The local Home, History, Addresses and Stats screens work without Valhalla. Market comparisons that require verified routes can remain in their learning/unavailable state until enough eligible observations exist.

## Optional advanced setup: walking/cycling route estimates

1. Configure a **trusted HTTPS Valhalla gateway** in the app's route research/settings screen.
2. Enter the gateway's **private bearer token**, supplied by an administrator you trust. Never share it in issues or screenshots.
3. Enable the relevant Wolt/Bolt route options and grant foreground location permission.
4. Test one known route and compare the result with what you would actually ride. Bolt map-marker recovery and stacked orders are especially dependent on the platform's current UI.

**There is no publicly provisioned default token.** The project's private server is not a free routing API for third parties. A supported public plan would require a new authenticated provisioning, rate limiting, privacy notice, operating budget, location-data handling, and reliability testing.

## Troubleshooting

- **No capture:** check Notification access, Accessibility service status, power/battery restrictions and that the courier app really shows a non-zero payout. If Android displays a restricted-setting warning for the GitHub APK, see the [Android 13+ instructions](ANDROID_RESTRICTED_SETTINGS.md) and review the security implications first.
- **No provisional €/km:** a visible payout and valid platform-reported distance are both needed. Some Bolt offers omit the full distance before acceptance.
- **No coloured route-verified verdict:** this is an advanced feature. Check routing opt-in, the private HTTPS endpoint/token, GPS and resolved route stops. The app refuses to invent a route.
- **Incorrect Wolt/Bolt OCR:** use the **Reliability** diagnostics report and file a [redacted bug report](https://github.com/Bl0ck154/CourierPilot/issues/new/choose). Do not attach customer names, exact addresses, screenshots containing sensitive information, or access codes.
- **App updates:** releases are GitHub-hosted signed APKs; automatic in-app updates may involve Android's install-from-source permission. No Google Play distribution is claimed.

[Understand market analysis](MARKET_ANALYSIS.md) · [Privacy and permissions](PRIVACY_AND_PERMISSIONS.md) · [Compatibility](COMPATIBILITY.md) · [Release signing](RELEASE_SIGNING.md)
