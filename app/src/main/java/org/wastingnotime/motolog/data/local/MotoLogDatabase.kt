package org.wastingnotime.motolog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Bike::class, Baseline::class, OwnerOverride::class, OdometerReading::class,
    MaintenanceOccurrence::class, MaintenanceHistory::class, HistoryAnnotation::class,
    FuelEvent::class, RideSession::class, RideSample::class, RideGap::class,
    ImportRow::class, AppliedOperation::class, OccurrenceRevision::class, PreventiveObligation::class, QualifyingAnchor::class,
    ObligationSatisfaction::class], version = 3, exportSchema = true)
abstract class MotoLogDatabase : RoomDatabase() {
    internal abstract fun dao(): StoreDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_OdometerReading_bikeId_certainty_observed_epochMillis " +
                    "ON OdometerReading (bikeId, certainty, observed_epochMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_RideSample_rideId_observed_epochMillis " +
                    "ON RideSample (rideId, observed_epochMillis)")
                installGuards(db)
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS PreventiveObligation (occurrenceId TEXT NOT NULL PRIMARY KEY, baselineId TEXT NOT NULL, cycleKey TEXT NOT NULL, branch TEXT NOT NULL, anchorKmId TEXT, anchorDateId TEXT, FOREIGN KEY(baselineId) REFERENCES Baseline(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(occurrenceId) REFERENCES MaintenanceOccurrence(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(anchorKmId) REFERENCES MaintenanceHistory(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(anchorDateId) REFERENCES MaintenanceHistory(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE TABLE IF NOT EXISTS QualifyingAnchor (id TEXT NOT NULL PRIMARY KEY, baselineId TEXT NOT NULL, historyId TEXT NOT NULL, revision INTEGER NOT NULL, orderReconciled INTEGER NOT NULL, FOREIGN KEY(baselineId) REFERENCES Baseline(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(historyId) REFERENCES MaintenanceHistory(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ObligationSatisfaction (occurrenceId TEXT NOT NULL PRIMARY KEY, historyId TEXT NOT NULL, originalKm INTEGER, originalDate TEXT, effectiveKm INTEGER, effectiveDate TEXT, occurrenceRevision INTEGER NOT NULL, FOREIGN KEY(occurrenceId) REFERENCES MaintenanceOccurrence(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(historyId) REFERENCES MaintenanceHistory(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                for ((table, columns) in listOf("PreventiveObligation" to listOf("baselineId", "anchorKmId", "anchorDateId"),
                    "QualifyingAnchor" to listOf("baselineId", "historyId"), "ObligationSatisfaction" to listOf("historyId"))) {
                    columns.forEach { db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_$it ON $table ($it)") }
                }
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_PreventiveObligation_baselineId_cycleKey ON PreventiveObligation (baselineId, cycleKey)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_QualifyingAnchor_baselineId_revision ON QualifyingAnchor (baselineId, revision)")
                installGuards(db)
            }
        }

        fun open(context: Context, name: String = "motolog.db"): MotoLogDatabase =
            builder(context, name).build()

        internal fun builder(context: Context, name: String): Builder<MotoLogDatabase> =
            Room.databaseBuilder(context.applicationContext, MotoLogDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { installGuards(db) }
                })
                // Unknown versions fail: no destructive fallback.

        private fun installGuards(db: SupportSQLiteDatabase) {
            // Preserve append-only evidence on both fresh databases and migration paths.
            for (table in listOf("Baseline", "OwnerOverride", "OdometerReading",
                "MaintenanceHistory", "HistoryAnnotation", "FuelEvent", "RideSample",
                "RideGap", "ImportRow", "AppliedOperation", "OccurrenceRevision",
                "PreventiveObligation", "QualifyingAnchor", "ObligationSatisfaction")) {
                val exists = db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
                if (!exists) continue
                for (action in listOf("UPDATE", "DELETE")) {
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS immutable_${table}_$action BEFORE $action ON $table " +
                        "BEGIN SELECT RAISE(ABORT, 'Immutable evidence'); END")
                }
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS stable_bike BEFORE UPDATE ON Bike WHEN NEW.slot != 1 OR NEW.id != OLD.id " +
                "BEGIN SELECT RAISE(ABORT, 'Stable single bike only'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS single_bike BEFORE INSERT ON Bike WHEN NEW.slot != 1 " +
                "BEGIN SELECT RAISE(ABORT, 'Single bike only'); END")
        }
    }
}
