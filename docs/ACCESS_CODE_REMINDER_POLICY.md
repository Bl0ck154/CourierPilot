# Access-code reminder timing policy

Saved door/intercom codes are delivery-scoped reminders. Background location is an optional timing
upgrade, not a prerequisite.

- With background location access, CourierPilot keeps the proximity path and surfaces the reminder
  near the resolved/learned entrance.
- Without background location access, CourierPilot must not nag the courier to enable it. It arms a
  durable ETA fallback for the same delivery and surfaces the code shortly before the expected
  arrival.
- ETA preference is: current delivery-screen ETA, then the active captured offer ETA adjusted for
  elapsed time, then a conservative delayed fallback if neither source exists.
- The ETA timer is anchored to the delivery identity. Repeated Accessibility refreshes must not keep
  pushing the reminder later; a fresher shorter ETA may only move it earlier.
- Positive `Works` feedback can learn an entrance coordinate only when the notification carried a
  real arrival location fix. ETA-only reminders never invent location samples.
