# CourierPilot 0.15.32

## Bolt duplicate capture fix

- Bolt persistence dedupe now compares nearby captures even when OCR read a different price.
- A stable Bolt card/route can deduplicate short price-drift captures such as one real `€6.84` offer being read again as `€84.00`.
- Bolt screen burst identity no longer depends on OCR price once a canonical route address is visible.
- Historical repair revision 14 re-checks existing Bolt price-drift duplicates, removes duplicate Market samples, and cleans duplicate screenshots best-effort.
- Regression coverage keeps genuinely different Bolt routes separate.

## Notification auto-open fix

- Persistent online/status notifications such as `Bolt Courier app is running` are now hard negatives and can never be classified as new offers.
- Unlock recovery no longer replays an old remembered PendingIntent after the exact offer notification disappeared.
- The exact active offer notification is revalidated immediately before an unlock retry.
- Previously screen-confirmed offer profiles may recover a completely textless transient order push, improving auto-open reliability without allowing visible unrelated messages to match by structure alone.

## Version

- `versionName = 0.15.32`
- `versionCode = 73`
