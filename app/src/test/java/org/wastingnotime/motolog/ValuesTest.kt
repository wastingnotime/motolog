package org.wastingnotime.motolog

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.domain.model.*

class ValuesTest {
    @Test fun decimalPrecisionRetainsExactOperands() {
        assertEquals("100", DecimalValues.normalize("100.00", 2))
        assertEquals("6", DecimalValues.normalize("6.000", 3))
        assertEquals("16.666666666667", DecimalValues.derive("100", "6").toPlainString())
        assertEquals("0", DecimalValues.normalize("0.00", 2, false))
        listOf("0", "-1", "NaN", "Infinity", "1e3", "1.0001", "1000000000000").forEach {
            assertThrows(IllegalArgumentException::class.java) { DecimalValues.normalize(it, 3) }
        }
    }
    @Test fun observedZoneAndOffsetMustAgree() {
        Observation(1_700_000_000_000, "UTC", 0).validate()
        assertThrows(IllegalArgumentException::class.java) {
            Observation(1_700_000_000_000, "UTC", 3600).validate()
        }
    }
}
