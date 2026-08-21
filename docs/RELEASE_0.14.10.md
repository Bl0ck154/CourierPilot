# CourierPilot 0.14.10

- Recognize the real Bolt accepted pickup screen cue `Order is ready for pickup` as lifecycle `ACCEPTED`.
- This makes the 0.14.9 active-pickup memory fallback actually engage for real add-on orders before the first pickup is collected.
- Adds regression coverage for the real Bolt pickup-screen wording.
