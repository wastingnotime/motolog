# V1 decisions and implementation defaults

Recorded 2026-11-12 against repository baseline
`0128d374735b12c1c4f2086f7b0f345e1e587744`. Read with the
[V1 brief](v1.md), [execution plan](execution-plan.md) and
[partial manual inventory](../../references/fazer-ys250-blueflex-maintenance.md).
Examples here are synthetic, not owner records.

## Authority and status

**Accepted by owner** means explicit direction recorded in local coordination.
On 2026-11-12 the owner said “do the project” and selected the proposed defaults
with en-US replacing pt-BR. The direction gate is satisfied; do not ask again
for authorization already given. **Implementation default** means a concrete
choice made under the owner's subsequent direction to resolve routine details
using inspection and evidence; it is not an additional claimed owner answer.
**Unresolved input** remains unknown until supplied. **Proposed/deferred** means
excluded from initial implementation, not an implicitly accepted behavior.

This log and the execution map resolve the documentation producers for app work.
They do not implement or dispatch it. Foundation code starts separately, with
bootstrap first. Task readiness still requires its producers and scoped decisions.

| Subject | Status | Recorded decision |
| --- | --- | --- |
| Direction and stack | Accepted by owner | Proceed with planned V1; one Android app module, Kotlin/Compose, transactional local storage, no backend. |
| Display and money | Accepted by owner | en-US UI/display locale, kilometers/liters/km/h, BRL currency. Locale does not change currency or units. |
| Ride identity and day | Accepted by owner | One persisted active ride; midnight splits daily totals, not the ride, using device-local days. |
| Average speed | Accepted by owner | Include stopped time in eligible tracking time. |
| Speed-change event | Accepted by owner | 5 km/h compared with the last recorded speed. |
| Automatic detection | Accepted by owner | Sustained movement starts a ride; five minutes idle stops an automatic ride. Exact rules below are implementation defaults. |
| Personal setup | Accepted direction | Collect current odometer/date and qualifying prior services locally; unknown anchors are allowed. No invented personal seeds. |
| Database, Android floor, quality constants | Implementation default | Room; Android 8.0/API 26 minimum; exact toolchain, matrix and tracking defaults below and in the execution plan. |
| Bike/manual match, current reading, service evidence, physical phone | Unresolved input | Registration/owner field validation must establish these; they are not prefilled from guesses. |
| Milestone/event/condition evaluation and configurable tracking thresholds | Proposed/deferred | Keep unsupported maintenance semantics visible. No threshold settings in initial V1; tuning requires versioned defaults and renewed test evidence. |

## Registration, anchors and completed history

The one-bike preset describes the target range, not a verified personal identity.
Before admitting a manual template, registration must confirm model/variant,
market, model year and manufacture year independently against the reviewed source.
The unresolved match blocks template admission, not offline registration with
“manual applicability unconfirmed” or generic owner-created **Custom** work.

**Implementation defaults:** setup asks for owner-entered whole kilometers and an
observation timestamp, defaulting the timestamp to now only when the owner confirms
a reading now. A reading may be skipped; mileage status is then unknown. Keep
recorded-at separate from observed-at. Past readings do not become current by
importing them today. Never use GPS, acquisition date, manufacture date or zero
as an absent service anchor. A service needs its own performed date and/or
odometer for each threshold; one known threshold can show due while the other is
unknown, but “not due” requires every defined threshold to be evaluable.

Private history supplied during planning remains private. After persistence exists,
provide owner-reviewed device-local entry/import with a preview of each source
note, date/reading certainty, action mapping and duplicate candidates. Confirm
before writing; write the confirmed batch transactionally with a stable import ID
and per-row identity. Replaying an import is a no-op; uncertain duplicate matches
require review, not automatic merging. Retain original notes privately and permit
unknown dates/readings/costs. Unknown cost is not zero. Ambiguous hydraulic work,
inspection, purchase, promised or diagnostic work cannot prove a replacement.
An oil replacement cannot prove filter replacement. An uncertain reading cannot
advance the current odometer or a qualifying recurrence anchor.

Import/setup is distinct from manual-template admission. Public fixtures contain
synthetic records only. No private baseline is embedded in the application or
published in repository examples. A fresh confirmed reading is still needed for
current calculations after historical import.

**Implementation default: immutable completion snapshots.** Each completion stores
bike and occurrence IDs, performed item/action and component scope, date and
optional odometer/cost/location/notes, source kind, baseline rule revision and
locators, owner override revision, original/effective targets and qualifying
anchor evidence. Capture values, not just mutable foreign keys. Full completion,
partial completion and linked remainder commit atomically under an operation ID;
retries cannot create a second result. History is read-only, including after
restart. Corrections use linked additive annotations/superseding evidence without
rewriting the original; no history-edit/delete UI in initial V1. A backdated
record cannot silently displace a later qualifying anchor or revise old snapshots.

## Maintenance recurrence and postponement

**Implementation defaults under the accepted limited-coverage direction:** support
only explicitly typed simple interval rules with applicable verified source and
confirmed qualifying anchors, or owner-created **Custom** intervals. For these,
next distance/date = actual qualifying completion distance/date + interval.
Calendar months/years use local calendar addition, clamping invalid end-of-month
dates to the last day. Date-only service anchors use their recorded local date;
due dates are inclusive from local midnight. Never reinterpret source milestones
as intervals. Store anchor kind and supported/unsupported status explicitly.

Only the unconditioned replacement intervals in the manual inventory are initially
eligible for automatic interval evaluation after applicability/anchors are confirmed
(external fuel filter, spark-plug replacement, brake fluid and hydraulic internal
parts with verified component scope). The brake-hose condition, air-filter use
conditions, chain events, oil/filter and inspection milestones remain visible as
unsupported/manual review, with no blanket “maintenance current” claim. Recording
performed work remains available even for unsupported schedules.

A synthetic **Custom** rule of 2,000 km / 3 months completed at 20,000 km on
January 31 is next due at 22,000 km or April 30, whichever is reached first.
Late completion at 22,100 km anchors its next interval at 24,100 km. In contrast,
a manual milestone at 20,000 km never moves the next fixed milestone when done
late; automatic milestone evaluation is deferred until replay, calendar origins
and late/partial reconciliation have a separate accepted design.

Postponement accepts an editable absolute whole-km target, local calendar date,
or both; at least one is required. Either threshold makes the remainder due.
Offer tomorrow and the latest confirmed odometer + 100 km as editable convenience
values, never manual recommendations; offer no km suggestion without a reading.
Require the replacement target to advance the target in that dimension and be
in the future relative to known current evidence. Explain any dropped threshold
and require explicit confirmation when replacing a dual target with a single one.
Preserve the original due thresholds and every postponement revision and reason.
An effective postponed target does not mean work was performed or erase the
original overdue status; show both “originally due” and “postponed until.”

Full postponement writes no completed work. Partial completion snapshots only
performed items, advances only their qualifying interval anchors, and links the
rest to the same occurrence with effective targets. Later template revisions
apply prospectively; outstanding items retain their original rule snapshot.
If a later occurrence includes the same unresolved action/component, show both
links and one outstanding-work identity; do not discard, duplicate or automatically
complete the earlier obligation. A completion explicitly lists the obligations
it satisfies. Unsupported milestone overlap stays manual review; no engine guess
may make such work disappear. Programmed one-off work follows the same target and
transaction rules; corrective records do not silently complete unrelated actions.

## Fuel, odometer and freshness

**Implementation defaults:** persist entered decimal values and entry mode exactly
as normalized decimal strings, with no binary floating-point money. Total BRL has
at most two decimal places; liters and BRL/liter accept at most three. Reject
excess precision with feedback rather than truncating. All three inputs must be
positive and finite, with at most 12 integral digits; odometer is nonnegative
whole km with at most 12 digits. Reject overflow before persistence. Use decimal
division with scale 12 and HALF_UP for derived values; retain the entered
operands, derive for display, and round displayed liters/unit price to three places and total paid to two. Never feed a display-rounded result back into
the calculation. Display an explicit BRL label (for example, BRL 100.00), en-US
numbers and unambiguous local dates. The UI distinguishes total paid from unit price.

Example: BRL 100.00 and 20.000 L derive BRL 5.000/L; BRL 100.00 and BRL 5.000/L
derive 20.000 L. BRL 100.00 / BRL 6.000/L retains its operands and displays
16.667 L. Entry-mode changes require reviewing the resulting values before save.
An operation ID prevents repeated-save duplicates.

Allow backdating with an explicit local observation timestamp and zone; reject
future events. Equal readings are valid. Validate a confirmed odometer against
both chronological neighbors; reject a decrease/inconsistent sequence and offer
private uncertainty review for historical evidence instead of silently repairing
it. Unknown/uncertain imported readings remain history, excluded from authoritative
calculations. Backdating recomputes affected projections, never rewrites saved
fuel operands or history snapshots. Latest refuel means latest occurred-at, not
latest insertion; ties use a stable event ID and expose same-time ambiguity for review.

“Current” means a confirmed owner observation no more than 24 hours old, with
visible observed-at and age. Freshness expires without inventing distance. For
kilometers since refuel, select the latest chronologically valid refuel with a
confirmed reading and require a fresh confirmed reading at/after it. Show current
minus refuel reading only when nonnegative and sequence-valid; otherwise show
unavailable with the missing/stale/ambiguous reason and offer “Update odometer.”
Show the last recorded refuel odometer separately; flag if a newer refuel lacks a
usable reading rather than pretending an older event is the latest. Maintenance
may show mileage status as-of an older reading with its age, but cannot claim
current not-due from a stale reading. GPS never overwrites these observations.

## Location samples, speed events and eligible time

These are **implementation defaults**, not manual facts or measured accuracy
claims. Use Android platform GNSS through a location adapter, without requiring
Google Play services or network. Request 1-second samples while recording and
5-second samples while auto is armed without a ride. Delivery is best effort;
algorithms use sample timestamps, never callback counts or promised cadence.

A usable fix has finite valid coordinates, horizontal accuracy > 0 and <= 25 m,
nonnegative monotonic elapsed-realtime age <= 10 s, native speed in [0, 200] km/h,
and finite speed accuracy in [0, 2] m/s. Missing speed/accuracy is unusable for
these metrics; do not fabricate speed from a missing value. Reject non-increasing
elapsed times.
Preserve rejection counts/reasons and gap boundaries, not invented positions.

Connect usable fixes only within the same uninterrupted clock epoch, no more
than 10 s apart, with implied geodesic speed <= 200 km/h. Otherwise open a gap
and start a new baseline at the next usable fix, with no bridge distance or time.
For a usable pair, use geodesic endpoint distance; if both endpoint speeds are
<= 3 km/h, count zero distance to suppress stopped jitter. All such pair durations
are eligible time, including stopped pairs. The result is an estimate with visible
coverage/gaps, not motorcycle odometer distance. Use unrounded meters and elapsed
milliseconds for totals and round only for display.

Speed-change events are separate from retained quality samples. Record the first
usable speed in a continuous segment; record another when the absolute difference
from the last speed event is **>= 5 km/h** in either direction. Example: baseline
40, then 44 (no event), 45 (event/new baseline), 41 (none), 40 (event).
Gaps reset the baseline. Threshold is fixed for initial V1; internal versioned
configuration must be captured with the ride so later tuning never changes history.
Speed events must not determine which distance/time samples survive.

Daily average = sum of valid segment distance / sum of eligible segment duration
across rides, including stopped time; do not average ride averages. A synthetic
10 km / 30 eligible minutes is 20 km/h even if 15 minutes were stopped. Positive
eligible time with zero distance yields 0; zero eligible time yields unavailable.
Gap time, app downtime and invalid fixes are excluded and coverage is shown, so
“includes stopped time” never turns missing GPS into measured stopped time.

## Automatic and manual lifecycle

Automatic mode is opt-in and explicitly armed from the foreground. Use one
persisted active ride enforced transactionally; repeated callbacks/start/stop
operations are idempotent. Manual start creates a manual-owned ride that automatic
idle detection cannot end. Manual start during an automatic ride takes ownership
of that same ride. Mode changes never fork an existing ride.

**Sustained-movement default:** usable speed >= 6 km/h spanning at least 15 s,
at least four usable fixes, consecutive gaps <= 6 s. A lower speed, unusable fix
or larger gap resets the candidate. Consecutive pairs must also satisfy the
segment plausibility/clock rules above. At qualification create the automatic ride
at the first candidate fix and commit its buffered samples exactly once. Example:
usable samples at 0/5/10/15 s all at 6 km/h start once at 0 s; one at 5.9 km/h
resets the window. A single position jump or brief movement does not qualify.

**Idle-stop default (separate criterion):** an automatic-owned ride stops at the
first usable sample for which continuous usable speed <= 3 km/h spans at least
300 s, with consecutive gaps <= 10 s. Speed > 3 km/h, an unusable fix or a larger
gap or implausible/clock-discontinuous pair resets the idle window; missing GPS
is not idle. Include that measured idle tail in the ride and daily denominator.
A 299-second stop does not end it.
After automatic stop remain armed; a new sustained window can create a new ride.
Manual stop ends either ride and disarms automatic detection until explicit re-arm.
Manual tracking remains available with auto unavailable; permission denial allows
saved-data management and shows why metrics cannot be recorded.

Use a visible location foreground service/notification with Stop while recording
or armed. Start/arm requires foreground interaction and granted precise location;
approximate-only permission cannot arm auto and shows limited/unavailable manual
metrics. Request notification permission where supported and show its status.
No always-on background-location permission, silent boot start, periodic worker
or battery-exemption demand in initial V1. Armed monitoring continues while the
user-started service can legally run; it is not a promise to auto-start after reboot.
Android restricts location foreground-service starts from the background; follow
[location permission rules](https://developer.android.com/develop/sensors-and-location/location/permissions)
and [service prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types#location).

Persist samples, reducer state and clock/algorithm version in bounded transactions
at most five seconds apart; acknowledge start/stop only after commit. A service
restart may recover the same active ID when Android permits it, but any unobserved
interval is a gap. A reboot, force-stop, revoked permission or failed recovery
leaves an interrupted ride recoverable in the app. Offer Resume same ride or End
at last persisted observation. No new ride or imputed distance/time on reopening.
Resume takes manual ownership with auto disarmed; re-arm auto explicitly after
interruption. Never connect monotonic clocks across reboots. Disk/write failure stops capture visibly; preserve committed records and
identify the possible uncommitted tail (up to five seconds in normal operation).

## Timezone, midnight and stopped-use controls

Persist instants, elapsed time and the device zone/offset observed for each sample;
service/fuel date-only records retain their entered local dates. Daily allocation
uses the recorded device-local zone, not today's zone when history is viewed.
Split each eligible segment at local midnight and zone-change boundaries;
apportion its distance by elapsed fraction, allocate duration exactly, and leave
the ride ID unchanged. A zone change takes effect at the first sample reporting
it; earlier samples are not rebucketed. DST days use actual elapsed time, not an
assumed 24 hours. A wall/elapsed delta mismatch greater than two seconds creates
a gap before a new wall/monotonic anchor, preventing negative/duplicate durations.
Statistics show partial coverage.

Example: a valid 10-second segment from 23:59:55 to 00:00:05 allocates five
seconds and half its distance to each day, within one ride. Multiple rides in a
day sum segment numerators/denominators. Midnight never auto-stops tracking.

**Implementation default:** Start/Stop and Refuel are large, clearly labeled
controls (at least 56 dp targets), with recording/armed/interrupted state always
visible and accessible labels/text scaling. Refuel opens a short stopped-use form;
settings, registration, maintenance and detailed history are parked flows.
Motion-based interaction lockout is **proposed/deferred**, off and absent from
initial V1: GPS loss/jitter must not trap Stop or data recovery behind a lock.
No interaction while moving is required for field validation. An optional future
lockout needs a separate usability decision and must preserve Stop access.
