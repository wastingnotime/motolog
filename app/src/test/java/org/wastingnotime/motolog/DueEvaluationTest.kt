package org.wastingnotime.motolog

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.motolog.domain.maintenance.*
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate

class DueEvaluationTest {
    private val jan = LocalDate.of(2024, 1, 31)
    private val custom = RuleValues("Replace", "Synthetic", SourceKind.CUSTOM,
        scheduleKind = ScheduleKind.INTERVAL, intervalKm = 2_000, intervalMonths = 3)
    private fun status(target: DueTargets, km: Long?, date: LocalDate) = DueEvaluation.evaluate(target, km, date)

    @Test fun inclusiveMileageAndCalendarEachWinFirstAndSoleThresholdsStandAlone() {
        val dual = DueEvaluation.interval(custom, 20_000, jan)
        assertEquals(LocalDate.of(2024, 4, 30), dual.date)
        listOf(21_999L to DueStatus.NOT_DUE, 22_000L to DueStatus.DUE, 22_001L to DueStatus.DUE).forEach { (km, expected) ->
            assertEquals(expected, status(dual, km, LocalDate.of(2024, 4, 29)).status)
            assertEquals(expected, status(DueTargets(km = 22_000), km, jan).status)
        }
        listOf(-1L to DueStatus.NOT_DUE, 0L to DueStatus.DUE, 1L to DueStatus.DUE).forEach { (days, expected) ->
            assertEquals(expected, status(dual, 20_000, dual.date!!.plusDays(days)).status)
            assertEquals(expected, status(DueTargets(date = dual.date), null, dual.date.plusDays(days)).status)
        }
        assertEquals(DueStatus.UNKNOWN, status(DueTargets(km = 100), null, jan).status)
        assertEquals(DueStatus.UNSCHEDULED, status(DueTargets(), null, jan).status)
    }

    @Test fun unknownBranchesNeverProveCurrentButKnownDueWins() {
        val unknownTime = DueTargets(km = 5_000, expectsDate = true)
        assertEquals(DueStatus.UNKNOWN, status(unknownTime, 4_999, jan).status)
        assertEquals(DueStatus.DUE, status(unknownTime, 5_000, jan).status)
        val unknownKm = DueEvaluation.interval(custom, null, jan)
        assertEquals(BranchStatus.UNKNOWN, status(unknownKm, 50_000, jan).mileage)
        assertEquals(DueStatus.UNKNOWN, status(unknownKm, 50_000, jan).status)
        assertEquals(DueStatus.DUE, status(unknownKm, null, jan.plusMonths(3)).status)
        assertEquals(DueTargets(expectsKm = true, expectsDate = true), DueEvaluation.interval(custom, null, null))
    }

    @Test fun monthEndLeapYearsAndOilCalendarAreIndependentOfMileageGrid() {
        assertEquals(LocalDate.of(2024, 2, 29), jan.plusMonths(1))
        assertEquals(LocalDate.of(2025, 2, 28), LocalDate.of(2024, 2, 29).plusMonths(12))
        val oilTime = DueTargets(date = LocalDate.of(2023, 8, 31).plusMonths(6))
        assertEquals(LocalDate.of(2024, 2, 29), oilTime.date)
        assertEquals(DueStatus.NOT_DUE, status(oilTime, null, LocalDate.of(2024, 2, 28)).status)
        assertEquals(DueStatus.DUE, status(oilTime, null, LocalDate.of(2024, 2, 29)).status)
        assertEquals(DueStatus.DUE, status(oilTime, null, LocalDate.of(2024, 3, 1)).status)
    }

    @Test(timeout = 1_000) fun hugeJumpRequiresReviewBeforeEnumerationAndInitialUnknownHistoryStillDoesNotBackfill() {
        listOf(5_000L, 10_000L).forEach { step ->
            assertThrows(MilestoneReviewRequired::class.java) {
                DueEvaluation.selectMarks(999_999_000_000, step, listOf(1_000))
            }
            assertEquals(2, DueEvaluation.selectMarks(999_999_000_001, step, emptyList()).size)
        }
    }

    @Test fun fixedFirstServiceAndReplayHaveNoOneThousandRecurrenceOrFilterAtFifteenThousand() {
        listOf(5_000L, 10_000L).forEach { step ->
            val marks = DueEvaluation.selectMarks(30_001, step, listOf(1_000))
            assertFalse(2_000L in marks)
            assertTrue(10_000L in marks && 20_000L in marks && 30_000L in marks)
            if (step == 10_000L) assertFalse(15_000L in marks)
            assertTrue(DueEvaluation.selectMarks(null, step, emptyList()).isEmpty())
            assertEquals(listOf(60_000L, if (step == 5_000L) 65_000L else 70_000L),
                DueEvaluation.selectMarks(61_234, step, emptyList()))
            assertEquals(emptyList<Long>(), DueEvaluation.selectMarks(999, step, listOf(1_000)))
        }
        listOf(999L, 1_000L, 1_001L).forEach { km ->
            assertEquals(if (km < 1_000) DueStatus.NOT_DUE else DueStatus.DUE,
                status(DueTargets(km = 1_000), km, jan).status)
        }
        listOf(5_000L, 10_000L, 15_000L, 20_000L, 30_000L).forEach { mark ->
            listOf(-1L, 0L, 1L).forEach { delta ->
                assertEquals(if (delta < 0) DueStatus.NOT_DUE else DueStatus.DUE,
                    status(DueTargets(km = mark), mark + delta, jan).status)
            }
        }
        assertEquals(25_000L, DueEvaluation.nextMark(20_000, 5_000))
        assertEquals(30_000L, DueEvaluation.nextMark(20_000, 10_000))
    }

    @Test fun verifiedConditionalNumericBranchesAreActionableAndConditionsStayUnknown() {
        listOf("brake-hoses", "air-filter", "chain-care").forEach { key ->
            val rule = FazerInventory.rules.getValue(key)
            assertEquals(RuleSupport.CONDITIONAL_INTERVAL, DueEvaluation.support(rule))
            val target = DueEvaluation.interval(rule, 100, jan)
            listOf(-1L, 0L, 1L).forEach { delta ->
                val result = DueEvaluation.evaluate(target, target.km?.plus(delta),
                    target.date?.plusDays(delta) ?: jan, true)
                assertTrue(result.manualReview)
                assertEquals(if (delta < 0) DueStatus.MANUAL_REVIEW else DueStatus.DUE, result.status)
            }
            val missing = DueEvaluation.evaluate(DueEvaluation.interval(rule, null, null), null, jan, true)
            assertEquals(DueStatus.MANUAL_REVIEW, missing.status)
            assertTrue(missing.mileage == BranchStatus.UNKNOWN || missing.calendar == BranchStatus.UNKNOWN)
            assertEquals(RuleSupport.MANUAL_REVIEW, DueEvaluation.support(rule.copy(sourceKind = SourceKind.CUSTOM)))
        }
        assertEquals(RuleSupport.OIL, DueEvaluation.support(FazerInventory.rules.getValue("oil")))
        assertEquals(RuleSupport.OIL_FILTER, DueEvaluation.support(FazerInventory.rules.getValue("oil-filter")))
        listOf("oil-inspection", "plug-inspection", "hose-inspection", "brake-checks").forEach {
            assertEquals(RuleSupport.MANUAL_REVIEW, DueEvaluation.support(FazerInventory.rules.getValue(it)))
        }
    }
}
