# Repository-grounded V1 execution plan

## Inspection and direction

Inspected on 2026-11-12 at baseline
`0128d374735b12c1c4f2086f7b0f345e1e587744`: nine tracked files, documentation,
license and ignore rules only. There is no Android module, wrapper, manifest,
source, database, test suite or CI to extend. The earlier
[surface inventory](implementation-surfaces.md) remains historical evidence;
the [manual review](../../references/fazer-ys250-blueflex-maintenance.md) now
supplies verified partial facts, with applicability still unconfirmed.

Owner direction and its exact scope are recorded in the [decision log](decisions.md).
The owner authorized the planned V1 and en-US/ride defaults. Stack libraries and
Android/toolchain pins below are implementation defaults selected under delegated
execution direction, not exact choices explicitly made by the owner. This plan
makes the product direction concrete. Bootstrap is required first, in the separate
foundation task. This documentation change creates no app code and dispatches no
implementation. Preserve one bike, offline authority and all excluded surfaces.

## Selected foundation defaults

As implementation defaults, use one application module **`:app` at `app/`**,
Kotlin, Compose Material UI and Room/SQLite with app-private transactional storage. Namespace/application ID:
`org.wastingnotime.motolog`. Packages below are internal organization, not separate
Gradle modules or shared WNT contracts. No sibling source dependency is required.

Pin these compatible versions in `gradle/libs.versions.toml` during bootstrap;
no dynamic versions. These are deliberate reproducible pins, not a claim to be
the latest release. Resolve other direct dependencies to exact compatible versions
and record them in that catalog before accepting foundation.

| Tool/component | Selected pin and basis |
| --- | --- |
| Gradle / AGP | Gradle 8.13, AGP 8.11.1. [AGP compatibility](https://developer.android.com/build/releases/agp-8-11-0-release-notes) requires Gradle 8.13, supports API 36, and defaults to Build Tools 35.0.0. Pin wrapper URL and official distribution SHA-256. |
| Build JVM / bytecode | Use available JDK 21 to run Gradle; Java/Kotlin JVM target 17. AGP requires JDK >= 17; [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime) covers running Java 21 from Gradle 8.5. |
| Kotlin / Compose compiler | Both 2.2.21. [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) supports this Gradle/AGP pair; [Compose compiler setup](https://developer.android.com/develop/ui/compose/compiler) uses the Kotlin-matched compiler plugin. |
| Compose libraries | BOM 2025.10.01, verified in [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.01/compose-bom-2025.10.01.pom); compiler is pinned separately. |
| Persistence / processing | Room 2.8.5 and [KSP 2.2.21-2.0.4](https://github.com/google/ksp/releases/tag/2.2.21-2.0.4). [Room release notes](https://developer.android.com/jetpack/androidx/releases/room) specify API 23 minimum and AGP >= 8.4 for its plugin. Export schemas and test migrations. |
| Android SDK | minSdk 26 (Android 8.0), compileSdk 36, targetSdk 36, Build Tools 35.0.0. API 26 simplifies platform time/location APIs; actual owner-phone compatibility is a field-validation input, not assumed proven. |

Local read-only inspection verified JDK 21, cached Gradle 8.13, installed API 36
platform and a connected API 35 emulator. SDK installation paths/device serials
remain private. Available platforms do not establish installed test images or
successful builds. Initial dependency resolution may need network; runtime core
flows must work offline. A toolchain incompatibility must be resolved and recorded
in foundation evidence before features, not hidden by floating upgrades.

Keep Room relational rows for stable bike identity, versioned source baseline and
overrides, confirmed/uncertain odometer observations, maintenance occurrences and
completion snapshots, fuel operands, ride/session/quality samples and operation
IDs. Use unique constraints for operations/import rows and the single active ride.
Null/uncertainty/status are explicit; zero is not a missing value. Money and entered
decimals use exact normalized representations; timestamps store observation and
recording separately with zone provenance. Store algorithm versions with rides.
See the decision log for units and precision. Schema evolution must preserve saved
records with exported schemas and migration checks; no destructive migration fallback.
Disable cloud backup/device-transfer inclusion of the database and private import
inputs; no analytics, network permission or backend is required for core flows.

Room gives atomic partial-completion/retry behavior and queryable history; it costs
schema/migration work. Persist full usable quality samples separately from speed
change events so distances/durations remain reproducible. Keep rejected-fix counts
and reasons, not raw rejected tracks. Batch commits up to five seconds, acknowledging
state transitions after durable writes. No automatic pruning of ride history in
initial V1; show storage usage and measure growth during field validation before
choosing retention. High-rate GNSS and continuous armed mode consume battery;
5-second armed sampling and explicit disarm bound the tradeoff. Measure screen-off
consumption, sample gaps and storage per hour on the owner phone; no battery-life
promise or device-wide settings changes follow from emulator tests.

## Bootstrap files and verification entry points

Foundation must first create `settings.gradle.kts`, root `build.gradle.kts`,
`gradle/libs.versions.toml`, checksum-pinned wrapper files and `gradlew`/`gradlew.bat`,
`app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
`app/src/main/java/org/wastingnotime/motolog/MainActivity.kt` and `res/`.
Add only a launchable Compose shell before persistence/features. Root SDK paths
belong in ignored local configuration/environment, never committed literals.

The following commands are required **after bootstrap**, unavailable in this
planning-only checkout:

```sh
./gradlew --version
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Use a specifically selected app-owned emulator/device for connected checks and
record API/ABI/build evidence. Unit tests cover pure reducers/calculations;
`app/src/androidTest/` covers Room transactions, reopening, migrations and Compose
flows. Run a representative offline close/reopen flow after installation. Add CI
at `.github/workflows/android.yml` for build/lint/unit checks with the same pinned
JVM/SDK, and document connected checks separately. The foundation is not accepted
until the wrapper commands actually run; documentation alone cannot prove builds.

## Execution map and required evidence

All paths below are planned within the new module; none exists at this baseline.
Existing requirements remain in `v1.md` and `decisions.md`. Every slice runs the
build/lint/unit commands above and adds relevant connected checks; synthetic data
only may be committed. Coordination task IDs and live readiness remain local.

| Ordered slice | Concrete internal paths under `app/src/` | Verification evidence required |
| --- | --- | --- |
| 0. Bootstrap and persistence foundation | `main/java/org/wastingnotime/motolog/` with `MainActivity.kt`, `data/local/`, `data/repository/`, `domain/model/`; `app/schemas/`; `test/` and `androidTest/` | Launch offline; transactional rollback and replayed operation/import IDs; save/reopen representative bike, fuel, service and ride records; null/uncertain fields; distinct GPS and odometer sources; single active ride; schema/migration test harness. First schema needs reopen/round-trip tests; later schema changes require actual old-to-new migration tests. |
| 1. Registration and sourced templates | `main/.../feature/registration/`, `domain/maintenance/`, `data/repository/`; verified nonpersonal template metadata only if admitted | One stable bike after restart; explicit applicability confirmation before admission; unknown odometer/anchors allowed; **Custom** overrides preserve baseline; unsupported/manual coverage visible. Owner-private import preview/cancel/confirm/retry tested with synthetic uncertainty/duplicate fixtures. |
| 2. Maintenance due and pending work | `main/.../domain/maintenance/`, `feature/maintenance/`, `data/local/` | Simple intervals first, then required fixed absolute oil and separate oil-filter milestones before acceptance. Document occurrence/reconciliation design before coding; test first-service vs later replay, just-before/exact/after marks, late/partial completion, postponed overlap and unknown calendar anchors with known mileage. Late completion resets only interval anchors, never the mileage grid; oil calendar guidance never creates a filter calendar rule. Unquantified condition/event facts remain manual-review; no “all current” from partial coverage. |
| 3. Completion and postponement | Same maintenance packages and transactional repositories | Full, partial and no-completion postponement; absolute date/km/dual targets; original target and revision preservation; concurrent/retried saves; later-occurrence overlap; failed transactions leave neither half saved. |
| 4. Corrective entry and history | `main/.../feature/history/`, `feature/maintenance/`, snapshot models/DAOs | Required service fields, unknown cost distinct from zero, immutable value/provenance snapshots after template edits/restart; additive correction link; corrective work cannot complete unrelated replacement. |
| 5. Fuel and readings | `main/.../domain/fuel/`, `feature/fuel/`, `data/repository/` | Both entry modes, precision/repeating division and invalid inputs; duplicate save; chronologically neighboring readings, backdating, 24-hour freshness boundary and unavailable reasons; never substitute GPS. |
| 6. Manual ride and daily statistics | `main/.../domain/tracking/`, `tracking/LocationAdapter.kt`, `tracking/RideService.kt`, `feature/ride/` | Deterministic quality/threshold/gap traces; stopped denominator; exact 5 km/h event boundary; one active session under retries; offline screen-off service; revoked permissions/process loss/reboot and recovery; midnight, zone change, DST and wall-clock jump allocation. |
| 7. Dashboard and stopped-use navigation | `main/.../feature/dashboard/`, `feature/navigation/`, Compose resources | Large labeled controls/text scaling; recording/armed/interrupted states; stale/missing metrics; quick refuel while stopped and management while parked; histories remain read-only; Stop always accessible. |
| 8. Opt-in automatic tracking | `main/.../domain/tracking/` reducer and existing service/ride UI | 15-second/four-fix movement window at >= 6 km/h; separate <= 3 km/h, 300-second idle window; jitter/gaps reset windows; manual takeover/stop/re-arm; one ride across midnight; service interruption cannot duplicate time/distance. |
| 9. Field acceptance | Existing tests plus private validation report; sanitized outcome only in public docs | Reconcile each V1 requirement to commit/test/device evidence; owner-controlled stopped/parked usability and ordinary ride observations; battery/storage per hour and sample coverage; private import reviewed separately from template admission. Unsupported scope and residual defects stay explicit. |

Here `main/.../` abbreviates
`main/java/org/wastingnotime/motolog/`; it does not name another module.
The canonical shell paths above are the bootstrap requirement; detailed class
splits may change with evidence without creating shared contracts.

## Device matrix and unresolved-input gates

| Target | Purpose and availability |
| --- | --- |
| API 26 emulator | Minimum supported floor: launch, Room/reopen, permissions and service behavior. Image provisioning/verification still required before claiming floor coverage. |
| API 30 emulator | Older scoped permission/background behavior; SDK platform present, test image/run not yet established. |
| API 35 x86_64 emulator | Available now; bootstrap, offline flows, notification/service restrictions, deterministic trace injection and process recovery. No emulator settings for other apps may be changed. |
| API 36 emulator | Target behavior and foreground-service regressions; platform installed, image/run still required before target coverage. |
| Owner physical Android phone, API >= 26 | Exact model/OS remains private and unresolved. Required for real GNSS, screen-off/OEM process behavior, touch usability, battery/storage and ordinary-ride validation before V1 field acceptance. |

Required decisions for bootstrap are resolved as implementation defaults. Unknown
owner readings/history and exact bike applicability defer to registration with
explicit missing-data behavior; they never block building an empty local store.
They **do** block adopting a particular manual seed or evaluating its unconfirmed
anchors. Simple intervals are an initial foundation increment, not the final
maintenance scope. The maintenance due slice owns the required oil/oil-filter
fixed-mileage design and implementation, including first-service/replay, late and
partial reconciliation, postponed overlap and explicit unknown-calendar handling.
It cannot be accepted without those capabilities and deterministic evidence.
Unknown calendar inputs must not suppress known mileage targets or imply not-due.
Unquantified condition/event automation, other inspection milestone designs and
motion lockout remain deferred/unsupported with visible coverage limits.
No consumer requiring unresolved semantics may become ready by treating them as
solved; the required due-slice design must resolve them before its implementation.
Existing field-validation work cannot be called accepted without the physical phone and owner-controlled evidence.

The execution plan does not move any implementation task to ready. Review each
consumer's actual scope against these gates and record the supported/unsupported
boundary in its local task before readiness. Dependencies, completion evidence
and any PR remain in WNT tasks; this public file records durable technical choices.
