package org.wastingnotime.motolog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Bike::class, Baseline::class, OwnerOverride::class, OdometerReading::class,
    MaintenanceOccurrence::class, MaintenanceHistory::class, HistoryAnnotation::class,
    FuelEvent::class, RideSession::class, RideSample::class, RideGap::class,
    ImportRow::class, AppliedOperation::class, OccurrenceRevision::class], version = 1, exportSchema = true)
abstract class MotoLogDatabase : RoomDatabase() {
    internal abstract fun dao(): StoreDao

    companion object {
        fun open(context: Context, name: String = "motolog.db"): MotoLogDatabase =
            Room.databaseBuilder(context.applicationContext, MotoLogDatabase::class.java, name)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Enforce append-only evidence even against accidental future DAO updates.
                        for (table in listOf("Baseline", "OwnerOverride", "OdometerReading",
                            "MaintenanceHistory", "HistoryAnnotation", "FuelEvent", "RideSample",
                            "RideGap", "ImportRow", "AppliedOperation", "OccurrenceRevision")) {
                            for (action in listOf("UPDATE", "DELETE")) {
                                db.execSQL("CREATE TRIGGER immutable_${table}_$action BEFORE $action ON $table " +
                                    "BEGIN SELECT RAISE(ABORT, 'Immutable evidence'); END")
                            }
                        }
                        db.execSQL("CREATE TRIGGER stable_bike BEFORE UPDATE ON Bike WHEN NEW.slot != 1 OR NEW.id != OLD.id " +
                            "BEGIN SELECT RAISE(ABORT, 'Stable single bike only'); END")
                        db.execSQL("CREATE TRIGGER single_bike BEFORE INSERT ON Bike WHEN NEW.slot != 1 " +
                            "BEGIN SELECT RAISE(ABORT, 'Single bike only'); END")
                    }
                })
                // Initial schema: no previous shipped version and no destructive fallback.
                .build()
    }
}
