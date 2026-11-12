package org.wastingnotime.motolog.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

// No GPS alternative exists in ReadingSource: a GPS fix is a RideSample only.
enum class ReadingSource { OWNER, OWNER_IMPORT }
enum class Certainty { CONFIRMED, UNCERTAIN, UNKNOWN }
enum class SourceKind { MANUAL, CUSTOM, UNVERIFIED }
enum class ScheduleKind { INTERVAL, MILESTONE, CONDITION, UNKNOWN }
enum class MaintenanceKind { PREVENTIVE, PROGRAMMED, CORRECTIVE, UNKNOWN }
enum class OccurrenceState { PENDING, COMPLETED, POSTPONED }
enum class RideState { ACTIVE, INTERRUPTED, ENDED }
enum class RideOwnership { MANUAL, AUTOMATIC }
enum class FuelMode { LITERS, UNIT_PRICE }

data class Observation(val epochMillis: Long, val zoneId: String, val offsetSeconds: Int) {
    fun validate() {
        val zone = ZoneId.of(zoneId)
        require(zone.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds == offsetSeconds) {
            "Offset must match the observed zone and instant"
        }
    }
    internal fun fields(): List<Any?> = listOf(epochMillis, zoneId, offsetSeconds)
}

// Copied into occurrences and history. Later source/template edits cannot rewrite it.
data class RuleValues(
    val action: String,
    val component: String,
    val sourceKind: SourceKind,
    val sourceIdentity: String? = null,
    val edition: String? = null,
    val locator: String? = null,
    val baselineRevision: Int? = null,
    val overrideRevision: Int? = null,
    val scheduleKind: ScheduleKind = ScheduleKind.UNKNOWN,
    val intervalKm: Long? = null,
    val intervalMonths: Int? = null,
    val coverageNote: String? = null,
) {
    internal fun fields(): List<Any?> = listOf(action, component, sourceKind, sourceIdentity,
        edition, locator, baselineRevision, overrideRevision, scheduleKind, intervalKm,
        intervalMonths, coverageNote)
}

object DecimalValues {
    // Canonical input has no exponent, sign, commas, leading zeros or redundant trailing zeros.
    fun normalize(input: String, scale: Int, positive: Boolean = true): String {
        require(input.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,$scale})?"))) { "Invalid precision" }
        val value = BigDecimal(input)
        require(if (positive) value.signum() > 0 else value.signum() >= 0)
        return value.stripTrailingZeros().toPlainString()
    }
    fun derive(total: String, quantity: String): BigDecimal =
        BigDecimal(total).divide(BigDecimal(quantity), 12, RoundingMode.HALF_UP)
}
