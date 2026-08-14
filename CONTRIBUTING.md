# Contributing to CourierPilot

Thanks for helping improve CourierPilot.

## Before opening an issue

- Check whether the problem is already reported.
- Include the CourierPilot version, Android version, device model, and affected courier platform.
- For capture/reliability issues, prefer the privacy-safe diagnostics report from the Reliability screen.
- Never post customer names, phone numbers, exact delivery addresses, or unredacted courier screenshots.

## Parser reports

Wolt and Bolt can change their UI without notice. Parser bug reports are especially useful when they include:

- platform;
- which field was missing or incorrect;
- the expected value;
- a **redacted** representation of the relevant screen text or layout.

Do not publish personal order data just to reproduce a parser problem.

## Pull requests

Keep changes focused and explain the real courier workflow they improve.

For parser changes:

1. Add or update a regression fixture/test when possible.
2. Preserve the core rule: a capture is not final until a plausible non-zero price is present.
3. Do not infer fields that the courier app did not expose.
4. Keep customer/address data out of privacy-safe reliability logs.

Run the unit tests before opening a PR:

```bash
gradle testDebugUnitTest
```

Release signing credentials are intentionally not part of the repository. Normal contributors do not need the private signing key.
