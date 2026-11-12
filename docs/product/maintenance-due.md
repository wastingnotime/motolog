# Maintenance occurrences and due evaluation

Implementation design recorded before the due engine. Read with the
[accepted decisions](decisions.md), [registration](registration.md) and
[reviewed facts](../../references/fazer-ys250-blueflex-maintenance.md).
These are internal Android models, using synthetic examples only.

## Admission, selection and uncertainty

Only registered, applicability-confirmed manual rules or explicit Custom intervals
are evaluated. Typed support is bounded: sourced oil replacement and separate oil
filter replacement use fixed mileage; sourced replacement intervals use qualifying
completion anchors. Other milestone inspections remain manual review. A Custom
milestone edit needs its own design; text is never parsed into a schedule.

On first evaluation with a confirmed owner reading, fixed mileage adopts the latest
reached mark (or 1,000 if below first service), plus the next future mark. At 20,800,
oil therefore starts with 20,000 and 25,000; earlier visits remain **history unknown**,
not fabricated missed work or completion. With no reading, the known 1,000
first target is informational and unadopted: expose unknown reached status but do
not persist an obligation or a mileage frontier. The first real used-bike reading
then establishes the frontier using the rule above, without backfilling earlier
marks. Genuinely adopted or explicitly known obligations remain durable; they are
distinct from this placeholder. Once adopted, marks are persisted. Subsequent evaluations enumerate every crossed mark and the next
future mark from that retained frontier. A late first reading cannot delete an
already adopted first service. A no-reading setup followed by a synthetic
61,873-km first reading adopts oil 60,000/65,000 and filter 60,000/70,000,
without claiming the earlier services were missed. Mileage below 1,000 never produces a zero mark;
1,000 does not repeat. Oil repeats absolute multiples of 5,000; filter repeats
multiples of 10,000. At 15,000 there is no filter replacement target.

Each obligation has a stable identity, baseline link, revision-specific cycle key,
immutable rule/source snapshot, original/effective targets and optional anchor
history link. Template edits affect new selections prospectively. Old pending work,
including retired revisions/actions, remains visible. Related obligations share the
baseline work group but keep separate identities; the UI shows one work group and
all its open targets. Programmed work has its own identity and optional targets;
no target means open/unscheduled, never completed. Carried remainders retain a
parent link; a postponed parent with a linked remainder is represented by that
remainder, not a second copy of the same work.

## Qualifying evidence and calendar branches

Only confirmed performed history with explicit qualifying evidence and matching
action, component and source/revision snapshot can be selected as an anchor.
Owner/import notes, inspection, purchase or ambiguous hydraulic work cannot qualify
implicitly. Anchor admission explicitly names the baseline and one current physical
completion, using an expected prior anchor revision. All recurrence dimensions come
from that single completion. Never combine a newer completion date with an older
completion odometer: a newer date-only replacement makes the mileage branch unknown,
even if the older replacement has a known odometer. Fixed mileage grids remain
independent of this recurrence anchor.

Anchor choices are append-only and ordered by explicit admission revision. A known
later completion date may supersede a known earlier one; a backdated completion
cannot silently displace a later anchor. Equal/missing dates make physical ordering
uncertain and require explicit owner reconciliation before switching the current
anchor. Import receipt time, row IDs and maximum odometer never settle that order.
Qualification itself does not satisfy old adopted obligations; those require the
explicit reconciliation below. Unknown dimensions create unknown branches, never
manufacture/acquisition/zero defaults.

Simple intervals add distance/months to actual qualifying completion dimensions.
The next interval is not generated repeatedly while its same anchored obligation
is still open. Month arithmetic clamps to the last valid day; local due dates are
inclusive. Two thresholds use OR; one threshold stands alone. Missing a required
anchor or current reading makes that branch unknown. Known due wins over unknown;
below a known threshold plus an unknown branch does not mean not due.

Oil has a separate six-calendar-month branch, anchored only by explicitly qualified
oil completion dates. An unknown calendar origin remains unknown while mileage
continues to evaluate. The time obligation and mileage obligations are shown in
the same work group. Filter has no calendar branch. Completion may explicitly
satisfy both reached branches with the same performed action, but never implicitly
satisfies a future mileage target or shifts its grid. An early/time-only oil change
can anchor the next calendar branch without completing a future mileage mark.

## Reconciliation and TASK-16 handoff

A completion explicitly selects obligation IDs and expected occurrence revisions.
One matching performed-action snapshot can satisfy multiple reached obligations of
the same rule/action, including a postponed older mark and a later mark or calendar
branch. Preserve a separate immutable satisfaction link and value snapshot for every
selected obligation. A transaction validates all selections before changing any;
a stable operation ID makes retries exact no-ops. An already satisfied obligation
cannot be completed again. Oil cannot satisfy filter, fluid cannot satisfy hydraulic
parts, and partial work changes only the selected action obligations. Historical
setup completion with no selected obligations proves no earlier mileage visits.

For example, oil's 20,000 obligation performed at 20,800 leaves 25,000 next.
Separately confirmed filter work for 20,000 at 20,800 leaves 30,000 next. If an
older 15,000 oil obligation is still open, it remains alongside 20,000 until the
owner explicitly reconciles both. No evaluation, template edit or later completion
silently closes older work. An unselected older calendar obligation likewise stays
open; it is explicitly reconciled with later work rather than superseded.

Postponement changes effective targets only, with revision audit/reason. Original
reached status remains visible independently from effective status. A postponed
20,000 target at 26,000 coexists with the 25,000 obligation; a shared work group
prevents representing them as two required physical services. Full/partial UI
commands and stricter target-advancement validation belong to TASK-16; this slice
supplies persistent obligations, qualification and explicit atomic reconciliation.
Partial remainders retain their original snapshot and parent chain.

## Quantified condition branches and coverage

For source-verified brake hoses (48 months), air-filter replacement (nominal 40,000
km), and complete chain care (500 km), evaluate the numeric branch from qualifying
anchors. At/after the numeric target it is actionable even though condition/event
observations remain manual review. Below it, retain manual-review/unknown condition
status. Missing numeric anchors stay unknown. Never infer rain/washing, damage,
dust/humidity or a shorter interval. Custom condition rules are unsupported pending
a separate design. Keep coverage notes/source locators and review status even when
numeric work is due or completed. This does not expand the verified inventory or
claim whole-bike currency. Unsupported rules always appear in the projection.

## Persistence and verification

Append-only obligation/anchor/satisfaction metadata accompanies existing occurrence
and history rows. New tables migrate without rewriting previous rows or operation
receipts. Selection, reconciliation and their metadata commit in Room transactions;
re-evaluation after restart cannot duplicate cycles. Deterministic unit tests cover
numeric/date boundaries and selection; connected tests cover registration admission,
reopen, retries, edits, overlap, explicit partial reconciliation, provenance failure,
rollback, migration and separate-process restart. Regressions include an older complete anchor followed
by a newer date-only replacement, and no-reading registration followed by the first
real used-bike reading without fabricated historical obligations. No physical-owner validation is
claimed by emulator checks.
