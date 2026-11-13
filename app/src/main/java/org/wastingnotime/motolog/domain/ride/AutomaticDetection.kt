package org.wastingnotime.motolog.domain.ride

/** Timestamp-based windows. Invalid evidence resets continuity; missing GPS is never idle. */
object AutomaticDetection {
    const val VERSION = "auto-v1-start6-15s-4fix-6s-idle3-300s-10s"
    data class Window(val fixes: List<RideMetrics.Fix> = emptyList(), val idleSince: Long? = null)
    data class Result(val window: Window, val start: Boolean = false, val stop: Boolean = false)
    fun reduce(window: Window, fix: RideMetrics.Fix, recording: Boolean): Result {
        if (RideMetrics.rejection(fix) != null) return Result(Window())
        val last = window.fixes.lastOrNull()
        if (last != null && last.clockEpoch == fix.clockEpoch && fix.elapsed <= last.elapsed) return Result(Window())
        val continuous = last != null && RideMetrics.pair(last, fix).reason == null &&
            fix.elapsed - last.elapsed <= if (recording) 10_000 else 6_000
        if (recording) {
            val since = if (fix.speed!! <= 3.0 / 3.6)
                if (continuous) window.idleSince ?: fix.elapsed else fix.elapsed else null
            return Result(Window(listOf(fix), since), stop = since != null && fix.elapsed - since >= 300_000)
        }
        if (fix.speed!! < 6.0 / 3.6) return Result(Window())
        // More than 256 deliveries inside 15 seconds is outside requested cadence.
        // Reset safely rather than grow unbounded or discard committed candidate evidence.
        val fixes = if (continuous && window.fixes.size < 256) window.fixes + fix else listOf(fix)
        return Result(Window(fixes), start = fixes.size >= 4 && fix.elapsed - fixes.first().elapsed >= 15_000)
    }
}
