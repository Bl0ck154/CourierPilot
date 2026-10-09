# CourierPilot 0.17.4 — clean screenshots: the card is hidden, not painted over

Android version: **0.17.4** · versionCode **143**

## Fix

- **Screenshots no longer contain CourierPilot's card or a patch where it was.** Since 0.17.0
  full-screen (display) captures kept the card on screen and painted over its pixels, which left
  a pale block (0.17.1), streaks (0.17.2) or a blur (0.17.3). Now the card is made invisible for
  the capture itself (two frames for the screen to update, then the screenshot), and shown again
  immediately after; a watchdog brings it back within 1.5 s even if Android never answers. A card
  that appears while a capture is in flight stays invisible until that capture is done.
- Painting over the card remains only as a fallback for the rare moment when your finger is on
  the card (dragging or swiping), because hiding it then would break the gesture.

## Manual acceptance checklist — real device

- [ ] Proof screenshots in History show the plain Wolt/Bolt screen, no card and no patch.
- [ ] The card blinks off only for an instant while a screenshot is taken and always comes back.
