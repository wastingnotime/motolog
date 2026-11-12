package org.wastingnotime.motolog.data.local

import androidx.room.*

@Dao
internal interface StoreDao {
    @Insert fun insert(row: Bike)
    @Query("SELECT * FROM Bike ORDER BY id") fun bikeRows(): List<Bike>
    @Query("UPDATE Bike SET manufactureYear = :year, manualConfirmed = 1 WHERE id = :id AND manualConfirmed = 0")
    fun confirmManual(id: String, year: Int): Int
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
    @Query("SELECT EXISTS(SELECT 1 FROM RideSample WHERE rideId = :rideId AND observed_epochMillis > :end)")
    fun hasSampleAfter(rideId: String, end: Long): Boolean
    @Query("SELECT * FROM OdometerReading WHERE bikeId = :bikeId AND certainty = 'CONFIRMED' AND observed_epochMillis < :time ORDER BY observed_epochMillis DESC LIMIT 1")
    fun previousConfirmedReading(bikeId: String, time: Long): OdometerReading?
    @Query("SELECT * FROM OdometerReading WHERE bikeId = :bikeId AND certainty = 'CONFIRMED' AND observed_epochMillis > :time ORDER BY observed_epochMillis LIMIT 1")
    fun nextConfirmedReading(bikeId: String, time: Long): OdometerReading?
    @Query("SELECT * FROM OdometerReading WHERE bikeId = :bikeId AND certainty = 'CONFIRMED' AND observed_epochMillis = :time AND id != :id LIMIT 1")
    fun simultaneousConfirmedReading(bikeId: String, time: Long, id: String): OdometerReading?
    @Insert fun insert(row: PreventiveObligation)
    @Query("SELECT * FROM PreventiveObligation ORDER BY occurrenceId") fun obligations(): List<PreventiveObligation>
    @Insert fun insert(row: QualifyingAnchor)
    @Query("SELECT * FROM QualifyingAnchor ORDER BY id") fun anchors(): List<QualifyingAnchor>
    @Insert fun insert(row: ObligationSatisfaction)
    @Query("SELECT * FROM ObligationSatisfaction ORDER BY occurrenceId") fun satisfactions(): List<ObligationSatisfaction>
    @Update fun update(row: MaintenanceOccurrence): Int
    @Update fun update(row: RideSession): Int
    @Query("SELECT * FROM RideSession WHERE activeSlot = 1 LIMIT 1") fun activeRide(): RideSession?
    @Query("SELECT * FROM RideSample WHERE id = :id") fun sample(id: String): RideSample?
    @Query("SELECT * FROM RideSample WHERE rideId = :rideId ORDER BY sequence DESC LIMIT 1")
    fun lastSample(rideId: String): RideSample?
    @Query("SELECT * FROM RecordingCursor WHERE rideId = :rideId") fun cursor(rideId: String): RecordingCursor?
    @Upsert fun checkpoint(row: RecordingCursor)
    @Insert fun insert(row: QualityFix)
    @Query("SELECT * FROM RideDay WHERE bikeId = :bikeId AND date = :date") fun days(bikeId: String, date: String): List<RideDay>
    @Query("SELECT * FROM RideDay WHERE bikeId = :bikeId AND date = :date AND zone = :zone")
    fun day(bikeId: String, date: String, zone: String): RideDay?
    @Upsert fun project(row: RideDay)
    @Query("SELECT * FROM RideSession WHERE bikeId = :bikeId ORDER BY recordedAt DESC LIMIT 20")
    fun recentRides(bikeId: String): List<RideSession>
    @Query("SELECT * FROM RideGap WHERE rideId = :rideId ORDER BY started_epochMillis DESC LIMIT 20")
    fun recentGaps(rideId: String): List<RideGap>

}
