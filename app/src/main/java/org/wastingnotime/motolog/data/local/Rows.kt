package org.wastingnotime.motolog.data.local

import androidx.room.*
import org.wastingnotime.motolog.domain.model.*

// Internal rows; writes are exposed through LocalStore, never REPLACE/upsert.

@Entity(tableName = "Bike", indices = [Index(value = ["slot"], unique = true)])
data class Bike(
    @PrimaryKey val id: String,
    val slot: Int = 1,
    val model: String? = null,
    val variant: String? = null,
    val market: String? = null,
    val modelYear: Int? = null,
    val manufactureYear: Int? = null,
    val manualConfirmed: Boolean = false,
) {
    internal fun fields(): List<Any?> = listOf(id, slot, model, variant, market, modelYear, manufactureYear, manualConfirmed)
}

@Entity(tableName = "Baseline", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"])], indices = [Index("bikeId"), Index(value = ["bikeId", "id", "revision"], unique = true)])
data class Baseline(
    @PrimaryKey val id: String,
    val bikeId: String,
    val revision: Int,
    @Embedded(prefix = "rule_") val rule: RuleValues,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, revision, rule)
}

@Entity(tableName = "OwnerOverride", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = Baseline::class, parentColumns = ["id"], childColumns = ["baselineId"])], indices = [Index("bikeId"), Index("baselineId"), Index(value = ["bikeId", "id", "revision"], unique = true)])
data class OwnerOverride(
    @PrimaryKey val id: String,
    val bikeId: String,
    val baselineId: String?,
    val revision: Int,
    @Embedded(prefix = "rule_") val rule: RuleValues,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, baselineId, revision, rule)
}

@Entity(tableName = "OdometerReading", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"])],
    indices = [Index("bikeId"), Index(value = ["bikeId", "certainty", "observed_epochMillis"])])
data class OdometerReading(
    @PrimaryKey val id: String,
    val bikeId: String,
    val kilometers: Long?,
    val certainty: Certainty,
    val source: ReadingSource,
    @Embedded(prefix = "observed_") val observed: Observation?,
    val recordedAt: Long,
    val originalNote: String? = null,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, kilometers, certainty, source, observed, recordedAt, originalNote)
}

@Entity(tableName = "MaintenanceOccurrence", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = MaintenanceOccurrence::class, parentColumns = ["id"], childColumns = ["parentId"])], indices = [Index("bikeId"), Index("parentId")])
data class MaintenanceOccurrence(
    @PrimaryKey val id: String,
    val bikeId: String,
    val parentId: String?,
    @Embedded(prefix = "rule_") val rule: RuleValues,
    val originalKm: Long?,
    val originalDate: String?,
    val effectiveKm: Long?,
    val effectiveDate: String?,
    val state: OccurrenceState = OccurrenceState.PENDING,
    val revision: Int = 0,
    val kind: MaintenanceKind = MaintenanceKind.UNKNOWN,
    val changeReason: String? = null,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, parentId, rule, originalKm, originalDate, effectiveKm, effectiveDate, state, revision, kind, changeReason)
}

@Entity(tableName = "MaintenanceHistory", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = MaintenanceOccurrence::class, parentColumns = ["id"], childColumns = ["occurrenceId"])], indices = [Index("bikeId"), Index("occurrenceId")])
data class MaintenanceHistory(
    @PrimaryKey val id: String,
    val bikeId: String,
    val occurrenceId: String?,
    @Embedded(prefix = "rule_") val rule: RuleValues,
    val originalKm: Long?,
    val originalDate: String?,
    val effectiveKm: Long?,
    val effectiveDate: String?,
    val performedDate: String?,
    val performedZone: String?,
    val odometerKm: Long?,
    val certainty: Certainty,
    val costBrl: String?,
    val location: String?,
    val description: String,
    val performedWork: String,
    val replacements: String?,
    val anchorEvidence: String?,
    val recordedAt: Long,
    val kind: MaintenanceKind = MaintenanceKind.UNKNOWN,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, occurrenceId, rule, originalKm, originalDate, effectiveKm, effectiveDate, performedDate, performedZone, odometerKm, certainty, costBrl, location, description, performedWork, replacements, anchorEvidence, recordedAt, kind)
}

@Entity(tableName = "HistoryAnnotation", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = MaintenanceHistory::class, parentColumns = ["id"], childColumns = ["historyId"])], indices = [Index("bikeId"), Index("historyId")])
data class HistoryAnnotation(
    @PrimaryKey val id: String,
    val bikeId: String,
    val historyId: String,
    val note: String,
    val recordedAt: Long,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, historyId, note, recordedAt)
}

@Entity(tableName = "FuelEvent", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = OdometerReading::class, parentColumns = ["id"], childColumns = ["readingId"])], indices = [Index("bikeId"), Index("readingId")])
data class FuelEvent(
    @PrimaryKey val id: String,
    val bikeId: String,
    val readingId: String?,
    val mode: FuelMode,
    val totalBrl: String,
    val enteredQuantity: String,
    @Embedded(prefix = "observed_") val observed: Observation,
    val recordedAt: Long,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, readingId, mode, totalBrl, enteredQuantity, observed, recordedAt)
}

@Entity(tableName = "RideSession", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"])], indices = [Index("bikeId"), Index(value = ["activeSlot"], unique = true), Index(value = ["bikeId", "recordedAt"])])
data class RideSession(
    @PrimaryKey val id: String,
    val bikeId: String,
    val state: RideState,
    val activeSlot: Int?,
    val ownership: RideOwnership,
    @Embedded(prefix = "started_") val started: Observation,
    @Embedded(prefix = "ended_") val ended: Observation?,
    val algorithmVersion: String,
    val recordedAt: Long,
    val revision: Int = 0,
    val reducerState: String? = null,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, state, activeSlot, ownership, started, ended, algorithmVersion, recordedAt, revision, reducerState)
}

@Entity(tableName = "RideSample", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = RideSession::class, parentColumns = ["id"], childColumns = ["rideId"])], indices = [Index("bikeId"), Index("rideId"), Index(value = ["rideId", "sequence"], unique = true), Index(value = ["rideId", "observed_epochMillis"])])
data class RideSample(
    @PrimaryKey val id: String,
    val bikeId: String,
    val rideId: String,
    val sequence: Long,
    @Embedded(prefix = "observed_") val observed: Observation,
    val clockEpoch: String,
    val elapsedMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMps: Double,
    val speedAccuracyMps: Double,
    val speedEvent: Boolean,
    val recordedAt: Long,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, rideId, sequence, observed, clockEpoch, elapsedMillis, latitude, longitude, accuracyMeters, speedMps, speedAccuracyMps, speedEvent, recordedAt)
}

@Entity(tableName = "RideGap", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"]), ForeignKey(entity = RideSession::class, parentColumns = ["id"], childColumns = ["rideId"])], indices = [Index("bikeId"), Index("rideId"), Index(value = ["rideId", "started_epochMillis"])])
data class RideGap(
    @PrimaryKey val id: String,
    val bikeId: String,
    val rideId: String,
    val reason: String,
    val rejectedCount: Long,
    @Embedded(prefix = "started_") val started: Observation,
    @Embedded(prefix = "ended_") val ended: Observation?,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, rideId, reason, rejectedCount, started, ended)
}

@Entity(tableName = "ImportRow", foreignKeys = [ForeignKey(entity = Bike::class, parentColumns = ["id"], childColumns = ["bikeId"])], indices = [Index("bikeId"), Index(value = ["importId", "rowId"], unique = true)])
data class ImportRow(
    @PrimaryKey val id: String,
    val bikeId: String,
    val importId: String,
    val rowId: String,
    val sourceNote: String,
    val certainty: Certainty,
) {
    internal fun fields(): List<Any?> = listOf(id, bikeId, importId, rowId, sourceNote, certainty)
}

@Entity(tableName = "AppliedOperation")
data class AppliedOperation(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val recordedAt: Long,
) {
    internal fun fields(): List<Any?> = listOf(id, fingerprint, recordedAt)
}

@Entity(foreignKeys = [ForeignKey(entity = MaintenanceOccurrence::class,
    parentColumns = ["id"], childColumns = ["occurrenceId"])],
    indices = [Index("occurrenceId"), Index(value = ["occurrenceId", "revision"], unique = true)])
data class OccurrenceRevision(
    @PrimaryKey val id: String,
    val occurrenceId: String,
    val revision: Int,
    val state: OccurrenceState,
    val effectiveKm: Long?,
    val effectiveDate: String?,
    val operationId: String,
    val recordedAt: Long,
    val changeReason: String?,
)
