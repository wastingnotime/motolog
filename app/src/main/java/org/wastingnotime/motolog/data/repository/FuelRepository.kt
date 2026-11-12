package org.wastingnotime.motolog.data.repository

import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.model.*

data class RefuelCommand(val operationId: String, val bikeId: String, val mode: FuelMode,
    val totalPaid: String, val quantity: String, val odometer: String, val observed: Observation)
data class ReadingCommand(val operationId: String, val bikeId: String, val odometer: String, val observed: Observation)

class FuelRepository(private val db: MotoLogDatabase) {
    private val store = LocalStore(db)
    fun snapshot(): WriteBatch = store.snapshot()

    fun refuel(command: RefuelCommand, now: Long): Boolean = with(command) {
        val values = FuelValues.parse(mode, totalPaid, quantity)
        val km = OwnerObservation.kilometers(odometer)
        store.command(operationId, listOf("refuel-v1", bikeId, mode, values.totalBrl, values.enteredQuantity, km, observed), now) {
            OwnerObservation.validate(observed, now)
            require(db.dao().bikeRows().any { it.id == bikeId }) { "Register the motorcycle first." }
            val readingId = "$operationId:owner-reading"
            WriteBatch(readings = listOf(OdometerReading(readingId, bikeId, km, Certainty.CONFIRMED,
                ReadingSource.OWNER, observed, now)), fuels = listOf(FuelEvent("$operationId:refuel", bikeId,
                readingId, mode, values.totalBrl, values.enteredQuantity, observed, now)))
        }
    }

    fun updateOdometer(command: ReadingCommand, now: Long): Boolean = with(command) {
        val km = OwnerObservation.kilometers(odometer)
        store.command(operationId, listOf("owner-reading-v1", bikeId, km, observed), now) {
            OwnerObservation.validate(observed, now)
            require(db.dao().bikeRows().any { it.id == bikeId }) { "Register the motorcycle first." }
            WriteBatch(readings = listOf(OdometerReading("$operationId:owner-reading", bikeId, km,
                Certainty.CONFIRMED, ReadingSource.OWNER, observed, now)))
        }
    }
}
