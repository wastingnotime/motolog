package org.wastingnotime.motolog.data.local

import androidx.room.*

@Dao
internal interface StoreDao {
    @Insert fun insert(row: Bike)
    @Query("SELECT * FROM Bike ORDER BY id") fun bikeRows(): List<Bike>
    @Insert fun insert(row: Baseline)
    @Query("SELECT * FROM Baseline ORDER BY id") fun baselineRows(): List<Baseline>
    @Insert fun insert(row: OwnerOverride)
    @Query("SELECT * FROM OwnerOverride ORDER BY id") fun ownerOverrideRows(): List<OwnerOverride>
    @Insert fun insert(row: OdometerReading)
    @Query("SELECT * FROM OdometerReading ORDER BY id") fun odometerReadingRows(): List<OdometerReading>
    @Insert fun insert(row: MaintenanceOccurrence)
    @Query("SELECT * FROM MaintenanceOccurrence ORDER BY id") fun maintenanceOccurrenceRows(): List<MaintenanceOccurrence>
    @Insert fun insert(row: MaintenanceHistory)
    @Query("SELECT * FROM MaintenanceHistory ORDER BY id") fun maintenanceHistoryRows(): List<MaintenanceHistory>
    @Insert fun insert(row: HistoryAnnotation)
    @Query("SELECT * FROM HistoryAnnotation ORDER BY id") fun historyAnnotationRows(): List<HistoryAnnotation>
    @Insert fun insert(row: FuelEvent)
    @Query("SELECT * FROM FuelEvent ORDER BY id") fun fuelEventRows(): List<FuelEvent>
    @Insert fun insert(row: RideSession)
    @Query("SELECT * FROM RideSession ORDER BY id") fun rideSessionRows(): List<RideSession>
    @Insert fun insert(row: RideSample)
    @Query("SELECT * FROM RideSample ORDER BY id") fun rideSampleRows(): List<RideSample>
    @Insert fun insert(row: RideGap)
    @Query("SELECT * FROM RideGap ORDER BY id") fun rideGapRows(): List<RideGap>
    @Insert fun insert(row: ImportRow)
    @Query("SELECT * FROM ImportRow ORDER BY id") fun importRowRows(): List<ImportRow>
    @Insert fun insert(row: AppliedOperation)
    @Query("SELECT * FROM AppliedOperation ORDER BY id") fun appliedOperationRows(): List<AppliedOperation>
    @Query("SELECT * FROM AppliedOperation WHERE id = :id") fun operation(id: String): AppliedOperation?
    @Query("SELECT * FROM MaintenanceOccurrence WHERE id = :id") fun occurrence(id: String): MaintenanceOccurrence?
    @Query("SELECT * FROM RideSession WHERE id = :id") fun ride(id: String): RideSession?
    @Insert fun insert(row: OccurrenceRevision)
    @Query("SELECT * FROM OccurrenceRevision ORDER BY occurrenceId, revision") fun occurrenceRevisions(): List<OccurrenceRevision>
    @Query("SELECT * FROM OdometerReading WHERE id = :id") fun reading(id: String): OdometerReading?
    @Query("SELECT * FROM RideSample WHERE rideId = :rideId AND sequence < :sequence ORDER BY sequence DESC LIMIT 1")
    fun previousSample(rideId: String, sequence: Long): RideSample?
    @Query("SELECT * FROM RideSample WHERE rideId = :rideId AND sequence > :sequence ORDER BY sequence LIMIT 1")
    fun nextSample(rideId: String, sequence: Long): RideSample?
    @Query("SELECT COUNT(*) FROM RideSample WHERE rideId = :rideId AND (observed_epochMillis < :start OR observed_epochMillis > :end)")
    fun samplesOutsideRide(rideId: String, start: Long, end: Long): Int
    @Update fun update(row: MaintenanceOccurrence): Int
    @Update fun update(row: RideSession): Int
}
