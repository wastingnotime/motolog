# Ride recording, optional automatic detection and daily statistics

Recording implements the [approved tracking defaults](decisions.md).
Use the [dashboard](dashboard.md) ride controls while safely stopped. Start and
Stop have at least 64 dp targets on the dashboard. Recording/gap/interrupted state
and Stop remain visible in other app flows and in the location-service notification. Automatic mode defaults off and requires an explicit foreground Arm action.

## Capture and recovery

The platform GPS provider requests one-second updates without Play services or
network access. Start/Resume is an explicit foreground action with precise location;
denied or approximate-only access leaves saved records available and explains
unavailable metrics. Notification access is requested on supported versions and
its status is shown. No background-location permission, boot receiver, periodic
worker or battery exemption is used. Android prerequisites follow the
[location foreground-service rules](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
and [location access rules](https://developer.android.com/develop/sensors-and-location/location/permissions).

A single IO lane commits each callback, cursor, gap boundary and affected daily
projection together. Start/Stop state appears only after the transaction commits.
A unique active slot prevents parallel rides; callback receipts prevent transaction
replay from counting evidence or totals twice. Stops target the displayed ride ID,
so a stale Stop cannot end a newer ride. The algorithm version captures the fixed
quality, sampling and speed-event defaults.

The service deliberately does not silently restart. On process initialization,
any active ride becomes interrupted. Service loss, disabled GPS or detected
permission revocation also interrupts it. A new process has a new clock epoch.
**Resume same ride** takes manual ownership, resets the baseline and preserves the
unobserved gap; **End at last observation** ends at the last persisted usable fix,
or start when there is no usable fix. Permission revocation or force-stop can kill
the process before cleanup; reopening performs the same recovery. No distance or
eligible time is inferred during downtime. The unmeasured tail between the last
fix and an explicit Stop is also retained as a gap. If Stop's wall clock is before
already saved usable observations, End uses the latest such existing observation
(or start when none exists), including when an earlier clock change already moved
the cursor backward. An explicit `WALL_CLOCK_CHANGE_AT_STOP` gap retains the
backward stop observation; the fallback contributes no distance or eligible time.
Usable samples cannot predate ride start; such clock-discontinuous deliveries remain
raw rejected evidence. Retrying Stop preserves the original end, samples and totals.
A storage/capture failure stops capture and reports a possibly missing tail of up to five seconds under normal operation;
committed evidence survives. The recorder does not promise survival of arbitrary
storage stalls, power loss or OEM process restrictions.

## Optional automatic mode

Arm automatic mode while safely stopped, with precise location granted. A visible
location service requests five-second GPS updates while waiting and one-second
updates during a ride. Delivery remains best effort. The notification and parked
status bar expose armed mode and Disarm even without an active ride. Monitoring
uses battery; there is no exemption demand or hidden restart. Denied or approximate
location leaves auto off, with an explanation and saved records still available.
Manual recording uses the same precise-location prerequisite and remains available
when automatic mode is off.

A quality-approved sequence at >=6 km/h qualifies after at least 15 seconds and
four fixes, with gaps <=6 seconds and plausible clock/geodesic pairs. A lower speed,
rejected fix or continuity break resets it. Qualification creates one automatic-owned
ride at the first buffered observation and commits all candidate samples and daily
totals atomically. Five minutes continuously <=3 km/h ends only an automatic-owned
ride, at the qualifying observation, including measured stopped time. Gaps <=10
seconds are allowed for idle; missing GPS, invalid pairs and speed >3 km/h reset
the idle window. Brief stops and stopped jitter cannot end a ride prematurely.

Automatic stop leaves detection armed. Manual Stop ends either ownership and
disarms. Take manual ownership retains the same ride ID and disarms detection;
re-arming while a manual ride exists never lets idle detection end it. Disarm
alone keeps an existing ride recording until explicit Stop. Resume after
interruption takes manual ownership and leaves automatic mode off.

Schema 6 adds a single bounded detection checkpoint. Candidate fixes and the
idle anchor persist in the same transaction as ride samples, callback receipts
and projections. Candidate capacity is 256 fixes; an excessive burst resets the
window safely. Every fix commits immediately. The detection version is retained
in an automatic ride's reserved reducer-state field, independently of the GPS
metrics algorithm version. Process initialization and service/permission loss
discard armed state and unqualified candidates; existing rides become interrupted.
There is no candidate replay across process downtime, boot or force-stop.
No idle time is inferred from that downtime.

## Evidence and totals

`QualityFix` retains every delivered native field, missing values, arrival age,
clock epoch and rejection reason. Invalid nonfinite fields are stored as text to
avoid SQLite numeric coercion. `RideSample` retains each usable native fix,
independently of its speed-event flag. Quality limits are inclusive: horizontal
accuracy (0,25] m, age [0,10] s, native speed [0,200] km/h and speed accuracy [0,2]
m/s. Out-of-order fixes, missing fields and rejected quality open gaps and reset
the speed-event baseline. The first usable speed in a segment is an event; later
events require an absolute change of at least 5 km/h from the last event.

Adjacent usable fixes connect only within one uninterrupted epoch, with increasing
elapsed time, at most ten seconds separation, wall/elapsed discrepancy at most
two seconds and WGS84 geodesic implied speed at most 200 km/h. A rejected pair
creates a gap and uses its second fix as a new baseline. Both endpoint speeds
at most 3 km/h suppress jitter distance to zero while preserving eligible time.
Cached fixes predating the current Start/Resume monotonic boundary are retained
as rejected evidence. A one-second heartbeat exposes missing delivery after ten
seconds. That live marker is provisional: a delayed fix whose native timestamp
still proves an eligible pair retracts it, so callback cadence cannot discard
valid coverage. Actual invalid or over-ten-second pairs finalize the gap. Live gaps and
the latest twenty closed gaps are visible; older raw evidence remains local.

Daily distance and eligible milliseconds are incremented atomically with each
accepted pair. Average speed divides summed unrounded distance by summed eligible
time, including measured stops, across rides. Zero time is unavailable; positive
time with zero distance is zero. Gaps and downtime are excluded. GPS statistics
are labeled estimates with partial coverage and never update owner odometer rows.

Each pair uses the first fix's recorded zone until the second fix, where a zone
change takes effect. Split at actual local midnight, allocating distance by
elapsed fraction and integer durations with exact remainder conservation. DST
uses actual elapsed time. Wall-clock discontinuities start a gap; prior days are
never rebucketed using the viewing zone. Today sums matching recorded local dates
across their recorded zones, with gaps/rejected fixes shown separately.

The cursor references only the previous usable sample and last speed event.
Capture uses primary-key/indexed active-slot lookups and touched-day projections;
it never loads historical samples. Refresh queries only the selected day, twenty
recent rides and twenty gaps, using supporting indexes. UI reads and all writes
run off the main thread. Schema 5 also persists versioned per-ride totals and
pages ride history/details; see the dashboard query boundaries. Schema 4 added
recording rows/indexes without rewriting existing evidence or receipts; raw quality evidence is append-only.

## Verification boundaries

Deterministic JVM traces cover quality and threshold boundaries, geodesic outliers,
missing/non-increasing fixes, clock changes, stopped jitter, midnight, zone changes
and DST. Room tests cover concurrent starts, receipt replay, interruption/resume,
zero-duration rides, weighted totals, migration and a two-hour one-second trace
with indexed-query assertions. Separate process probes check durable recovery.
Platform-GPS tests inject synthetic native locations into the real service,
check foreground/background capture, missing-fix recovery, native automatic start, a real five-minute measured idle stop and notification Stop/Disarm.

API 35 emulator results do not establish physical GNSS native speed/accuracy
availability, OEM/custom-ROM process behavior, battery use or touch usability.
The reported Moto G5 on Android 15 remains an owner-controlled physical validation
target, using parked controls and ordinary riding with records kept local.
API 26/30/36 and physical runs must be reported separately when performed.
