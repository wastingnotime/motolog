# Planning gate and unresolved decisions

## Current repository baseline

The inspected setup contains documentation, license and ignore rules. There are
no Android modules, UI/data conventions, persistence or tracking implementations,
or app tests to extend. The [manual review and partial rule inventory](../../references/fazer-ys250-blueflex-maintenance.md)
verifies source identity and selected schedule facts. Exact applicability to the
MotoLog bike remains pending, and no maintenance intervals are seeded.

The [Android surface inventory](implementation-surfaces.md) records the inspected
commit, actual files, greenfield slice map and a separate scaffold proposal.

Before app work, inspect the then-current checkout and instructions, identify
existing modules and test conventions, substantiate manual data privately, and
present a repository-grounded implementation plan. Map each slice to concrete
code and verification steps. Owner acceptance of that plan and explicit direction
are required before implementation. These slices do not dispatch work or choose
an Android stack.

## Decisions to resolve

| Area | Questions and proposals; not accepted defaults |
| --- | --- |
| Manual and registration | Source edition/hash and selected facts are verified in the linked review. Confirm this bike's model/variant/BR specification and manufacture year separately from model year; establish starting odometer, time anchor and prior-service baseline independently. Propose historical snapshots of completed rules and provenance. |
| Maintenance recurrence | Preserve the reviewed first-service marks, replayed odometer milestones, independent replacements and event/condition triggers. Decide actual-completion vs fixed-milestone anchors where the source leaves them unresolved, calendar origins, supported postponement targets, and interaction between outstanding work and later revisions. No universal 5,000-km recurrence or invented service anchors. |
| Fuel and odometer | Confirm currency, locale, rounding, validation and backdating; propose preserving entered values and deriving the third fuel value. Decide how a fresh odometer reading is supplied and its age shown. Propose current minus last-refuel odometer only when chronologically valid. |
| Active ride | Propose one persisted active ride shared by manual/automatic modes. Resolve speed-change threshold units, comparison baseline and configurability, GPS sampling/quality, automatic movement/idle criteria, manual override and interruption recovery. |
| Daily statistics | Decide midnight session splitting vs daily aggregation and timezone. Propose valid distance divided by eligible tracked time; decide whether stopped time counts and handle zero duration explicitly. |
| Android | Choose storage, minimum Android version, permissions, background tracking and battery tradeoffs after repository inspection. Motion lockout remains undecided. |

## Candidate slices and acceptance intent

1. **Local foundation and registration.** In airplane mode, register the supported
   bike and retain it across restart. Trace each seeded rule to its verified
   source. Modified/owner-added items display **Custom** and retain source and
   baseline. Missing verified schedules remain visible gaps, without sample intervals.
2. **Maintenance.** Check exact mileage and time thresholds independently. Full
   completion of preventive and programmed work clears the occurrence and writes
   history. Full postponement completes nothing. Partial completion preserves
   performed items and links outstanding items with new targets, including retries.
   Programmed postponement leaves tracking available. Corrective history retains
   every required field after restart and offers no editing controls.
3. **Fuel and odometer.** Save equivalent events through both modes; verify derived
   values, distinct labels and history. Invalid inputs get clear feedback; retries
   do not duplicate events. Dashboard calculations use appropriate odometer
   readings and expose missing data.
4. **Manual tracking and dashboard.** Start/stop offline. Deterministic location
   samples test distance, speed changes on both sides of the agreed threshold and
   daily statistics. Denied permissions and lost fixes preserve saved records
   across restart and expose gaps.
5. **Automatic tracking and field validation.** Verify accepted start/stop and day
   boundary rules, including midnight crossings. Interrupted tracking cannot
   duplicate sessions, time or distance. Validate quick controls while safely
   stopped and management flows while parked.

WNT tasks own sequencing, dependencies, current status and acceptance evidence.
Future technical decisions belong in these repository docs once accepted.
