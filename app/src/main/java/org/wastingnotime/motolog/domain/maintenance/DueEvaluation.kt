package org.wastingnotime.motolog.domain.maintenance

import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate

/** Branches stay explicit: a known due branch wins, an unknown one never proves currency. */
enum class BranchStatus { NOT_APPLICABLE, BELOW, DUE, UNKNOWN }
enum class DueStatus { DUE, NOT_DUE, UNKNOWN, MANUAL_REVIEW, UNSCHEDULED }
data class DueResult(val status: DueStatus, val mileage: BranchStatus, val calendar: BranchStatus,
    val manualReview: Boolean = false)
data class DueTargets(val km: Long? = null, val date: LocalDate? = null,
    val expectsKm: Boolean = km != null, val expectsDate: Boolean = date != null)
enum class RuleSupport { INTERVAL, CONDITIONAL_INTERVAL, OIL, OIL_FILTER, MANUAL_REVIEW }

class MilestoneReviewRequired : IllegalArgumentException(
    "Mileage jump requires review: more than 1,000 new milestones or a target outside the supported odometer range. " +
        "Existing obligations are retained. Review the reading and reconcile the adopted frontier before retrying.")

object DueEvaluation {
    const val MAX_NEW_MARKS = 1_000

    fun support(rule: RuleValues): RuleSupport {
        if (rule.scheduleKind == ScheduleKind.INTERVAL && rule.sourceKind != SourceKind.UNVERIFIED &&
            (rule.intervalKm != null || rule.intervalMonths != null)) return RuleSupport.INTERVAL
        // Exact reviewed fact identity/action/schedule, excluding explanatory display text only.
        fun verified(key: String): Boolean {
            val fact = FazerInventory.rules.getValue(key)
            return rule.copy(coverageNote = null) == fact.copy(coverageNote = null)
        }
        return when {
            verified("oil") -> RuleSupport.OIL
            verified("oil-filter") -> RuleSupport.OIL_FILTER
            listOf("brake-hoses", "air-filter", "chain-care").any(::verified) -> RuleSupport.CONDITIONAL_INTERVAL
            else -> RuleSupport.MANUAL_REVIEW
        }
    }

    fun evaluate(targets: DueTargets, currentKm: Long?, today: LocalDate,
        manualReview: Boolean = false, mileageFresh: Boolean = true): DueResult {
        require(currentKm == null || currentKm >= 0)
        fun <T : Comparable<T>> branch(expected: Boolean, target: T?, current: T?): BranchStatus = when {
            !expected -> BranchStatus.NOT_APPLICABLE
            target == null || current == null -> BranchStatus.UNKNOWN
            current >= target -> BranchStatus.DUE
            else -> BranchStatus.BELOW
        }
        val km = branch(targets.expectsKm, targets.km, currentKm).let {
            if (!mileageFresh && it == BranchStatus.BELOW) BranchStatus.UNKNOWN else it
        }
        val date = branch(targets.expectsDate, targets.date, today)
        val branches = listOf(km, date)
        val status = when {
            BranchStatus.DUE in branches -> DueStatus.DUE
            manualReview -> DueStatus.MANUAL_REVIEW
            BranchStatus.UNKNOWN in branches -> DueStatus.UNKNOWN
            branches.all { it == BranchStatus.NOT_APPLICABLE } -> DueStatus.UNSCHEDULED
            else -> DueStatus.NOT_DUE
        }
        return DueResult(status, km, date, manualReview)
    }

    fun interval(rule: RuleValues, anchorKm: Long?, anchorDate: LocalDate?): DueTargets = DueTargets(
        if (rule.intervalKm != null && anchorKm != null) Math.addExact(anchorKm, rule.intervalKm) else null,
        if (rule.intervalMonths != null && anchorDate != null) anchorDate.plusMonths(rule.intervalMonths.toLong()) else null,
        rule.intervalKm != null, rule.intervalMonths != null)

    fun firstMark(currentKm: Long?, step: Long): Long {
        require(step == 5_000L || step == 10_000L)
        return if (currentKm == null || currentKm < step) 1_000 else currentKm / step * step
    }
    fun nextMark(mark: Long, step: Long): Long = if (mark == 1_000L) step else Math.addExact(mark, step)

    /** Initial latest reached + future; later calls preserve the persisted frontier. */
    fun selectMarks(currentKm: Long?, step: Long, retainedMarks: List<Long>): List<Long> {
        if (currentKm == null) return emptyList() // Unadopted informational target only.
        var mark = retainedMarks.maxOrNull()?.let { nextMark(it, step) } ?: firstMark(currentKm, step)
        // Preflight arithmetic is constant-time, including a storage-valid 12-digit jump.
        // An existing future mark is sufficient. No backfill of unknown pre-adoption history.
        if (retainedMarks.isNotEmpty() && retainedMarks.max() > currentKm) return emptyList()
        val count = when {
            currentKm < mark -> 1L
            mark == 1_000L -> 2 + currentKm / step
            else -> (currentKm - mark) / step + 2
        }
        val last = if (mark == 1_000L && count > 1) (count - 1) * step else mark + (count - 1) * step
        if (count > MAX_NEW_MARKS || last > 999_999_999_999L) throw MilestoneReviewRequired()
        val selected = ArrayList<Long>(count.toInt())
        while (true) {
            selected += mark
            if (mark > currentKm) break
            mark = nextMark(mark, step)
        }
        return selected
    }
}
