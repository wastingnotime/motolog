# Local persistence foundation

The Android foundation follows the accepted [execution plan](execution-plan.md).
It provides internal Room storage for feature slices. The
[registration slice](registration.md) now creates the single bike, admits verified
manual facts after owner confirmation and supports reviewed local setup. No owner
history is bundled. The [maintenance slice](maintenance-due.md) now supplies due evaluation,
persistent obligation selection and explicit reconciliation. Fuel forms and location
capture remain downstream work. These are private app internals, not exported contracts.

## Storage and authority

`MotoLogApplication` owns the database/store. Open it on an IO dispatcher; the
synchronous `LocalStore` API intentionally uses Room's transactions and rejects
main-thread access. Feature callers should use `withContext(Dispatchers.IO)`.
The app requests no runtime or network permissions and has no network client.
AndroidX adds only its app-internal signature permission for receiver isolation.
Backup is disabled and both cloud extraction and device transfer exclude all app
storage domains. Private data stays in app-private storage; uninstall clears it.
No import file or manual is bundled.

| Rows | Contract |
| --- | --- |
| Bike | Caller-generated stable ID, unique singleton slot 1; unknown identity fields nullable. Registration admits the preset and confirms manual applicability explicitly; match confirmation and baseline insertion are atomic. |
| Baseline / OwnerOverride | Append-only revisions with separate identities; overrides retain the baseline link and use Custom provenance. Revision IDs must be stable per rule revision. Unknown schedules/coverage remain explicit; no interval is inferred. |
| OdometerReading | Owner or owner-import evidence only; whole nonnegative km, nullable if unknown. Confirmed readings require an observed instant and value, and must agree with both chronological neighbors. Uncertain readings/notes survive without advancing authority. |
| MaintenanceOccurrence / OccurrenceRevision | Original targets/rule snapshot are fixed. Effective targets/state change with compare-and-set revision increments; every change archives prior effective targets/state and change reason. Parent IDs link remainders. Completed occurrences cannot reopen. |
| MaintenanceHistory / HistoryAnnotation | Copied action/component, source identity/edition/locator and revisions, original/effective targets, date/zone, reading certainty, optional exact cost/location and performed work/replacements/anchor evidence. History is append-only, including at SQL level; corrections are additive annotations. |
| PreventiveObligation / QualifyingAnchor / ObligationSatisfaction | Append-only baseline/cycle identity, selected single physical-completion anchor revisions and explicit many-obligations-to-one-performed-action links. Every satisfaction retains its own original/effective targets and selected revision alongside the immutable occurrence rule and performed history snapshots. Current recurrence cannot combine dimensions from different completions. |
| FuelEvent | Exact normalized total BRL and entered liters or unit price, entry mode, observed instant and optional linked owner reading. Linked reading must describe the same observation. Derived values are not saved over original operands. |
| RideSession | Algorithm version, receipt time and reducer checkpoint, manual/automatic ownership, observed start/end, revision. ACTIVE and INTERRUPTED reserve unique slot 1; ENDED releases it. Recovery keeps the same ID. Lifecycle algorithms and checkpoint serialization belong to tracking. |
| RideSample / RideGap | Quality samples, independent speed-event flag, sequence, clock epoch, elapsed ms, zone/offset, receipt time and raw unrounded GPS coordinates/speed/accuracy; rejected-fix reason/count and gap boundaries separately. Unique ride/sequence and ordered monotonic times within a clock epoch. No GPS field or method updates an odometer. |
| ImportRow / AppliedOperation | Stable batch/row identity, private source note/certainty and command fingerprint receipt. Registration provides reviewed local entry with duplicate candidates; no automatic private import or merge is performed. |

The single-bike constraint makes all linked records belong to that one stable
identity. Foreign keys restrict deletion. There is no replace/upsert or history
mutation API. The internal DAO is not a feature entry point. SQLite triggers guard
append-only evidence as well as the singleton slot; retain these in future migrations.
Nullable cost differs from zero. Missing dates, intervals, readings and source
locators remain absent; `UNKNOWN`/`UNCERTAIN` do not claim confirmation.

## Atomic commands and retries

Call `LocalStore.write(operationId, WriteBatch(...), recordedAt)` with a durable,
caller-generated operation ID for the user's command, not a new ID on each retry.
IDs for every row must also be stable. One transaction validates and writes the
entire batch, state changes, previous occurrence revisions and operation receipt.
An exception leaves none of those changes committed. A replay with identical
ordered content returns false; reusing the ID with different content throws
`OperationConflict`. Receipt time is metadata and excluded from the fingerprint.
Concurrent copies serialize under SQLite transactions and commit once.

Durable writes query only touched records. Confirmed readings use indexed strict
predecessor/successor lookups plus an equal-time peer check, including other rows
inserted by the same transaction. Uncertain readings do not enter those queries.
Samples use the unique ride/sequence index for neighboring samples; closing a ride
uses an indexed EXISTS lookup beyond the proposed end. Start is immutable, so
ordinary checkpoints do not read sample history. Restart `snapshot()` remains a
separate, explicit full read. `WritePathTest` records executed SQL against 30
historical rides/300 samples and checks SQLite EXPLAIN plans for index use without
history scans or temporary sorting; it also covers backdated/equal-time readings,
unsorted batches, sample neighbors and rollback of an invalid ride end.

The SHA-256 fingerprint uses an explicit length-delimited, type-tagged field order;
list order is significant. Keep this encoding and enum/type identities compatible
when evolving commands, or version it with replay support. Do not regenerate a
failed command with changed IDs under the old operation ID. Import uses a stable
import ID plus unique row IDs in the same command as its confirmed records; a
second operation cannot insert that row again. Uncertain duplicate matching needs
the registration slice's review flow.

Occurrence/ride updates require `revision = old + 1`; stale writes fail atomically.
Partial completion callers put performed snapshots, linked remainder and occurrence
updates in one batch. Full postponement includes no history. This layer supplies
the transaction primitive; checklist membership, target advancement, required
corrective form fields, occurrence reconciliation and anchor qualification must be
validated by the downstream domain commands before constructing a batch. A batch
alone is not a maintenance engine or evidence that a manual rule applies.

## Precision and time

Entered decimals normalize with `DecimalValues.normalize`: no exponent, signs,
commas or binary floating point, at most 12 integral digits; total BRL/cost at most
two decimal places, liters/BRL per liter at most three. Inputs must be positive
except a known service cost may be zero. Persist canonical strings without redundant
zeros. Division uses scale 12, HALF_UP; only display rounds to two/three places.
Odometer km are whole numbers in 0..999999999999. GPS samples retain Double meters,
coordinates and m/s measurements independently; no rounded display value is an anchor.

`Observation` stores UTC epoch milliseconds, IANA zone ID and the offset observed
at that instant, checked for agreement. `recordedAt` is a separate UTC epoch-ms
receipt time, never a substitute for an unknown observation. Service/target dates
use ISO local `yyyy-MM-dd`; service date zone is separate and nullable together
with an unknown date. No date-only record is converted to a fabricated instant.
Rides retain algorithm version; samples also retain monotonic elapsed milliseconds
and a caller-managed clock epoch that changes after reboot. Distance, eligible-time
and day-splitting calculations belong to tracking and use the decision log's gap
rules; storage never interpolates missing measurements. Future-event validation,
freshness and source admission belong to their domain commands.

## Schema evolution and verification

Versions 1, 2 and 3 are exported in `app/schemas/`. Version 1 is the first schema;
there is no invented v0-to-v1 migration. Version 2 adds chronology and ride-time
indexes through the explicit `MIGRATION_1_2`. Version 3 adds the three append-only
maintenance metadata tables via `MIGRATION_2_3`, preserving prior rows and receipt
fingerprints. Empty new metadata groups are omitted from legacy command encoding;
older commands remain replay-compatible. `SchemaTest` validates both 1→3 and 2→3
paths, plus the initial schema, and migrates synthetic records in every prior table,
comparing all persisted
values (including nulls, source snapshots and operation receipts), then verifies
append-only guards and a production reopen. It uses
[Room's MigrationTestHelper](https://developer.android.com/training/data-storage/room/migrating-db-versions).
Production reopen/round-trip tests exercise the actual callback-created triggers.
Unsupported database versions fail without destructive fallback; a test verifies
that rows remain intact. Future changes must bump the version, register explicit
migrations, preserve trigger semantics and operation receipts, and add old-to-new
data-preservation checks against every supported exported schema.

`LocalStoreTest` covers representative reopen/provenance/null round trips,
late-failure rollback of a partial completion, exact replay and conflicting retry,
concurrent retry, stale revisions, append-only history, linked imports, singleton
bike/active ride and separate GPS/odometer authority. `RestartProbeTest` also accepts
instrumentation argument `phase=seed` then `phase=verify` in separate processes;
the verify phase checks exact synthetic data and receipt replay after force-stop.
The runtime INTERNET permission is denied in that probe, so successful storage
cannot depend on network access. Unit tests check exact decimal and zone behavior.

The approved pins are in `gradle/libs.versions.toml`. An explicit serialization-core
1.8.1 dependency aligns the app runtime with Room 2.8.5's migration helper:
otherwise instrumentation consistent resolution selected core 1.7.3 alongside
JSON 1.8.1 and produced an AbstractMethodError. This is a verified compatibility
adjustment, not a change to the chosen storage library. The
[upstream serialization release](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.8.1)
and Room dependencies identify the version used.

`MaintenanceTest`, `MaintenanceFlowTest` and `MaintenanceRestartProbeTest` exercise
fixed grids, anchor ordering/missing dimensions, source admission, persistent
unknowns, pending/remainder grouping, explicit reconciliation, UI coverage and
separate-process offline restart. Reconciliation accepts an optional `AnchorChoice`
in the same transaction; TASK-16 should use it for qualifying completions, and
compose multi-action outcomes in an enclosing Room transaction. The read model
separates current recurrence from retained obligations requiring reconciliation.
