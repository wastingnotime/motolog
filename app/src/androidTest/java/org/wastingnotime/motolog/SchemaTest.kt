package org.wastingnotime.motolog

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.wastingnotime.motolog.data.local.MotoLogDatabase
import org.wastingnotime.motolog.data.repository.LocalStore

class SchemaTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        MotoLogDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportedInitialSchemaValidatesWithoutInventedMigration() {
        helper.createDatabase("schema-test", 1).close()
        helper.runMigrationsAndValidate("schema-test", 1, true).close()
        context.deleteDatabase("schema-test")
    }

    @Test fun versionTwoMigratesToThreeWithoutChangingEvidence() {
        val name = "migration-2-3-test"
        val old = helper.createDatabase(name, 2)
        old.execSQL("INSERT INTO Bike (id, slot, manualConfirmed) VALUES ('synthetic', 1, 0)")
        old.execSQL("INSERT INTO AppliedOperation (id, fingerprint, recordedAt) VALUES ('receipt', 'original', 1)")
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 3, true, MotoLogDatabase.MIGRATION_2_3)
        migrated.query("SELECT fingerprint FROM AppliedOperation WHERE id = 'receipt'").use {
            assertTrue(it.moveToFirst()); assertEquals("original", it.getString(0))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'immutable_QualifyingAnchor_DELETE'").use {
            assertTrue(it.moveToFirst())
        }
        migrated.close()
        val reopened = MotoLogDatabase.open(context, name)
        try { assertEquals("synthetic", LocalStore(reopened).snapshot().bikes.single().id) }
        finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test fun versionThreeRecordingMigrationKeepsActiveRideAndReceiptsRecoverable() {
        val name = "migration-3-4-test"
        val old = helper.createDatabase(name, 3)
        old.execSQL("INSERT INTO Bike (id, slot, manualConfirmed) VALUES ('synthetic', 1, 0)")
        old.execSQL("INSERT INTO AppliedOperation (id, fingerprint, recordedAt) VALUES ('receipt', 'original', 1)")
        old.execSQL("INSERT INTO RideSession (id, bikeId, state, activeSlot, ownership, started_epochMillis, started_zoneId, started_offsetSeconds, algorithmVersion, recordedAt, revision) VALUES ('ride', 'synthetic', 'ACTIVE', 1, 'MANUAL', 1700000000000, 'UTC', 0, 'legacy-v1', 1700000000000, 0)")
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 4, true, MotoLogDatabase.MIGRATION_3_4)
        migrated.query("SELECT fingerprint FROM AppliedOperation WHERE id = 'receipt'").use {
            assertTrue(it.moveToFirst()); assertEquals("original", it.getString(0))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'immutable_QualityFix_DELETE'").use { assertTrue(it.moveToFirst()) }
        migrated.close()
        val db = MotoLogDatabase.open(context, name)
        try {
            val repository = org.wastingnotime.motolog.data.repository.RideRepository(db)
            repository.interrupt("PROCESS_RESTART", org.wastingnotime.motolog.domain.model.Observation(1700000001000, "UTC", 0))
            assertEquals(org.wastingnotime.motolog.domain.model.RideState.INTERRUPTED, repository.status().first!!.state)
            assertNotNull(repository.status().second!!.gap)
            assertThrows(IllegalArgumentException::class.java) {
                repository.resume("ride", org.wastingnotime.motolog.domain.model.Observation(1700000001000, "UTC", 0), "new")
            }
            repository.stop("ride", org.wastingnotime.motolog.domain.model.Observation(1700000001000, "UTC", 0), true)
            assertNull(repository.status().first)
            assertEquals(1700000000000, db.dao().ride("ride")!!.ended!!.epochMillis)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFourBackfillsBoundedPerRideTotalsWithoutBridgingGapsOrInventingUnknownAlgorithms() {
        val name = "migration-4-5-test"
        val old = helper.createDatabase(name, 4)
        val version = org.wastingnotime.motolog.domain.ride.RideMetrics.VERSION
        old.execSQL("INSERT INTO Bike (id, slot, manualConfirmed) VALUES ('bike', 1, 0)")
        for ((id, algorithm) in listOf("ride" to version, "legacy" to "unsupported")) {
            old.execSQL("INSERT INTO RideSession (id, bikeId, state, ownership, started_epochMillis, started_zoneId, started_offsetSeconds, ended_epochMillis, ended_zoneId, ended_offsetSeconds, algorithmVersion, recordedAt, revision) VALUES (?, 'bike', 'ENDED', 'MANUAL', 1700000000000, 'UTC', 0, 1700000005000, 'UTC', 0, ?, 1700000000000, 0)", arrayOf(id, algorithm))
        }
        repeat(4) { i ->
            old.execSQL("INSERT INTO RideSample (id, bikeId, rideId, sequence, observed_epochMillis, observed_zoneId, observed_offsetSeconds, clockEpoch, elapsedMillis, latitude, longitude, accuracyMeters, speedMps, speedAccuracyMps, speedEvent, recordedAt) VALUES (?, 'bike', 'ride', ?, ?, 'UTC', 0, 'epoch', ?, 0, 0, 5, 0, 1, 1, ?)", arrayOf<Any>("sample-$i", i, 1700000000000L + i * 1000, i * 1000L, 1700000000000L + i * 1000))
        }
        old.execSQL("INSERT INTO RideGap (id, bikeId, rideId, reason, rejectedCount, started_epochMillis, started_zoneId, started_offsetSeconds, ended_epochMillis, ended_zoneId, ended_offsetSeconds) VALUES ('gap', 'bike', 'ride', 'PROCESS_RESTART', 0, 1700000001000, 'UTC', 0, 1700000002000, 'UTC', 0)")
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 5, true, MotoLogDatabase.MIGRATION_4_5)
        migrated.query("SELECT meters, eligibleMillis FROM RideTotal WHERE rideId = 'ride'").use {
            assertTrue(it.moveToFirst()); assertEquals(0.0, it.getDouble(0), 0.0); assertEquals(2000L, it.getLong(1))
        }
        migrated.query("SELECT * FROM RideTotal WHERE rideId = 'legacy'").use { assertFalse(it.moveToFirst()) }
        migrated.query("SELECT COUNT(*) FROM RideSample").use { assertTrue(it.moveToFirst()); assertEquals(4L, it.getLong(0)) }
        migrated.close()
        context.deleteDatabase(name)
    }

    @Test fun indexedMigrationPreservesEveryRecordAndReceiptAndInstallsGuards() {
        val name = "migration-1-2-test"
        val old = helper.createDatabase(name, 1)
        val time = 1_700_000_000_000L
        fun insert(table: String, vararg fields: Pair<String, Any?>) {
            val values = ContentValues()
            fields.forEach { (key, value) -> when (value) {
                null -> values.putNull(key)
                is String -> values.put(key, value)
                is Long -> values.put(key, value)
                is Int -> values.put(key, value)
                is Double -> values.put(key, value)
                else -> error("Unsupported fixture value")
            } }
            old.insert(table, 0, values)
        }
        val rule = arrayOf("rule_action" to "Inspect", "rule_component" to "Synthetic",
            "rule_sourceKind" to "CUSTOM", "rule_scheduleKind" to "UNKNOWN")
        val observed = arrayOf("observed_epochMillis" to time, "observed_zoneId" to "UTC",
            "observed_offsetSeconds" to 0)
        insert("Bike", "id" to "bike", "slot" to 1, "manualConfirmed" to 0)
        insert("Baseline", "id" to "baseline", "bikeId" to "bike", "revision" to 1, *rule)
        insert("OwnerOverride", "id" to "override", "bikeId" to "bike", "baselineId" to "baseline",
            "revision" to 1, *rule)
        insert("OdometerReading", "id" to "owner", "bikeId" to "bike", "kilometers" to 20_000,
            "certainty" to "CONFIRMED", "source" to "OWNER", "recordedAt" to time, *observed)
        insert("MaintenanceOccurrence", "id" to "pending", "bikeId" to "bike", "originalKm" to 21_000,
            "effectiveKm" to 22_000, "state" to "POSTPONED", "revision" to 1, "kind" to "PROGRAMMED", *rule)
        insert("MaintenanceHistory", "id" to "service", "bikeId" to "bike", "occurrenceId" to "pending",
            "certainty" to "UNKNOWN", "costBrl" to null, "performedDate" to null,
            "description" to "Synthetic service", "performedWork" to "Inspected",
            "recordedAt" to time, "kind" to "PROGRAMMED", *rule)
        insert("HistoryAnnotation", "id" to "note", "bikeId" to "bike", "historyId" to "service",
            "note" to "Synthetic evidence", "recordedAt" to time)
        insert("FuelEvent", "id" to "fuel", "bikeId" to "bike", "readingId" to "owner",
            "mode" to "LITERS", "totalBrl" to "100", "enteredQuantity" to "20", "recordedAt" to time, *observed)
        insert("RideSession", "id" to "ride", "bikeId" to "bike", "state" to "INTERRUPTED", "activeSlot" to 1,
            "ownership" to "MANUAL", "algorithmVersion" to "test-v1", "recordedAt" to time, "revision" to 0,
            "started_epochMillis" to time, "started_zoneId" to "UTC", "started_offsetSeconds" to 0)
        insert("RideSample", "id" to "sample", "bikeId" to "bike", "rideId" to "ride", "sequence" to 0,
            "clockEpoch" to "epoch", "elapsedMillis" to 1_000, "latitude" to 0.0, "longitude" to 0.0,
            "accuracyMeters" to 5.0, "speedMps" to 0.0, "speedAccuracyMps" to 1.0, "speedEvent" to 1,
            "recordedAt" to time, *observed)
        insert("RideGap", "id" to "gap", "bikeId" to "bike", "rideId" to "ride", "reason" to "MISSING_FIX",
            "rejectedCount" to 3, "started_epochMillis" to time, "started_zoneId" to "UTC", "started_offsetSeconds" to 0)
        insert("ImportRow", "id" to "import", "bikeId" to "bike", "importId" to "batch", "rowId" to "row",
            "sourceNote" to "Synthetic note", "certainty" to "UNKNOWN")
        insert("AppliedOperation", "id" to "receipt", "fingerprint" to "synthetic-fingerprint", "recordedAt" to time)
        insert("OccurrenceRevision", "id" to "previous", "occurrenceId" to "pending", "revision" to 0,
            "state" to "PENDING", "effectiveKm" to 21_000, "operationId" to "receipt", "recordedAt" to time)
        val tables = listOf("Bike", "Baseline", "OwnerOverride", "OdometerReading", "MaintenanceOccurrence",
            "MaintenanceHistory", "HistoryAnnotation", "FuelEvent", "RideSession", "RideSample", "RideGap",
            "ImportRow", "AppliedOperation", "OccurrenceRevision")
        fun snapshot(db: SupportSQLiteDatabase) = tables.associateWith { table ->
            db.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
            }
        }
        val before = snapshot(old)
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 3, true, MotoLogDatabase.MIGRATION_1_2, MotoLogDatabase.MIGRATION_2_3)
        assertEquals(before, snapshot(migrated))
        assertThrows(Exception::class.java) { migrated.execSQL("UPDATE MaintenanceHistory SET costBrl = '0'") }
        assertThrows(Exception::class.java) { migrated.execSQL("DELETE FROM AppliedOperation") }
        migrated.close()
        val reopened = MotoLogDatabase.open(context, name)
        try {
            val store = LocalStore(reopened)
            assertEquals("bike", store.snapshot().bikes.single().id)
            assertNull(store.snapshot().histories.single().costBrl)
            assertEquals(1, store.occurrenceRevisions().size)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
