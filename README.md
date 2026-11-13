# MotoLog

MotoLog is WastingNoTime's Android-only, local-first personal motorcycle companion.
V1 helps one owner record fuel and service, track rides, and see what needs
attention for one Yamaha Fazer 250 in the 2016/2017 target range. Exact model,
market and manual applicability must be verified before maintenance data is seeded.

The repository includes an offline stopped-use dashboard, quick refuel, parked
management navigation, paged ride details, Android registration, sourced editable
checklists,
reviewed local setup entry and atomic maintenance outcomes on transactional,
app-private Room storage. Product features follow the accepted [execution plan](docs/product/execution-plan.md). No
personal records are bundled; verified manual facts are admitted only after local
owner confirmation of exact bike/manual applicability. Owner direction is recorded
in the decision log.

## V1 scope

- Offline motorcycle registration and editable maintenance templates with provenance.
- Preventive, programmed and corrective maintenance, with pending work,
  postponement and a read-only history of completed work.
- Refueling through either liters and total paid or total paid and unit price.
- Manual ride tracking, optional automatic tracking, and a dashboard with explicit
  missing-data states and large quick controls for use while safely stopped.

V1 excludes accounts, rider profiles, multiple motorcycles, a general motorcycle
catalog, workshops, marketplaces, cloud sync, backend dependencies, iOS and web.
MotoLog is independent of Rastro. No backend, web or MCP application belongs in
this Android-only setup.

## Read next

- [Product requirements and invariants](docs/product/v1.md)
- [Planning gate, scope limits and remaining inputs](docs/product/planning.md)
- [Accepted decisions and implementation defaults](docs/product/decisions.md)
- [Grounded execution map, tooling and verification](docs/product/execution-plan.md)
- [Inspected Android surfaces and separate bootstrap proposal](docs/product/implementation-surfaces.md)
- [Source provenance and publication rules](references/README.md)
- [Reviewed manual facts, partial coverage and applicability gaps](references/fazer-ys250-blueflex-maintenance.md)
- [Contributor and agent guidance](AGENTS.md)

Repository docs own technical truth; WNT tasks track coordination and acceptance.
Personal vehicle records and source manuals stay outside this public repository.

## Build and verify

Use JDK 21 and an Android SDK with platform 36 and Build Tools 35.0.0. Set
`ANDROID_HOME` or an ignored `local.properties` SDK path. The checksum-pinned
Gradle 8.13 wrapper resolves exact catalog dependencies; the initial build needs
network access, while the app does not.

```sh
./gradlew --version
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Select an app-owned device with `ANDROID_SERIAL` for connected checks. The initial
verified target is API 35 x86_64; API 26/30/36 and physical-device checks remain
in the plan's device matrix. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
CI runs build/lint/unit checks; connected tests require a separate device.

See [storage contracts and migration policy](docs/product/local-persistence.md)
for transaction/retry, precision, time, provenance and restart checks. See
[registration and checklist behavior](docs/product/registration.md) for manual
admission, missing coverage, Custom edits and owner-reviewed local setup.
The [maintenance due model](docs/product/maintenance-due.md) implements supported
intervals, fixed oil/filter grids, independent oil calendar status and persistent
pending work. Open **Maintenance due and pending** from the checklist. Select a checklist there
to record full completion, full postponement or atomic
partial completion with linked remaining work. Create/schedule ordinary programmed
jobs there with date/km/both targets or explicitly unscheduled, and review read-only
completed history. **New corrective service** records performed date/zone, issue,
work, optional owner odometer, cost, location and replacements without completing
pending checklist work. Completed history combines all maintenance kinds and shows
missing values explicitly. **Refuel and owner odometer** opens both reviewed fuel
entry modes, read-only fuel history and timestamped owner readings. The
[fuel model](docs/product/fuel.md) preserves exact entered operands and shows
kilometers since refuel only with fresh, chronologically suitable owner evidence.
**Manual ride recording** opens large Start/Stop controls, explicit interruption
recovery, GPS coverage and daily distance/average statistics. See the
[manual recording model](docs/product/rides.md). The
[dashboard and parked management](docs/product/dashboard.md) provide the main navigation and short stopped-use refuel flow. Automatic tracking remains off.

## License

SPDX-License-Identifier: MPL-2.0

Repository material is subject to the Mozilla Public License, v. 2.0.
See the complete [LICENSE](LICENSE), published by
[Mozilla](https://www.mozilla.org/MPL/2.0/).
