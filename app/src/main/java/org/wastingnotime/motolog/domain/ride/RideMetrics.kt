package org.wastingnotime.motolog.domain.ride

import org.wastingnotime.motolog.domain.model.Observation
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

/** Version captured on every ride. Native speed is never inferred from positions. */
object RideMetrics {
    const val VERSION = "gnss-v1-1s-25m-10s-200kmh-2mps-event5"
    data class Fix(val observed: Observation, val clockEpoch: String, val elapsed: Long,
        val receivedElapsed: Long, val latitude: Double, val longitude: Double,
        val accuracy: Double?, val speed: Double?, val speedAccuracy: Double?)
    data class PairResult(val reason: String?, val meters: Double = 0.0, val millis: Long = 0)
    data class Allocation(val date: String, val zone: String, val meters: Double, val millis: Long)

    fun rejection(fix: Fix): String? = with(fix) {
        when {
            !latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 -> "COORDINATES"
            accuracy == null || !accuracy.isFinite() || accuracy <= 0 || accuracy > 25 -> "HORIZONTAL_ACCURACY"
            elapsed < 0 || receivedElapsed < elapsed || receivedElapsed - elapsed > 10_000 -> "FIX_AGE"
            speed == null || !speed.isFinite() || speed < 0 || speed > 200.0 / 3.6 -> "NATIVE_SPEED"
            speedAccuracy == null || !speedAccuracy.isFinite() || speedAccuracy !in 0.0..2.0 -> "SPEED_ACCURACY"
            else -> null
        }
    }

    // WGS84 inverse distance (Vincenty), with spherical fallback for near-antipodal points.
    // Endpoints near antipodes necessarily fail the ten-second plausibility limit.
    fun distance(a: Fix, b: Fix): Double {
        val major = 6378137.0; val flattening = 1 / 298.257223563; val minor = (1 - flattening) * major
        val u1 = atan((1 - flattening) * tan(Math.toRadians(a.latitude)))
        val u2 = atan((1 - flattening) * tan(Math.toRadians(b.latitude)))
        val l = Math.toRadians(b.longitude - a.longitude)
        var lambda = l
        repeat(100) {
            val sinSigma = hypot(cos(u2) * sin(lambda), cos(u1) * sin(u2) - sin(u1) * cos(u2) * cos(lambda))
            if (sinSigma == 0.0) return 0.0
            val cosSigma = sin(u1) * sin(u2) + cos(u1) * cos(u2) * cos(lambda)
            val sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cos(u1) * cos(u2) * sin(lambda) / sinSigma
            val cosSqAlpha = 1 - sinAlpha * sinAlpha
            val cos2Sigma = if (cosSqAlpha < 1e-15) 0.0 else cosSigma - 2 * sin(u1) * sin(u2) / cosSqAlpha
            val c = flattening / 16 * cosSqAlpha * (4 + flattening * (4 - 3 * cosSqAlpha))
            val next = l + (1 - c) * flattening * sinAlpha * (sigma + c * sinSigma * (cos2Sigma + c * cosSigma * (-1 + 2 * cos2Sigma * cos2Sigma)))
            if (abs(next - lambda) < 1e-12) {
                val uSq = cosSqAlpha * (major * major - minor * minor) / (minor * minor)
                val aa = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
                val bb = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
                val delta = bb * sinSigma * (cos2Sigma + bb / 4 * (cosSigma * (-1 + 2 * cos2Sigma * cos2Sigma) -
                    bb / 6 * cos2Sigma * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2Sigma * cos2Sigma)))
                return minor * aa * (sigma - delta)
            }
            lambda = next
        }
        val dlat = Math.toRadians(b.latitude - a.latitude); val dlon = l
        val h = sin(dlat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dlon / 2).pow(2)
        return 6371008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun pair(a: Fix, b: Fix): PairResult {
        if (a.clockEpoch != b.clockEpoch) return PairResult("CLOCK_EPOCH")
        val elapsed = b.elapsed - a.elapsed
        if (elapsed <= 0) return PairResult("NON_INCREASING_TIME")
        if (elapsed > 10_000) return PairResult("MISSING_FIX")
        val wall = b.observed.epochMillis - a.observed.epochMillis
        if (wall <= 0 || abs(wall - elapsed) > 2_000) return PairResult("WALL_CLOCK_CHANGE")
        val meters = distance(a, b)
        if (meters / elapsed * 3600 > 200) return PairResult("IMPLIED_SPEED")
        return PairResult(null, if (a.speed!! <= 3.0 / 3.6 && b.speed!! <= 3.0 / 3.6) 0.0 else meters, elapsed)
    }

    fun speedEvent(last: Double?, speed: Double) = last == null || abs(speed - last) * 3.6 >= 5.0 - 1e-10

    /** Zone changes apply at b, so [a,b) uses a's zone. Durations sum exactly. */
    fun allocate(a: Fix, b: Fix, pair: PairResult): List<Allocation> {
        require(pair.reason == null && pair.millis > 0)
        val zone = ZoneId.of(a.observed.zoneId)
        val start = a.observed.epochMillis; val end = b.observed.epochMillis
        var cursor = start; var used = 0L; var usedMeters = 0.0
        return buildList {
            while (cursor < end) {
                val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val next = minOf(end, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val cumulative = if (next == end) pair.millis else ((next - start).toDouble() / (end - start) * pair.millis).roundToLong()
                val meters = if (next == end) pair.meters - usedMeters else pair.meters * (cumulative - used).toDouble() / pair.millis
                add(Allocation(day.toString(), zone.id, meters, cumulative - used))
                used = cumulative; usedMeters += meters; cursor = next
            }
        }
    }
}

fun observationAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Observation =
    Observation(millis, zone.id, zone.rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds)
