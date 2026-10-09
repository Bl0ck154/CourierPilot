# CourierPilot 0.17.1 — fast History, redesigned Stats, Pay and Settings

Android version: **0.17.1** · versionCode **140**

## Faster History

- History no longer shows "Loading offers…" every time. Stored rows appear immediately; the
  re-parse that repairs old rows (venue names, stops) runs in the background, in parallel, and is
  cached for the whole app session, so returning to History or resuming the app is instant.
- Offers load in chunks of 30 while you scroll instead of 50-row pages, so 1,500+ offers scroll
  smoothly. The list, scroll position, search and filter are kept when you switch tabs.

## Redesign (design D)

- **Stats**: Today / 7 days / 30 days chips; big graded average €/km with offers, online time and
  offers per hour; Wolt vs Bolt rows; a 14-day bar chart where each bar takes that day's verdict
  colour; per-day rows. Nothing on the screen opens another screen unexpectedly any more.
- **Pay**: your and city medians as graded €/km, trend pill, Day / Week / Month history as grouped
  rows.
- **Settings**: back button, uppercase section labels, grouped blocks for every section;
  Android access, Reliability Center and Developer tools as list rows. Switches use the theme
  accent.

## Manual acceptance checklist — real device

- [ ] Open History with 1,000+ offers: rows appear at once; scroll to the bottom loads more.
- [ ] Switch to Home and back: History keeps its position without "Loading offers…".
- [ ] Stats: period chips change all numbers; no accidental navigation when tapping cards.
- [ ] Pay and Settings look right in Light and Dark.
