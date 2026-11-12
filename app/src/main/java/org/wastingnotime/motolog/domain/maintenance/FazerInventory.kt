package org.wastingnotime.motolog.domain.maintenance

import org.wastingnotime.motolog.domain.model.*

/** Original, limited facts reviewed in references/fazer-ys250-blueflex-maintenance.md. */
object FazerInventory {
    const val identity = "Yamaha FAZER YS250 BlueFlex / U44CWDW0 / BR"
    const val edition = "Second edition, May 2017"
    const val sha256 = "547af0d0dde6b93c02f732910c08252478dfafe2c16669db3222ec7f832f3ca8"
    const val gaps = "Partial coverage. Unverified schedules are not seeded: fuel hoses, valves, injection/exhaust, " +
        "check hose, clutch, wheels/tires/bearings, suspension/steering, fasteners, stands, cables/controls, " +
        "lights/switches and brake-pad wear limits. Pre-ride checks and procedures are not covered. " +
        "This checklist cannot establish that all motorcycle maintenance is current."

    private fun rule(component: String, locator: String, km: Long? = null, months: Int? = null,
        kind: ScheduleKind = ScheduleKind.INTERVAL, note: String? = null, action: String = "Replace") =
        RuleValues(action, component, SourceKind.MANUAL, "$identity; SHA-256 $sha256", edition,
            locator, baselineRevision = 1, scheduleKind = kind, intervalKm = km,
            intervalMonths = months, coverageNote = note ?: "Prior qualifying service anchors unknown until evidenced.")

    val rules: Map<String, RuleValues> = linkedMapOf(
        "fuel-filter" to rule("External fuel filter", "Emissions table item 2; printed 6-4 / PDF 53", km = 15_000),
        "spark-plug" to rule("Spark plug", "Emissions table item 3 replacement; printed 6-4 / PDF 53", km = 10_000),
        "brake-fluid" to rule("Brake fluid", "General table item 7; printed 6-6 / PDF 55; hydraulic note 6-10 / PDF 59", months = 24),
        "brake-cylinders" to rule("Brake cylinder internal parts", "Hydraulic note; printed 6-10 / PDF 59", months = 24,
            note = "Establish actual cylinder/front/rear scope for each qualifying replacement; fluid work is insufficient."),
        "brake-calipers" to rule("Brake caliper internal parts", "Hydraulic note; printed 6-10 / PDF 59", months = 24,
            note = "Establish actual caliper/front/rear scope for each qualifying replacement; fluid work is insufficient."),
        "brake-hoses" to rule("Brake hoses", "General table item 6 replacement; printed 6-6 / PDF 55; note 6-10 / PDF 59",
            months = 48, kind = ScheduleKind.CONDITION, note = "Manual review: cracks/damage require replacement independently of age."),
        "air-filter" to rule("Air-filter element", "General table item 1; printed 6-5 / PDF 54; note 6-9 / PDF 58",
            km = 40_000, kind = ScheduleKind.CONDITION, note = "Manual review: humid/dusty use requires more frequent replacement; shorter interval unquantified."),
        "chain-care" to rule("Drive chain", "General table item 12; printed 6-7 / PDF 56", km = 500,
            kind = ScheduleKind.CONDITION, action = "Inspect, adjust and lubricate",
            note = "Manual review: also after washing/rain. Check slack, alignment and condition; no replacement interval."),
        "oil" to rule("Engine oil", "General table item 20; printed 6-8 / PDF 57; note 6-3 / PDF 52",
            kind = ScheduleKind.MILESTONE, note = "Fixed mileage: 1,000, then 5,000/10,000/15,000 km and onward at 5,000-km marks. Six-month guidance; initial calendar origin unknown. Evaluate mileage independently; earlier service history remains unknown."),
        "oil-filter" to rule("Oil-filter element", "General table item 21; printed 6-8 / PDF 57; replay note 6-3 / PDF 52",
            kind = ScheduleKind.MILESTONE, note = "Fixed mileage: 1,000, then 10,000/20,000/30,000 km and onward. No six-month filter rule. Earlier service history remains unknown."),
        "oil-inspection" to rule("Oil level/leaks", "General table item 20; printed 6-8 / PDF 57; note 6-3 / PDF 52",
            kind = ScheduleKind.MILESTONE, action = "Inspect", note = "Manual review: 1,000, then 5,000-km marks / six months; inspection is not replacement."),
        "plug-inspection" to rule("Spark plug", "Emissions table item 3 inspection; printed 6-4 / PDF 53; note 6-3 / PDF 52",
            kind = ScheduleKind.MILESTONE, action = "Inspect, clean and correct gap", note = "Manual review: 1,000, then 5,000-km marks / six months; separate from replacement."),
        "hose-inspection" to rule("Brake hoses", "General table item 6 inspection; printed 6-6 / PDF 55; note 6-3 / PDF 52",
            kind = ScheduleKind.MILESTONE, action = "Inspect", note = "Manual review: 5,000-km marks / six months; 1,000-km cell blank. Damage can require replacement."),
        "brake-checks" to rule("Front/rear hydraulic brakes", "General table items 4/5; printed 6-5 / PDF 54; notes 6-3 / PDF 52, 6-9 / PDF 58",
            kind = ScheduleKind.MILESTONE, action = "Check operation, level and leaks", note = "Manual review: 1,000, then 5,000-km marks / six months, plus unquantified regular level checks. Not replacement.")
    )
}
