# V1 validation and owner checks

Validation uses the accepted [V1 requirements](v1.md), [decisions](decisions.md)
and [device matrix](execution-plan.md). Build/test logs, artifact hashes, device
identities, current results and acceptance status belong in the local task record.
Emulator traces do not establish real GNSS quality, phone background behavior,
battery life or owner usability.

## Reproduce automated checks

Use JDK 21 and the pinned SDK/toolchain described in the README. Select only an
app-owned synthetic test emulator; leave unrelated devices unchanged. Record the
source commit, clean/dirty state, API/ABI, APK SHA-256 and test selection.

```sh
git rev-parse HEAD
git status --short
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
sha256sum app/build/outputs/apk/debug/app-debug.apk
export ANDROID_SERIAL=YOUR_TEST_EMULATOR_SERIAL
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi
```

Before instrumentation, use Android Settings to enable airplane mode and disable
Wi-Fi. Save the visible switch states and `dumpsys connectivity`; verify there is
no active default network. A backing setting alone does not prove offline state.
No INTERNET permission is declared by the app.

Dashboard UI tests require an empty app database. On the selected **synthetic
test emulator only**, install the APK and clear its app data before the suite:

```sh
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" shell pm clear org.wastingnotime.motolog
./gradlew :app:connectedDebugAndroidTest
```

Preserve `app/build/test-results/`, `app/build/reports/` and
`app/build/outputs/androidTest-results/` privately before subsequent runs overwrite
them. Check that Gradle reports only the selected device. Tests using mock fixes
exercise the platform service but are not ordinary-ride evidence.

The restart probes support separate instrumentation processes. Reinstall the
exact app and test APKs after connected tests, which may uninstall them:

```sh
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
export PROBE=RegistrationRestartProbeTest
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "org.wastingnotime.motolog.$PROBE" -e phase seed org.wastingnotime.motolog.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" shell am force-stop org.wastingnotime.motolog
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "org.wastingnotime.motolog.$PROBE" -e phase verify org.wastingnotime.motolog.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat for `RestartProbeTest`, `MaintenanceRestartProbeTest`,
`MaintenanceOutcomeRestartTest`, `FuelRestartProbeTest` and `RideRestartProbeTest`.
Require `OK` and no failure/crash markers, rather than relying on adb's exit code.
Keep app data between seed and verify. `RideLifecycleProbeTest` additionally
supports real permission-revocation and force-stop phases; its source describes
the required grants, mock-location setup and phase names.

## Requirement-to-check map

Names below refer to suites in `app/src/test/` or `app/src/androidTest/`.
Record each suite's actual result and device in the local evidence report.

| Requirement | Automated evidence | Additional owner evidence |
| --- | --- | --- |
| One stable bike, gated exact preset, unknown history, source locators, Custom baseline/override separation, preview/cancel/import retries | RegistrationTest, RegistrationFlowTest, RegistrationRestartProbeTest, LocalStoreTest, WritePathTest | Confirm actual YS250/BlueFlex/BR/model-2017/manual match and separate manufacture year before admitting facts; otherwise leave unconfirmed |
| First due threshold, sole threshold, unknown anchors, distinct fixed oil/filter grids, calendar independence and partial coverage | DueEvaluationTest, MaintenanceTest, MaintenanceFlowTest, MaintenanceRestartProbeTest | Review supported coverage and explicit manual-review gaps; no whole-bike current-maintenance claim |
| Full/none/partial completion, original targets, linked remainders, atomic rollback/retry and later overlap | MaintenanceOutcomeTest, MaintenanceOutcomeFlowTest, MaintenanceOutcomeRestartTest | Perform/review parked synthetic outcomes before entering real history |
| Programmed jobs and immutable corrective/completed history with fields and provenance | CorrectiveServiceTest, CorrectiveServiceFlowTest, MaintenanceOutcomeTest | Review date/zone, issue, work, replacements, location, unknown versus zero cost and owner reading |
| Both fuel modes, exact operands, validation, backdates, duplicate saves and owner-reading authority | FuelValuesTest, FuelProjectionTest, FuelRepositoryTest, FuelFlowTest, FuelRestartProbeTest | Verify labels and practical entry while stopped; check fresh/stale/missing readings |
| Manual ride, >=5 km/h event boundary, gaps, stopped denominator, midnight/DST/zone/clock behavior | RideMetricsTest, RideRepositoryTest, RideRestartProbeTest, RideScreenTest, RideHistoryFlowTest | Real GNSS/native speed quality and one ride across a day boundary if available |
| Optional automatic mode, qualification, idle stop, manual takeover, one ride and replay protection | AutomaticDetectionTest, AutomaticRideTest, RideCaptureDeviceTest | Ordinary ride, screen off, background/OEM behavior and battery/storage measurements |
| Permission loss, missing fixes, process interruption and visible recovery | RideCaptureDeviceTest, RideLifecycleProbeTest, RideRestartProbeTest | Parked permission/GPS loss and recovery; preserved history and visible gaps |
| Dashboard missing/zero/stale metrics, large controls, visible armed/recording/interrupted states, parked routes and read-only history | DashboardDataTest, DashboardFlowTest, RideScreenTest, RideHistoryFlowTest | Owner touch/text-scale review while stopped; management while parked; Stop accessible |
| Offline local persistence, migrations and excluded scope | Restart probes, SchemaTest, LocalStoreTest; manifest/backup-rule inspection | Repeat phone flows in airplane mode after restart |

## Owner validation on the test bike

1. Install the supplied versioned debug APK and retain its build identifier/hash.
   Keep diagnostics and personal records local. Do not run emulator data-clearing
   commands on the owner phone. Review manual applicability before confirming it;
   unknown service history and calendar anchors must remain unknown.
2. While parked, enable airplane mode and disable Wi-Fi. Review registration,
   sources, Custom labels and local setup previews/cancel paths separately from
   manual admission. Save only owner-confirmed real readings, refuels and actually
   performed work. Never invent a completion or fuel event to exercise the phone:
   its one-bike history is immutable. Do not clear data once personal records
   exist. Full synthetic mutation/outcome regression evidence comes from disposable
   app-owned emulators. Practical acceptance of real events can occur in ordinary
   use; then close/reopen and verify records, remainders and dashboard values.
   Record every unexpected result.
3. While stopped, review Start/Stop, quick refuel, automatic arm/disarm, notification
   actions and readable states at the preferred text size. Record confusing labels,
   obscured controls, missed taps and unnecessary steps. Detailed management is a
   parked flow. No interaction while moving is required.
4. During an ordinary ride, use manual mode and a separate explicitly armed
   automatic session. Set up while stopped, then leave the screen off. After
   parking, review start/stop timing, measured stops, gaps, duplicate sessions,
   distance/speed plausibility and background interruptions. Do not interpret
   unavailable native speed as zero or substitute GPS for owner odometer.
5. Record duration, initial/final battery percentage, charging state, screen-on
   duration, capture/armed duration, app storage before/after and interruption
   details. Compare battery percentage points/hour and storage growth/hour with
   these conditions attached; a short or charging run cannot establish battery
   performance. Check interruption/recovery while parked and verify history after
   restart. Record physical API/model as reported, without guessing a variant/ROM.

Required failures and missing physical evidence prevent final acceptance. Keep
unsupported maintenance rules, applicability questions and unresolved defects
explicit in the task; do not convert emulator success into field acceptance.
