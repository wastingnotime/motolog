package org.wastingnotime.motolog.data.local

import androidx.room.*

/** Append-only identities; absence of a historical obligation means unknown, not completed. */
@Entity(foreignKeys = [ForeignKey(entity = Baseline::class, parentColumns = ["id"], childColumns = ["baselineId"]),
    ForeignKey(entity = MaintenanceOccurrence::class, parentColumns = ["id"], childColumns = ["occurrenceId"]),
    ForeignKey(entity = MaintenanceHistory::class, parentColumns = ["id"], childColumns = ["anchorKmId"]),
    ForeignKey(entity = MaintenanceHistory::class, parentColumns = ["id"], childColumns = ["anchorDateId"])],
    indices = [Index("baselineId"), Index("anchorKmId"), Index("anchorDateId"), Index(value = ["baselineId", "cycleKey"], unique = true)])
data class PreventiveObligation(@PrimaryKey val occurrenceId: String, val baselineId: String,
    val cycleKey: String, val branch: String, val anchorKmId: String?, val anchorDateId: String?) {
    internal fun fields(): List<Any?> = listOf(occurrenceId, baselineId, cycleKey, branch, anchorKmId, anchorDateId)
}

@Entity(foreignKeys = [ForeignKey(entity = Baseline::class, parentColumns = ["id"], childColumns = ["baselineId"]),
    ForeignKey(entity = MaintenanceHistory::class, parentColumns = ["id"], childColumns = ["historyId"])],
    indices = [Index("baselineId"), Index("historyId"), Index(value = ["baselineId", "revision"], unique = true)])
data class QualifyingAnchor(@PrimaryKey val id: String, val baselineId: String, val historyId: String,
    val revision: Int, val orderReconciled: Boolean) {
    internal fun fields(): List<Any?> = listOf(id, baselineId, historyId, revision, orderReconciled)
}

/** One performed action may explicitly reconcile several obligations; each retains its own targets. */
@Entity(foreignKeys = [ForeignKey(entity = MaintenanceOccurrence::class, parentColumns = ["id"], childColumns = ["occurrenceId"]),
    ForeignKey(entity = MaintenanceHistory::class, parentColumns = ["id"], childColumns = ["historyId"])],
    indices = [Index("historyId")])
data class ObligationSatisfaction(@PrimaryKey val occurrenceId: String, val historyId: String,
    val originalKm: Long?, val originalDate: String?, val effectiveKm: Long?, val effectiveDate: String?,
    val occurrenceRevision: Int) {
    internal fun fields(): List<Any?> = listOf(occurrenceId, historyId, originalKm, originalDate,
        effectiveKm, effectiveDate, occurrenceRevision)
}
