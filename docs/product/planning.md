# Planning gate and remaining inputs

## Gate resolution

The [surface inventory](implementation-surfaces.md) inspected the documentation-only
repository. At baseline `0128d374735b12c1c4f2086f7b0f345e1e587744`, there are
still no Android modules or app tests. The
[manual review](../../references/fazer-ys250-blueflex-maintenance.md) verifies
selected source facts and explicitly partial coverage, not personal applicability
or completed-service anchors.

The owner's 2026-11-12 direction to proceed and accepted choices are recorded in
the [decision log](decisions.md). Routine implementation defaults are labeled
separately from owner choices. The [execution plan](execution-plan.md) maps the
actual empty implementation surface to a required first bootstrap, one `:app`
module, pinned tooling, internal packages, verification commands and device matrix.
The direction gate is satisfied; no repeated authorization question is required.
The initial Android shell and storage are now implemented; see the
[local persistence contracts](local-persistence.md). Feature behavior remains
bounded by this plan and the decision log.

## Resolved behavior and deferred inputs

| Area | Durable decision and remaining gate |
| --- | --- |
| Registration | Collect current owner reading/timestamp and qualifying prior-service evidence locally, allowing unknown values. Exact model/variant/market/model/manufacture year and manual match must be confirmed before admitting a template. Private history preview/import follows persistence; it is separate from seeding manual rules. |
| Maintenance | Supported simple intervals recur from actual qualifying completion with explicit anchors. Immutable completion snapshots and atomic partial outcomes preserve provenance. Postponement supports absolute date/km/both and preserves original targets. Simple intervals are an initial increment; fixed absolute oil and distinct oil-filter milestones plus unknown calendar-anchor handling must be designed/implemented before maintenance due acceptance. Unquantified condition/event facts remain visibly manual-review; no blanket current-maintenance claim. |
| Fuel | en-US, BRL, exact entered decimal operands, explicit derived rounding and chronological backdating checks. A confirmed current odometer is fresh for 24 hours with visible age; stale/missing/ambiguous values cannot produce a current dashboard result. |
| Ride | One persisted active ride; fixed >= 5 km/h event threshold from last recorded speed. Deterministic quality rules, >= 6 km/h for 15 seconds/four fixes to auto-start, <= 3 km/h for five minutes to auto-stop, manual takeover and interruption recovery are specified in the log. |
| Daily totals | Device-local midnight divides segments/totals, not rides. Sum valid distance / eligible tracked time including measured stops, excluding gaps. Zone, DST and clock-change behavior are explicit. |
| Android and UI | Implementation defaults, not explicitly owner-selected libraries/versions: Kotlin/Compose, Room, minimum API 26, target/compile API 36 and pinned compatible tooling. Large stopped-use controls; motion lockout deferred. Physical owner phone and battery/storage evidence remain field-validation inputs. |

These unknown personal inputs are not fabricated and do not block an empty
foundation or the missing-data flows. They block only calculations/template
admission that require them. Unknown calendar origins must be represented without
blocking known absolute mileage evaluation. Required oil/filter milestone design
and implementation belong to the maintenance due slice, not a permanent deferral.
Other unsupported scheduling kinds remain visible for manual review.

## Verification and readiness

Follow the execution map in order: bootstrap/persistence, registration, supported
maintenance due/pending work and atomic completion/postponement, corrective history,
fuel, manual recording/statistics, dashboard, automatic tracking, then field
acceptance. The map gives concrete future paths and commands, and separates unit,
Room/Compose connected checks and owner-controlled physical-device validation.
No Gradle/app checks can run in this planning-only checkout.

Before any consumer becomes ready, reconcile its task scope and acceptance with
these decisions, supported coverage and required producer evidence. Do not label
required milestone semantics resolved without design/test evidence or claim a
field/device check passed from an emulator result. WNT tasks own sequencing, live readiness, status,
private evidence and final acceptance. Worker exit alone never completes a task.
