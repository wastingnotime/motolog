package org.wastingnotime.motolog.domain.fuel

import org.wastingnotime.motolog.data.local.FuelEvent
import org.wastingnotime.motolog.data.local.OdometerReading
import org.wastingnotime.motolog.domain.model.Certainty

enum class FuelUnavailable(val message: String) {
    NO_REFUEL("No refuel recorded."),
    REFUEL_READING("Latest refuel has no usable confirmed owner reading. Review historical evidence privately."),
    AMBIGUOUS_REFUEL("Refuels share the latest timestamp. Review historical evidence privately."),
    NO_CURRENT("No confirmed owner reading. Update odometer."),
    FUTURE("An observation is in the future. Review the timestamp and device clock."),
    STALE("Owner reading is older than 24 hours. Update odometer."),
    BEFORE_REFUEL("Owner reading predates the latest refuel. Update odometer."),
    INVALID_SEQUENCE("Owner readings are ambiguous or decrease. Review historical evidence privately; retain uncertain evidence in local setup."),
}

data class FuelStatus(val latestRefuel: FuelEvent?, val refuelReading: OdometerReading?,
    val current: OdometerReading?, val currentAgeMillis: Long?, val kilometersSince: Long?,
    val unavailable: FuelUnavailable?)

object FuelProjection {
    const val FRESH_MILLIS = 24 * 60 * 60 * 1000L

    /** GPS is deliberately absent. Recompute from occurred-at evidence on each read, including backdates. */
    fun evaluate(bikeId: String, fuels: List<FuelEvent>, readings: List<OdometerReading>, now: Long): FuelStatus {
        val events = fuels.filter { it.bikeId == bikeId }
            .sortedWith(compareByDescending<FuelEvent> { it.observed.epochMillis }.thenByDescending { it.id })
        val confirmed = readings.filter { it.bikeId == bikeId && it.certainty == Certainty.CONFIRMED &&
            it.kilometers != null && it.observed != null }
            .sortedWith(compareBy<OdometerReading> { it.observed!!.epochMillis }.thenBy { it.id })
        val latest = events.firstOrNull()
        val refuelReading = latest?.let { event -> readings.find { it.id == event.readingId &&
            it.bikeId == bikeId && it.observed == event.observed } }
        val current = confirmed.lastOrNull()
        val age = current?.let { now - it.observed!!.epochMillis }
        val reason = when {
            latest == null -> FuelUnavailable.NO_REFUEL
            latest.observed.epochMillis > now || (age != null && age < 0) -> FuelUnavailable.FUTURE
            events.count { it.observed.epochMillis == latest.observed.epochMillis } > 1 -> FuelUnavailable.AMBIGUOUS_REFUEL
            current == null -> FuelUnavailable.NO_CURRENT
            refuelReading?.kilometers == null || refuelReading.certainty != Certainty.CONFIRMED -> FuelUnavailable.REFUEL_READING
            current.observed!!.epochMillis < latest.observed.epochMillis -> FuelUnavailable.BEFORE_REFUEL
            confirmed.any { it.kilometers!! !in 0..999_999_999_999L } || confirmed.zipWithNext().any { (a, b) ->
                a.kilometers!! > b.kilometers!! ||
                    (a.observed!!.epochMillis == b.observed!!.epochMillis && a.kilometers != b.kilometers)
            } -> FuelUnavailable.INVALID_SEQUENCE
            age!! > FRESH_MILLIS -> FuelUnavailable.STALE
            else -> null
        }
        return FuelStatus(latest, refuelReading, current, age,
            if (reason == null) current!!.kilometers!! - refuelReading!!.kilometers!! else null, reason)
    }
}
