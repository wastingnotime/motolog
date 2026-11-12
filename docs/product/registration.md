# Offline registration and sourced checklists

Registration now implements the first feature slice of the accepted execution
plan. It uses the existing Room schema and transaction receipts; there is no
network permission, bundled owner history or automatic private-file access.

## Bike and manual admission

The sole preset is YS250 / BlueFlex / BR / model year 2017. Manufacture year is
entered independently. The owner must explicitly confirm both the exact bike
specification and Yamaha U44CWDW0, second edition May 2017, before admitting the
reviewed facts. A manual-unconfirmed registration can omit manufacture year and
reading; it saves a stable generated bike ID without seeding manual rules.
Confirmation can happen later, atomically updating the match and inserting the
facts. Registering another bike fails without changing saved records.

A supplied initial reading requires whole kilometers and an explicit observed
ISO timestamp with offset. The “observed now” action fills the timestamp only
when invoked. Recorded time remains separate. Skipping the reading creates no
zero reading. Last-confirmed readings display their observation time and do not
claim current freshness; current dashboard calculations belong to later slices.

## Facts, missing coverage and owner changes

`FazerInventory` admits only the limited original facts in the reviewed
[reference inventory](../../references/fazer-ys250-blueflex-maintenance.md).
Each manual baseline includes source code, edition, hash and printed/PDF locator.
No historical completion or pending preventive occurrence is seeded. Hydraulic
cylinder and caliper replacement evidence remains separate, with actual component
scope required. Oil/filter and inspection milestones keep milestone semantics;
conditional/event care is marked for manual review. Their prose facts are not an
interval-evaluation engine. The maintenance due slice must add its required
structured mileage grids and reconciliation before due acceptance.

Unverified schedules remain absent from storage and visible in the coverage-gap
message. Partial coverage cannot establish that all maintenance is current.

Edits append an `OwnerOverride` linked to the original immutable `Baseline`.
Optimistic revisions reject stale edits. Any saved owner revision, including an
interval-only change, displays **Custom**, the owner override label, original
source/locator and original baseline. Owner-added items have an immutable
owner-source baseline and display **Custom** / **Owner**; subsequent edits follow
the same revision path. An edit does not rewrite imported performed snapshots.

## Owner-reviewed local setup

The setup screen accepts one local source row at a time. It defaults to an
unmapped note with unknown certainty. The preview shows the original note,
certainty, date, reading, cost, action mapping, duplicate candidates and counts
of records to be written. Cancel writes nothing; confirmation freezes that
preview into one atomic command with stable import/row/operation IDs. Retrying
the same command is a no-op. No file parser or bulk import format is exported.

- An unmapped note saves only private source evidence, without performed history.
- A reading preserves observation time, owner-import source and certainty.
  Confirmed readings need both value and timestamp and must respect chronology.
  Uncertain readings do not advance authoritative odometer state.
- Actually performed service requires explicit UI confirmation of performed work.
  Optional checklist mapping must identify the exact action and actual scope;
  ambiguous brake work, diagnosis, promises or purchases belong in unmapped notes.
  Missing date, odometer and cost remain null. Uncertain service supplies no
  qualifying anchor evidence. An oil mapping never implies filter replacement.
- Programmed work saves an open occurrence with no invented target, cost or
  completion. Original notes remain available alongside the checklist.

Matching source notes, service component/date/reading, reading observations and
open programmed actions generate review candidates. The app never merges them
automatically. Saving a separate candidate requires explicit duplicate review;
a new candidate appearing after preview requires refreshing the preview first.

Actual private history and pending jobs require owner entry/review on the intended
device. Emulator verification uses synthetic records only and is separate from
that personal import and from manual-template admission.

## Verification

`RegistrationTest` covers unknown setup/reopen, singleton rollback, manual
admission/idempotency, locators and coverage kinds, baseline retention, interval
and text edits, owner sources, immutable snapshots, preview/cancel/retry,
duplicate review/races, programmed jobs, historical observations, uncertain
anchors, chronology rollback and concurrent edits. `RegistrationFlowTest` exercises
registration, visible missing data, owner additions, interval overrides and local
preview/cancel/confirmation through Compose. `RegistrationRestartProbeTest` accepts
separate `phase=seed` and `phase=verify` instrumentation runs across force-stop,
checking bike identity, source baseline, Custom values, unknown history, pending
work and replay. It asserts that INTERNET permission is denied.
