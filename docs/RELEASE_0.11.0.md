# CourierPilot 0.11.0

`versionCode 17`

0.11 adds an explicit foreground GPS ride recorder so future personal routing can be trained/validated from real ridden evidence instead of guessed speed constants.

## Added

- `Ride trace` screen with Start/Stop and live local session status;
- Android location foreground service with ongoing notification and Stop action;
- long-press CourierPilot launcher shortcut → `Ride trace`;
- local `gps_sessions` / `gps_samples` persistence in `route_research.db`;
- ~2 s / 2 m location request cadence;
- rejection of >80 m accuracy points and extreme GPS jumps;
- service heartbeat for interrupted/stale-session detection;
- orphaned open-session cleanup on the next explicit start/stop;
- latest trace summary with distance and average speed;
- deliberate GeoJSON export containing geometry plus per-point timestamps, accuracy and reported speed;
- deletion of the latest finished trace or all finished traces, with confirmation;
- tests for trace filtering, distance and rich GeoJSON metadata.

## Android/privacy boundary

Recording starts only after a visible Activity action. Foreground location permission is required; Android 13+ also requires notification permission in CourierPilot so an active recording remains visibly controllable in the notification surface.

The service is `START_NOT_STICKY` and does not silently restart after process death or reboot. No `ACCESS_BACKGROUND_LOCATION` permission is added. Finished traces remain local until deliberately shared and can be deleted from the Ride trace screen.

## Not yet wired

0.11 deliberately does not:

- upload traces automatically;
- call Valhalla `/trace_attributes` automatically;
- derive per-edge personal ETA yet;
- use traces to change an offer verdict;
- auto-start GPS from Accessibility/platform presence.

Those steps follow only after real traces exist and map-matching behavior is validated.
