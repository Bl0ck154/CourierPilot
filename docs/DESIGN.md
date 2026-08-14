# CourierPilot Design System

CourierPilot is a calm, high-signal courier dashboard. It should feel like a purpose-built field tool rather than a debug utility or a generic settings app.

## Product feeling

- Fast to scan outdoors and while moving between stops.
- Confident, modern, slightly technical.
- Information-dense without looking cramped.
- Avoid an all-white card wall. Use layered surfaces, tinted metrics, and a strong visual anchor.
- Capture/reliability status should be visible but quiet when healthy.

## Visual direction

### Core palette

- Ink / primary dark: `#0B1220`
- Ink elevated: `#111B2E`
- App background light: `#F3F6FA`
- Surface light: `#FFFFFF`
- Border light: `#DCE3EC`
- Primary blue: `#2563EB`
- Primary cyan: `#22B8CF`
- Success: `#16A34A`
- Warning: `#F59E0B`
- Danger: `#DC2626`
- Purple metric accent: `#7C3AED`
- Muted text: `#64748B`

Use tinted metric surfaces instead of identical white cards:

- blue tint `#EFF6FF`
- cyan tint `#ECFEFF`
- green tint `#F0FDF4`
- amber tint `#FFFBEB`
- violet tint `#F5F3FF`

### Dark mode

- Background: `#080D17`
- Surface: `#101827`
- Elevated surface: `#152033`
- Border: `#243247`
- Text: `#F8FAFC`
- Muted: `#94A3B8`

## Typography

Use the system sans family through Material 3 typography. Prioritize hierarchy over decorative fonts.

- Screen title: 28-32sp, semibold
- Hero metric: 28-34sp, semibold
- Section title: 18-20sp, semibold
- Card title: 14-16sp, medium/semibold
- Supporting copy: 12-14sp
- Dense metadata: 11-12sp

Do not use uppercase labels everywhere. Use uppercase only for tiny metric eyebrow labels when it improves scanning.

## Shape and elevation

- Main cards: 20-24dp radius
- Small chips: pill / 999dp radius
- Buttons: 14-18dp radius
- Prefer tonal separation and border contrast over heavy shadows.
- One hero surface may use a subtle dark-to-ink gradient.

## Home

The Home screen is a dashboard, not a settings page.

1. Top hero / command card
   - CourierPilot title
   - compact health indicator (`Capture ready` / `Needs attention`)
   - settings icon button
   - current shift state and primary Start/End shift action
   - dark ink surface so the screen has a strong visual identity

2. Today metrics
   - 2x2 responsive grid
   - Offers, Avg offer, Avg €/km, Wolt/Bolt
   - each metric gets a distinct subtle tint/accent
   - every metric is clickable and routes to a relevant filtered History or Stats view

3. Recent offers
   - show merchant, price, platform, stacked badge, first customer/drop-off preview
   - tap opens Offer Details

4. Activity
   - a finger-friendly recent heatmap (roughly 12-16 weeks on Home)
   - minimum practical cell/touch target; do not show a microscopic full-year calendar on Home
   - selected day opens/filters History for that date

## History

- Search field is persistent at the top.
- Search all captured fields: merchant, pickup, customer, drop-off, platform, price, distance, raw text, date/time.
- Add filter chips: All / Wolt / Bolt / Single / Stacked.
- Group results by day.
- Offer cards should use platform accent and route summary instead of being plain white rectangles.

## Statistics

- Use period selector: Today / 7d / 30d / All.
- Primary cards: offers, average price, average €/km, tracked work time.
- Platform split and Single vs Stacked should be visually charted, not only text.
- Full activity calendar can be horizontally scrollable with larger cells.
- Hourly activity chart must clearly say it reflects captured offers, not inferred work hours.

## Offer details

- Strong price/platform hero.
- Route is a vertical timeline:
  - blue pickup nodes
  - green drop-off nodes
  - multiple drop-offs from the same pickup are first-class and obvious
- Customer names/addresses remain local-only.
- Original screenshot is a secondary evidence action.

## Reliability

- Healthy state should be concise.
- Problems should become visually prominent only when action is required.
- Advanced diagnostics/event log are collapsed by default.
- Periodic alive reminder is optional and clearly described as approximate/non-persistent.

## Interaction

- If a card looks tappable, it must be tappable.
- Minimum touch target: 48dp.
- Use ripple/pressed feedback.
- Bottom navigation: Home / History / Stats with real vector icons and a clear selected state.
- Respect `WindowInsets.safeDrawing` / system bars on every screen.

## Privacy

- Customer names and addresses are local device data.
- Diagnostic logs must never include raw offer text, customer names, or addresses.
- Future export must exclude customer/drop-off PII by default.

## Implementation preference

New UI should use Jetpack Compose + Material 3. Keep capture, parser, SQLite and Accessibility logic independent of Compose. Migrate screen-by-screen and retain the existing Views activities as fallback until the Compose launcher passes startup and functional tests.
