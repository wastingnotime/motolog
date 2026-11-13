package org.wastingnotime.motolog.data.repository

import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.model.*
import org.wastingnotime.motolog.domain.ride.*
import java.time.Instant
import java.time.ZoneId

/** All capture transactions touch one cursor, indexed rows, and at most two local days. */
class RideRepository(private val db: MotoLogDatabase) {
    private val dao get() = db.dao()
    data class View(val active: RideSession?, val cursor: RecordingCursor?, val days: List<RideDay>,
        val recent: List<RideSession>, val gaps: List<RideGap>) {
        val millis get() = days.sumOf { it.eligibleMillis }
        val meters get() = days.sumOf { it.meters }
        val averageKmh get() = if (millis == 0L) null else meters / millis * 3600
    }
    fun status(): Pair<RideSession?, RecordingCursor?> = db.runInTransaction<Pair<RideSession?, RecordingCursor?>> {
        val active = dao.activeRide()
        active to active?.let { dao.cursor(it.id) }
    }
    fun view(bike: String, date: String): View = db.runInTransaction<View> {
        val active = dao.activeRide()
        val recent = dao.recentRides(bike)
        View(active, active?.let { dao.cursor(it.id) }, dao.days(bike, date), recent,
            (active ?: recent.firstOrNull())?.let { dao.recentGaps(it.id) }.orEmpty())
    }

    fun history(bike: String, offset: Int = 0): List<RideSession> = dao.ridePage(bike, offset)
    data class Detail(val ride: RideSession, val total: RideTotal?, val gapCount: Long,
        val rejected: Long, val cursor: RecordingCursor?) {
        val averageKmh get() = total?.takeIf { it.eligibleMillis > 0 }?.let { it.meters / it.eligibleMillis * 3600 }
    }
    fun detail(id: String): Detail = db.runInTransaction<Detail> {
        Detail(requireNotNull(dao.ride(id)), dao.total(id), dao.gapCount(id), dao.rejectedCount(id), dao.cursor(id))
    }
    fun events(id: String, after: Long = -1): List<RideSample> = dao.speedEvents(id, after)
    fun gaps(id: String, offset: Int = 0): List<RideGap> = dao.gapPage(id, offset)

    fun start(operation: String, bike: String, at: Observation, epoch: String, minimumElapsed: Long = 0): RideSession = db.runInTransaction<RideSession> {
        at.validate()
        require(operation.isNotBlank())
        val receiptPrefix = "ride-start-v1:${bike.length}:$bike:"
        dao.operation(operation)?.let {
            require(it.fingerprint.startsWith(receiptPrefix)) { "Operation ID reused" }
            return@runInTransaction requireNotNull(dao.ride(it.fingerprint.removePrefix(receiptPrefix)))
        }
        dao.activeRide()?.let {
            require(it.state == RideState.ACTIVE) { "Resume or end the interrupted ride first." }
            require(it.bikeId == bike) { "Active ride belongs to a different motorcycle." }
            val manual = if (it.ownership == RideOwnership.AUTOMATIC)
                it.copy(ownership = RideOwnership.MANUAL, revision = it.revision + 1).also(dao::update) else it
            dao.insert(AppliedOperation(operation, receiptPrefix + manual.id, at.epochMillis))
            return@runInTransaction manual
        }
        val ride = RideSession(operation, bike, RideState.ACTIVE, 1, RideOwnership.MANUAL, at, null,
            RideMetrics.VERSION, at.epochMillis)
        dao.insert(ride)
        dao.project(RideTotal(ride.id, ride.algorithmVersion))
        dao.checkpoint(RecordingCursor(ride.id, clockEpoch = epoch, minimumElapsed = minimumElapsed, last = at, gap = at, gapReason = "AWAITING_FIX"))
        addDay(bike, at, gaps = 1)
        dao.insert(AppliedOperation(operation, receiptPrefix + ride.id, at.epochMillis))
        ride
    }

    fun resume(id: String, at: Observation, epoch: String, minimumElapsed: Long = 0) = db.runInTransaction {
        val ride = requireNotNull(dao.activeRide()?.takeIf { it.id == id }) { "Ride has ended. Reload recording state." }
        require(ride.algorithmVersion == RideMetrics.VERSION) { "Unsupported recording version. End at last observation." }
        if (ride.state == RideState.ACTIVE) return@runInTransaction
        val cursor = cursor(ride)
        dao.update(ride.copy(state = RideState.ACTIVE, ownership = RideOwnership.MANUAL, revision = ride.revision + 1))
        dao.checkpoint(openGap(ride, cursor, "RESUMED").copy(clockEpoch = epoch, lastElapsed = null, minimumElapsed = minimumElapsed))
    }

    /** Called on process initialization, service destruction, revoked permission or capture failure. */
    fun interrupt(reason: String, at: Observation) = db.runInTransaction {
        val ride = dao.activeRide() ?: return@runInTransaction
        if (ride.state == RideState.INTERRUPTED) return@runInTransaction
        val cursor = cursor(ride)
        dao.checkpoint(openGap(ride, cursor, reason))
        dao.update(ride.copy(state = RideState.INTERRUPTED, revision = ride.revision + 1))
    }

    fun stop(id: String, at: Observation, lastObservation: Boolean = false) = db.runInTransaction {
        val ride = dao.activeRide()?.takeIf { it.id == id } ?: return@runInTransaction
        var cursor = cursor(ride)
        at.validate()
        val requestedEnd = if (lastObservation || ride.state == RideState.INTERRUPTED) cursor.last else at
        // The last arrival can itself follow a backward wall-clock change. Use the
        // indexed latest observed usable sample so End encloses every saved sample.
        val latestUsable = dao.latestObservedSample(ride.id)?.observed ?: ride.started
        val boundary = if (cursor.last.epochMillis > latestUsable.epochMillis) cursor.last else latestUsable
        val backwards = requestedEnd.epochMillis < boundary.epochMillis
        val end = if (backwards) boundary else requestedEnd
        if (backwards) {
            // Retain the actual backward observation separately from the safe end.
            // This discontinuity is evidence only: no segment or eligible time is added.
            dao.insert(RideGap("${ride.id}:stop-clock:${ride.revision}", ride.bikeId, ride.id,
                "WALL_CLOCK_CHANGE_AT_STOP", 0, boundary, requestedEnd))
            addDay(ride.bikeId, boundary, gaps = 1)
        } else if (cursor.gap == null && end != cursor.last) cursor = openGap(ride, cursor, "STOP_TAIL")
        closeGap(ride, cursor, end)
        dao.checkpoint(cursor.copy(lastSampleId = null, lastEventSpeed = null, gap = null, gapReason = null, tentativeGap = false))
        dao.update(ride.copy(state = RideState.ENDED, activeSlot = null, ended = end, revision = ride.revision + 1))
    }

    fun heartbeat(at: Observation, elapsed: Long, epoch: String) = db.runInTransaction {
        val ride = dao.activeRide()?.takeIf { it.state == RideState.ACTIVE } ?: return@runInTransaction
        val cursor = cursor(ride)
        if (cursor.gap == null && (epoch != cursor.clockEpoch || cursor.lastElapsed?.let { elapsed - it > 10_000 } == true)) {
            // Delivery can be ten seconds late. Expose missing delivery now, but a late
            // timestamp-valid pair can still prove continuous coverage and retract this marker.
            dao.checkpoint(openGap(ride, cursor, "MISSING_FIX").copy(lastSampleId = cursor.lastSampleId,
                lastEventSpeed = cursor.lastEventSpeed, tentativeGap = true))
        }
    }

    /** Stable callback ID makes transaction retry a no-op, including raw rows and daily increments. */
    fun accept(id: String, fix: RideMetrics.Fix) = db.runInTransaction {
        val ride = dao.activeRide()?.takeIf { it.state == RideState.ACTIVE } ?: return@runInTransaction
        val receipt = "fix:${ride.id}:$id"
        if (dao.operation(receipt) != null) return@runInTransaction
        fix.observed.validate()
        var cursor = cursor(ride)
        val quality = RideMetrics.rejection(fix)
        val order = if (fix.clockEpoch == cursor.clockEpoch && cursor.lastElapsed != null && fix.elapsed <= cursor.lastElapsed!!) "NON_INCREASING_TIME" else null
        val rejection = quality ?: order ?: when {
            fix.clockEpoch == cursor.clockEpoch && fix.elapsed < cursor.minimumElapsed -> "BEFORE_CAPTURE"
            fix.observed.epochMillis < ride.started.epochMillis -> "WALL_CLOCK_BEFORE_START"
            else -> null
        }
        dao.insert(QualityFix(receipt, ride.id, cursor.nextSequence, fix.observed, fix.clockEpoch,
            fix.elapsed, fix.receivedElapsed, fix.latitude.toString(), fix.longitude.toString(),
            fix.accuracy?.toString(), fix.speed?.toString(), fix.speedAccuracy?.toString(), rejection))
        if (rejection != null) {
            cursor = openGap(ride, cursor, rejection)
            addDay(ride.bikeId, fix.observed, rejected = 1)
            dao.checkpoint(cursor.copy(nextSequence = cursor.nextSequence + 1, rejected = cursor.rejected + 1))
        } else {
            val previous = cursor.lastSampleId?.let(dao::sample)
            val pair = previous?.let { RideMetrics.pair(it.fix(), fix) }
            if (cursor.tentativeGap && pair?.reason == null && pair != null) {
                addDay(ride.bikeId, cursor.gap!!, gaps = -1)
                cursor = cursor.copy(gap = null, gapReason = null, tentativeGap = false)
            }
            if (pair?.reason != null) cursor = openGap(ride, cursor, pair.reason)
            if (pair != null && pair.reason == null) {
                val total = requireNotNull(dao.total(ride.id))
                dao.project(total.copy(meters = total.meters + pair.meters, eligibleMillis = total.eligibleMillis + pair.millis))
                RideMetrics.allocate(previous.fix(), fix, pair).forEach { allocation ->
                    val old = dao.day(ride.bikeId, allocation.date, allocation.zone) ?: RideDay(ride.bikeId, allocation.date, allocation.zone)
                    dao.project(old.copy(meters = old.meters + allocation.meters, eligibleMillis = old.eligibleMillis + allocation.millis))
                }
            }
            val event = RideMetrics.speedEvent(cursor.lastEventSpeed, fix.speed!!)
            val sampleId = "$receipt:usable"
            dao.insert(RideSample(sampleId, ride.bikeId, ride.id, cursor.nextSequence, fix.observed,
                fix.clockEpoch, fix.elapsed, fix.latitude, fix.longitude, fix.accuracy!!, fix.speed,
                fix.speedAccuracy!!, event, fix.observed.epochMillis))
            closeGap(ride, cursor, fix.observed)
            dao.checkpoint(cursor.copy(nextSequence = cursor.nextSequence + 1, lastSampleId = sampleId,
                lastEventSpeed = if (event) fix.speed else cursor.lastEventSpeed, clockEpoch = fix.clockEpoch,
                lastElapsed = fix.elapsed, last = fix.observed, gap = null, gapReason = null, rejected = 0, tentativeGap = false))
        }
        dao.insert(AppliedOperation(receipt, "quality-fix-v1", fix.observed.epochMillis))
    }

    private fun cursor(ride: RideSession): RecordingCursor = dao.cursor(ride.id) ?: run {
        // Foundation/pre-version-4 interrupted rides have no reducer. Never bridge their samples.
        val sample = dao.lastSample(ride.id)
        RecordingCursor(ride.id, nextSequence = (sample?.sequence ?: -1) + 1,
            clockEpoch = sample?.clockEpoch ?: "legacy", last = sample?.observed ?: ride.started,
            gap = sample?.observed ?: ride.started, gapReason = "PROCESS_RESTART")
    }
    private fun openGap(ride: RideSession, cursor: RecordingCursor, reason: String): RecordingCursor {
        if (cursor.gap != null) return cursor.copy(lastSampleId = null, lastEventSpeed = null, tentativeGap = false,
            gapReason = if (cursor.gapReason == "AWAITING_FIX" || cursor.tentativeGap) reason else cursor.gapReason)
        addDay(ride.bikeId, cursor.last, gaps = 1)
        return cursor.copy(lastSampleId = null, lastEventSpeed = null, gap = cursor.last, gapReason = reason)
    }
    private fun closeGap(ride: RideSession, cursor: RecordingCursor, end: Observation) {
        cursor.gap?.let { dao.insert(RideGap("${ride.id}:gap:${cursor.nextSequence}:${ride.revision}", ride.bikeId,
            ride.id, cursor.gapReason ?: "MISSING_FIX", cursor.rejected, it, end)) }
    }
    private fun addDay(bike: String, at: Observation, gaps: Long = 0, rejected: Long = 0) {
        val date = Instant.ofEpochMilli(at.epochMillis).atZone(ZoneId.of(at.zoneId)).toLocalDate().toString()
        val old = dao.day(bike, date, at.zoneId) ?: RideDay(bike, date, at.zoneId)
        dao.project(old.copy(gaps = old.gaps + gaps, rejected = old.rejected + rejected))
    }
    private fun RideSample.fix() = RideMetrics.Fix(observed, clockEpoch, elapsedMillis, elapsedMillis,
        latitude, longitude, accuracyMeters, speedMps, speedAccuracyMps)
}
