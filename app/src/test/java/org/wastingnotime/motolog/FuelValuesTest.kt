package org.wastingnotime.motolog

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.domain.fuel.*
import org.wastingnotime.motolog.domain.model.*

class FuelValuesTest {
    @Test fun equivalentModesAndRepeatingDivisionRetainOperands() {
        val liters = FuelValues.parse(FuelMode.LITERS, "100.00", "20.000")
        val price = FuelValues.parse(FuelMode.UNIT_PRICE, "100.00", "5.000")
        assertEquals(0, liters.liters.compareTo(price.liters))
        assertEquals(0, liters.unitPrice.compareTo(price.unitPrice))
        assertEquals("100", liters.totalBrl)
        assertEquals("20", liters.enteredQuantity)
        assertEquals("5", price.enteredQuantity)
        val repeating = FuelValues.parse(FuelMode.UNIT_PRICE, "100.00", "6.000")
        assertEquals("16.666666666667", repeating.liters.toPlainString())
        assertEquals("16.667", repeating.litersDisplay)
        assertEquals("6.000", repeating.unitPriceDisplay)
        assertEquals("100.00", repeating.totalDisplay)
        assertEquals("6", repeating.enteredQuantity)
        assertEquals("0.125", FuelValues.parse(FuelMode.LITERS, "1", "8").unitPriceDisplay)
    }

    @Test fun invalidOperandsPrecisionAndDerivedRangeNeverRoundIntoValidity() {
        FuelMode.entries.forEach { mode ->
            listOf("", "0", "-1", "NaN", "Infinity", "1e2", "1,00", "1.001", "1000000000000", " 1").forEach { input ->
                assertThrows(IllegalArgumentException::class.java) { FuelValues.parse(mode, input, "1") }
            }
            listOf("", "0", "-1", "NaN", "Infinity", "1e2", "1.0001", "1000000000000").forEach { input ->
                assertThrows(IllegalArgumentException::class.java) { FuelValues.parse(mode, "1", input) }
            }
            assertThrows(IllegalArgumentException::class.java) { FuelValues.parse(mode, "999999999999.99", "0.001") }
            assertThrows(IllegalArgumentException::class.java) { FuelValues.parse(mode, "0.01", "999999999999.999") }
            assertEquals("999999999999.99", FuelValues.parse(mode, "999999999999.99", "1").totalBrl)
            assertEquals("0.001", FuelValues.parse(mode, "0.01", "0.001").enteredQuantity)
        }
    }

    @Test fun ownerReadingRangeFutureZonesAndDstAreExplicit() {
        assertEquals(0, OwnerObservation.kilometers("0"))
        assertEquals(999_999_999_999L, OwnerObservation.kilometers("999999999999"))
        listOf("", "-1", "1.0", "1000000000000", "9999999999999999999999", "NaN").forEach {
            assertThrows(IllegalArgumentException::class.java) { OwnerObservation.kilometers(it) }
        }
        val first = OwnerObservation.parse("2026-11-01T01:30:00", "America/New_York", "-04:00")
        val second = OwnerObservation.parse("2026-11-01T01:30:00", "America/New_York", "-05:00")
        assertEquals(3_600_000L, second.epochMillis - first.epochMillis)
        listOf(Triple("bad", "UTC", "Z"), Triple("2026-03-08T02:30:00", "America/New_York", "-05:00"),
            Triple("2026-01-15T12:00:00", "UTC", "+01:00"), Triple("2026-01-15T12:00:00", "Invalid/Zone", "Z")).forEach { (local, zone, offset) ->
            assertThrows(IllegalArgumentException::class.java) { OwnerObservation.parse(local, zone, offset) }
        }
        OwnerObservation.validate(first, first.epochMillis)
        assertThrows(IllegalArgumentException::class.java) { OwnerObservation.validate(first, first.epochMillis - 1) }
    }
}
