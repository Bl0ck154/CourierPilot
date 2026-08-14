# CourierPilot release signing

CourierPilot releases use one permanent Android signing identity. Only public certificate information is recorded here; the keystore, private key, and passwords must never be committed.

- Application ID: `com.block154.courierpilot`
- Key alias: `courierpilot-release`
- Certificate subject/issuer: `CN=CourierPilot, OU=Mobile, O=Block154, L=Kyiv, ST=Kyiv, C=UA`
- Certificate SHA-256: `74:55:64:17:F1:28:92:81:BC:AF:1A:2C:6F:3F:4A:A1:19:DB:24:B0:79:A1:37:59:A5:83:C3:CC:66:79:6B:70`
- Validity: 2026-08-14 through 2053-12-30

GitHub Actions restores the base64-encoded keystore into `$RUNNER_TEMP`, builds `assembleRelease`, verifies `app-release.apk` with `apksigner verify --verbose --print-certs`, and rejects an APK whose signing certificate does not match the fingerprint above.

The required repository secrets are `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.

Losing the permanent keystore or its credentials makes it impossible to publish compatible updates under the same application ID. Keep the protected off-GitHub backup and its recovery data safe.
