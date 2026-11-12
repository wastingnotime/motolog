package org.wastingnotime.motolog.data.repository

import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** One command, one transaction. IDs belong to callers and must survive UI/process retries. */
data class WriteBatch(
    val bikes: List<Bike> = emptyList(),
    val baselines: List<Baseline> = emptyList(),
    val overrides: List<OwnerOverride> = emptyList(),
    val readings: List<OdometerReading> = emptyList(),
    val occurrences: List<MaintenanceOccurrence> = emptyList(),
    val histories: List<MaintenanceHistory> = emptyList(),
    val annotations: List<HistoryAnnotation> = emptyList(),
    val fuels: List<FuelEvent> = emptyList(),
    val rides: List<RideSession> = emptyList(),
    val samples: List<RideSample> = emptyList(),
    val gaps: List<RideGap> = emptyList(),
    val importRows: List<ImportRow> = emptyList(),
    val occurrenceChanges: List<MaintenanceOccurrence> = emptyList(),
    val rideChanges: List<RideSession> = emptyList(),
    val obligations: List<PreventiveObligation> = emptyList(),
    val anchors: List<QualifyingAnchor> = emptyList(),
    val satisfactions: List<ObligationSatisfaction> = emptyList(),
) {
    internal fun fields(): List<Any?> = listOf(
        bikes.map { it.fields() }, baselines.map { it.fields() }, overrides.map { it.fields() },
        readings.map { it.fields() }, occurrences.map { it.fields() }, histories.map { it.fields() },
        annotations.map { it.fields() }, fuels.map { it.fields() }, rides.map { it.fields() },
        samples.map { it.fields() }, gaps.map { it.fields() }, importRows.map { it.fields() },
        occurrenceChanges.map { it.fields() }, rideChanges.map { it.fields() }) +
        // Preserve v1/v2 command fingerprints when no new metadata is supplied.
        if (obligations.isEmpty() && anchors.isEmpty() && satisfactions.isEmpty()) emptyList() else
            listOf(obligations.map { it.fields() }, anchors.map { it.fields() }, satisfactions.map { it.fields() })
}

class OperationConflict : IllegalArgumentException("Operation ID reused with a different command")
class StaleRevision : IllegalStateException("Record changed; reload before applying command")

class LocalStore(private val db: MotoLogDatabase) {
    private val dao get() = db.dao()

    /** Returns false for an exact replay. All failures roll back rows and operation receipt. */
    fun write(operationId: String, batch: WriteBatch, recordedAt: Long): Boolean {
        require(operationId.isNotBlank())
        val fingerprint = fingerprint(batch.fields())
        return db.runInTransaction<Boolean> {
            val previous = dao.operation(operationId)
            if (previous != null) {
                if (previous.fingerprint != fingerprint) throw OperationConflict()
                return@runInTransaction false
            }
            validate(batch)
            batch.bikes.forEach(dao::insert)
            batch.baselines.forEach(dao::insert)
            batch.overrides.forEach(dao::insert)
            batch.readings.forEach(dao::insert)
            batch.readings.filter { it.certainty == Certainty.CONFIRMED }.forEach(::validateReadingNeighbors)
            batch.occurrences.forEach(dao::insert)
            batch.occurrenceChanges.forEach { next ->
                val old = dao.occurrence(next.id) ?: throw StaleRevision()
                if (next.revision != old.revision + 1) throw StaleRevision()
                require(old.bikeId == next.bikeId && old.rule == next.rule &&
                    old.originalKm == next.originalKm && old.originalDate == next.originalDate &&
                    old.parentId == next.parentId && old.kind == next.kind && old.state != OccurrenceState.COMPLETED)
                dao.insert(OccurrenceRevision("${operationId.length}:$operationId:${old.id}", old.id,
                    old.revision, old.state, old.effectiveKm, old.effectiveDate, operationId, recordedAt, old.changeReason))
                check(dao.update(next) == 1)
            }
            batch.histories.forEach(dao::insert)
            batch.obligations.forEach(dao::insert)
            batch.anchors.forEach(dao::insert)
            batch.satisfactions.forEach(dao::insert)
            batch.annotations.forEach(dao::insert)
            batch.fuels.forEach(dao::insert)
            batch.rides.forEach(dao::insert)
            batch.rideChanges.forEach { next ->
                val old = dao.ride(next.id) ?: throw StaleRevision()
                if (next.revision != old.revision + 1) throw StaleRevision()
                require(old.bikeId == next.bikeId && old.started == next.started &&
                    old.algorithmVersion == next.algorithmVersion && old.recordedAt == next.recordedAt && old.state != RideState.ENDED)
                check(dao.update(next) == 1)
            }
            batch.samples.forEach(dao::insert)
            batch.gaps.forEach(dao::insert)
            batch.importRows.forEach(dao::insert)
            validateLinks(batch)
            dao.insert(AppliedOperation(operationId, fingerprint, recordedAt))
            true
        }
    }

    // A consistent read for restart/recovery; feature-specific query projections can follow.
    fun snapshot(): WriteBatch = db.runInTransaction<WriteBatch> {
        WriteBatch(dao.bikeRows(), dao.baselineRows(), dao.ownerOverrideRows(),
            dao.odometerReadingRows(), dao.maintenanceOccurrenceRows(), dao.maintenanceHistoryRows(),
            dao.historyAnnotationRows(), dao.fuelEventRows(), dao.rideSessionRows(), dao.rideSampleRows(),
            dao.rideGapRows(), dao.importRowRows(), obligations = dao.obligations(),
            anchors = dao.anchors(), satisfactions = dao.satisfactions())
    }

    fun occurrenceRevisions(): List<OccurrenceRevision> = dao.occurrenceRevisions()

    private fun validate(batch: WriteBatch) {
        batch.fields().forEach { group -> (group as List<*>).forEach { row ->
            require(((row as List<*>).first() as String).isNotBlank()) { "Blank record ID" }
        } }
        fun km(value: Long?) { require(value == null || value in 0..999_999_999_999L) }
        fun date(value: String?) { value?.let { require(LocalDate.parse(it).toString() == it) } }
        fun rule(value: RuleValues) {
            require(value.action.isNotBlank() && value.component.isNotBlank())
            km(value.intervalKm)
            require(value.intervalKm == null || value.intervalKm > 0)
            require(value.intervalMonths == null || value.intervalMonths > 0)
            if (value.sourceKind == SourceKind.MANUAL) {
                require(!value.sourceIdentity.isNullOrBlank() && !value.edition.isNullOrBlank() &&
                    !value.locator.isNullOrBlank() && value.baselineRevision != null)
            }
        }
        batch.bikes.forEach { require(it.id.isNotBlank() && it.slot == 1) }
        batch.baselines.forEach { require(it.revision > 0); rule(it.rule) }
        batch.overrides.forEach {
            require(it.revision > 0 && it.rule.sourceKind == SourceKind.CUSTOM); rule(it.rule)
        }
        batch.readings.forEach {
            km(it.kilometers); it.observed?.validate()
            if (it.certainty == Certainty.CONFIRMED) require(it.kilometers != null && it.observed != null)
        }
        (batch.occurrences + batch.occurrenceChanges).forEach {
            rule(it.rule); km(it.originalKm); km(it.effectiveKm)
            date(it.originalDate); date(it.effectiveDate); require(it.revision >= 0)
        }
        batch.histories.forEach {
            rule(it.rule); km(it.originalKm); km(it.effectiveKm); km(it.odometerKm)
            date(it.originalDate); date(it.effectiveDate); date(it.performedDate)
            require((it.performedDate == null) == (it.performedZone == null))
            it.performedZone?.let(ZoneId::of)
            it.costBrl?.let { cost -> require(DecimalValues.normalize(cost, 2, false) == cost) }
            require(it.description.isNotBlank() && it.performedWork.isNotBlank())
        }
        batch.fuels.forEach {
            require(DecimalValues.normalize(it.totalBrl, 2) == it.totalBrl)
            require(DecimalValues.normalize(it.enteredQuantity, 3) == it.enteredQuantity)
            it.observed.validate()
        }
        (batch.rides + batch.rideChanges).forEach {
            it.started.validate(); it.ended?.validate()
            require(it.algorithmVersion.isNotBlank() && it.revision >= 0)
            require(if (it.state == RideState.ENDED) it.activeSlot == null && it.ended != null
                else it.activeSlot == 1 && it.ended == null)
            require(it.ended == null || it.ended.epochMillis >= it.started.epochMillis)
        }
        batch.samples.forEach {
            it.observed.validate()
            require(it.sequence >= 0 && it.elapsedMillis >= 0 && it.clockEpoch.isNotBlank())
            require(it.latitude.isFinite() && it.latitude in -90.0..90.0)
            require(it.longitude.isFinite() && it.longitude in -180.0..180.0)
            require(it.accuracyMeters.isFinite() && it.accuracyMeters > 0 && it.accuracyMeters <= 25)
            require(it.speedMps.isFinite() && it.speedMps in 0.0..(200.0 / 3.6))
            require(it.speedAccuracyMps.isFinite() && it.speedAccuracyMps in 0.0..2.0)
        }
        batch.gaps.forEach {
            it.started.validate(); it.ended?.validate()
            require(it.rejectedCount >= 0 && it.reason.isNotBlank())
        }
    }

    // All batch readings have been inserted, so neighbors include this command's other rows.
    // Strict temporal neighbors are separate from equal-time peers: a batch of equal-time
    // readings must still be checked against the readings before and after that timestamp.
    private fun validateReadingNeighbors(reading: OdometerReading) {
        val time = requireNotNull(reading.observed).epochMillis
        val km = requireNotNull(reading.kilometers)
        dao.previousConfirmedReading(reading.bikeId, time)?.let { previous ->
            require(requireNotNull(previous.kilometers) <= km) { "Owner odometer decreases" }
        }
        dao.nextConfirmedReading(reading.bikeId, time)?.let { next ->
            require(km <= requireNotNull(next.kilometers)) { "Owner odometer decreases" }
        }
        dao.simultaneousConfirmedReading(reading.bikeId, time, reading.id)?.let { peer ->
            require(km == peer.kilometers) { "Conflicting simultaneous readings" }
        }
    }

    private fun validateLinks(batch: WriteBatch) {
        batch.fuels.forEach { fuel -> fuel.readingId?.let { id ->
            val reading = requireNotNull(dao.reading(id))
            require(reading.bikeId == fuel.bikeId && reading.observed == fuel.observed)
        } }
        batch.samples.forEach { sample ->
            val ride = requireNotNull(dao.ride(sample.rideId))
            require(sample.bikeId == ride.bikeId && sample.observed.epochMillis >= ride.started.epochMillis)
            require(ride.ended == null || sample.observed.epochMillis <= ride.ended.epochMillis)
            dao.previousSample(sample.rideId, sample.sequence)?.let { previous ->
                if (previous.clockEpoch == sample.clockEpoch) require(sample.elapsedMillis > previous.elapsedMillis)
            }
            dao.nextSample(sample.rideId, sample.sequence)?.let { next ->
                if (next.clockEpoch == sample.clockEpoch) require(next.elapsedMillis > sample.elapsedMillis)
            }
        }
        batch.rideChanges.forEach { ride -> ride.ended?.let { end ->
            // Start is immutable and new samples already checked above. Only a new end
            // boundary can exclude existing samples; checkpoints need no history query.
            require(!dao.hasSampleAfter(ride.id, end.epochMillis))
        } }
    }
}

// Length-delimited, type-tagged encoding: null, strings and nested lists cannot collide.
// Field order is explicit and versioned with schema/code; operation receipts remain durable.
private fun fingerprint(fields: List<Any?>): String {
    val bytes = ByteArrayOutputStream()
    val output = DataOutputStream(bytes)
    fun encode(value: Any?) {
        when (value) {
            null -> output.writeByte(0)
            is List<*> -> { output.writeByte(1); output.writeInt(value.size); value.forEach { encode(it) } }
            is RuleValues -> { output.writeByte(2); encode(value.fields()) }
            is Observation -> { output.writeByte(3); encode(value.fields()) }
            else -> {
                output.writeByte(4)
                val text = "${value.javaClass.name}:${value}".toByteArray(Charsets.UTF_8)
                output.writeInt(text.size); output.write(text)
            }
        }
    }
    encode(fields)
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
