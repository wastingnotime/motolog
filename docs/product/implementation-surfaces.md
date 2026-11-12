# Android implementation surface inventory

## Inspection baseline

Inspected on 2026-11-12 at commit
`152a607b0c36b8502b110b857f8bc405b4d9ce1c` in an isolated checkout on
`codex/task-10-af2ee4f2`. Canonical identity is
[`wastingnotime/motolog`](https://github.com/wastingnotime/motolog), with origin
`git@github.com:wastingnotime/motolog.git`. GitHub repository metadata confirmed
that identity, public visibility and the `main` default branch. Repository access
is established; earlier failed resolution is superseded by this inspection.
This inventory describes that commit, not an Android implementation.

Explicitly read [AGENTS.md](../../AGENTS.md), then [README.md](../../README.md),
[V1 requirements](v1.md), [planning](planning.md) and
[reference publication rules](../../references/README.md). These require
Android-only, local-first operation for one motorcycle, distinct odometer/GPS
authority, preserved completed history, and private handling of source manuals
and owner records. The full MPL-2.0 license is in [LICENSE](../../LICENSE).
Implementation requires the grounded plan and owner direction described in the
planning gate; this inspection does not resolve open choices.

## Actual files and reusable material

The inspected commit has exactly seven tracked files:

| File | Reusable surface |
| --- | --- |
| `AGENTS.md` | Repository boundaries, instruction entry point and public-content rules. |
| `README.md` | Product identity, scope and documentation navigation. |
| `docs/product/v1.md` | Confirmed domain requirements, source authority and offline behavior. |
| `docs/product/planning.md` | Candidate slices, verification intent and unresolved decisions. |
| `references/README.md` | Admission criteria for manual-derived facts and publication limits. |
| `LICENSE` | Repository MPL-2.0 license. |
| `.gitignore` | Android build/IDE/key and private-input guards; these do not prove an Android project exists. |

There is no reusable application code in this checkout. Reuse the requirements
and provenance rules when designing the app; no sibling product internals are
an established MotoLog dependency. No runtime/API/data-format contract is exported.

| Area | Observed implementation surface and gap |
| --- | --- |
| Android modules | None. No settings/build files, Gradle wrapper, manifest, Kotlin/Java sources, resources, package/application ID or SDK declarations. |
| UI | No screens, navigation, theme, components, assets or UI framework convention. V1 specifies large stopped-use controls and parked management flows only. |
| Storage | No database, schema, migrations, repository layer, serialization, backup or import implementation. Offline persistence, stable bike identity, provenance and atomic partial completion still need design. |
| Tracking | No location provider, permissions, service, background/restart handling, active-ride state or GPS calculations. Odometer and GPS authority are specified only in the product brief. |
| Tests | No unit, instrumentation, UI or fixture directories, test framework or runner. Planning contains acceptance intent, not executable checks. |
| Build and CI | No build/test scripts, dependency catalog, wrapper, CI workflows or release/signing configuration. No runnable app build, lint or test entry point exists at this commit. |
| Manuals and seeds | No source manual, derived inventory, seed data or verified maintenance baseline is tracked. A privately supplied PDF is available; its presence and byte hash were checked locally. Applicability, table alignment and schedule semantics were not verified in this inspection. |

Private manual locators and checksum evidence remain in local task records.
Availability of a source does not establish its applicability to the owner bike.
Manual verification must produce the original factual summaries and per-rule
locators permitted by `references/README.md`; until then schedules stay unseeded.

## V1 slice map

Every slice is greenfield at this revision. The existing file mappings below
refer to requirements, not working features or selected future modules.

| V1 slice | Actual requirement files | Missing implementation and verification surface |
| --- | --- | --- |
| 1. Local foundation and registration | `v1.md`: Registration and maintenance sources; `planning.md`: slice 1 | Bike identity, persistence, preset and editable sourced templates. Add offline/restart checks, baseline/override provenance checks and explicit unverified-schedule states. |
| 2. Maintenance | `v1.md`: Maintenance behavior; `planning.md`: slice 2 | Due evaluation, pending occurrences, full/partial completion, postponement and corrective history. Verify exact mileage/time boundaries, atomic linked outcomes, retry safety and history preservation after restart. |
| 3. Fuel and odometer | `v1.md`: Fuel and measurement authority; `planning.md`: slice 3 | Both entry modes, derived values, history and current-reading selection. Verify equivalent events, invalid input, duplicate submission, chronological validity and missing-data states without GPS substitution. |
| 4. Manual tracking and dashboard | `v1.md`: Rides and dashboard; `planning.md`: slice 4 | Start/stop UI, permission flow, location recording, persisted ride and daily calculations. Add deterministic sample tests, offline/restart, permission denial, lost fixes and interrupted tracking checks. |
| 5. Automatic tracking and field validation | `v1.md`: Rides and dashboard; `planning.md`: slice 5 | Automatic detection, manual override, background recovery and day allocation. Verify accepted movement/idle rules, midnight behavior, no duplicate sessions/time/distance, and physical-device stopped/parked flows. |

Before naming implementation files, the execution plan must resolve storage and
Android choices plus recurrence, fuel precision/backdating, location quality,
speed thresholds, eligible time, automatic detection and day-boundary semantics.
Any owner decisions recorded in coordination must be reconciled into the product
decision log by the planning work; this inventory does not adopt defaults.

## Separate Android bootstrap proposal

Repository creation is complete. A separate Android scaffold is needed before
feature slices can run. The following is a proposal for the execution plan,
not authorization to create code or a commitment to a framework:

1. Record the chosen language/UI/storage approach, application ID, minimum and
   target Android versions, toolchain versions and device/emulator matrix. Keep
   feature-specific open decisions explicit rather than embedding assumptions
   in the scaffold.
2. Propose one Android application module at `app/`, with root
   `settings.gradle.kts`, `build.gradle.kts`, a pinned Gradle wrapper and
   `app/build.gradle.kts`. Add `app/src/main/AndroidManifest.xml` and the selected
   source/resource layout. Confirm these paths in the accepted plan before use;
   none exists at the inspected revision. Add module splits only for demonstrated
   needs. Do not create backend, web, MCP or shared-contract surfaces.
3. Add a minimal launchable shell and synthetic smoke fixture, then establish
   local unit-test and Android instrumentation entry points. Prove offline
   launch and restart on the agreed matrix before starting product features.
   Keep real owner setup/import and verified template seeding separate.
4. Document and verify the scaffold's actual commands, SDK/JDK prerequisites and
   device setup in the README. For the proposed module layout, candidate checks
   are `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` and
   `./gradlew :app:connectedDebugAndroidTest` on an agreed emulator/device.
   These are future checks, unavailable here. Add CI for the accepted commands
   without committing signing secrets or machine-specific SDK paths.

Scaffold acceptance should name the resulting files, pinned tooling, executable
checks and device evidence. Manual verification and the repository-grounded
decision/implementation plan remain separate producers; inspection alone does
not make feature work ready.

## Inspection verification

`git rev-parse HEAD`, `git remote -v`, `git branch --show-current` and
`git worktree list --porcelain` established checkout identity and isolation.
`git ls-tree -r --name-only HEAD` and
`rg --files --hidden -g '!.git'` agreed on the seven-file baseline; the initial
worktree was clean, with no additional supplied code or manuals. GitHub metadata
confirmed the canonical public repository. Build and app tests could not run
because their entry points are absent, rather than because of a test failure.
