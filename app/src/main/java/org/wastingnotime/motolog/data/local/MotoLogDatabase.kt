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
    ImportRow::class, AppliedOperation::class, OccurrenceRevision::class], version = 2, exportSchema = true)
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

        fun open(context: Context, name: String = "motolog.db"): MotoLogDatabase =
            builder(context, name).build()

        internal fun builder(context: Context, name: String): Builder<MotoLogDatabase> =
            Room.databaseBuilder(context.applicationContext, MotoLogDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { installGuards(db) }
                })
                // Unknown versions fail: no destructive fallback.

        private fun installGuards(db: SupportSQLiteDatabase) {
            // Preserve append-only evidence on both fresh databases and migration paths.
            for (table in listOf("Baseline", "OwnerOverride", "OdometerReading",
                "MaintenanceHistory", "HistoryAnnotation", "FuelEvent", "RideSample",
                "RideGap", "ImportRow", "AppliedOperation", "OccurrenceRevision")) {
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
