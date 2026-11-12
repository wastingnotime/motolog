package org.wastingnotime.motolog.domain.fuel

import org.wastingnotime.motolog.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Exact normalized operands are durable; scale-12 HALF_UP division is never display-rounded input. */
data class FuelValues(val mode: FuelMode, val totalBrl: String, val enteredQuantity: String) {
    val liters: BigDecimal get() = if (mode == FuelMode.LITERS) BigDecimal(enteredQuantity)
        else DecimalValues.derive(totalBrl, enteredQuantity)
    val unitPrice: BigDecimal get() = if (mode == FuelMode.UNIT_PRICE) BigDecimal(enteredQuantity)
        else DecimalValues.derive(totalBrl, enteredQuantity)
    val totalDisplay: String get() = BigDecimal(totalBrl).setScale(2).toPlainString()
    val litersDisplay: String get() = liters.setScale(3, RoundingMode.HALF_UP).toPlainString()
    val unitPriceDisplay: String get() = unitPrice.setScale(3, RoundingMode.HALF_UP).toPlainString()

    companion object {
        fun parse(mode: FuelMode, total: String, quantity: String): FuelValues {
            val normalizedTotal = try { DecimalValues.normalize(total, 2) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Total paid must be positive BRL, up to 12 whole digits and two decimal places.") }
            val normalizedQuantity = try { DecimalValues.normalize(quantity, 3) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Liters / price per liter must be positive, up to 12 whole digits and three decimal places.") }
            val derived = DecimalValues.derive(normalizedTotal, normalizedQuantity)
            require(derived.signum() > 0 && derived < BigDecimal("1000000000000")) {
                "Derived value is outside the supported range at scale 12. Review the entered values."
            }
            return FuelValues(mode, normalizedTotal, normalizedQuantity)
        }
    }
}

object OwnerObservation {
    fun kilometers(input: String): Long {
        require(input.matches(Regex("[0-9]{1,12}"))) { "Odometer must be nonnegative whole km, up to 12 digits." }
        return input.toLong()
    }

    /** Offset is explicit so DST overlaps are resolved by the owner and gaps cannot be shifted silently. */
    fun parse(local: String, zoneId: String, offset: String): Observation {
        try {
            val dateTime = LocalDateTime.parse(local)
            val zone = ZoneId.of(zoneId)
            val chosenOffset = ZoneOffset.of(offset)
            require(chosenOffset in zone.rules.getValidOffsets(dateTime))
            return Observation(dateTime.toInstant(chosenOffset).toEpochMilli(), zone.id, chosenOffset.totalSeconds)
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid local timestamp (YYYY-MM-DDTHH:MM:SS), zone and matching UTC offset. Resolve ambiguous historical times explicitly.")
        }
    }

    fun validate(observed: Observation, now: Long) {
        observed.validate()
        require(observed.epochMillis <= now) { "Observation is in the future. Review the timestamp and zone." }
    }
}
