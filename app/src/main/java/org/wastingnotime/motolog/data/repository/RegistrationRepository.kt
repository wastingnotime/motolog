package org.wastingnotime.motolog.data.repository

import org.wastingnotime.motolog.data.local.*
import org.wastingnotime.motolog.domain.maintenance.FazerInventory
import org.wastingnotime.motolog.domain.model.*
import java.time.LocalDate
import java.time.ZoneId

/** Feature commands wrap read/validate/write in the same Room transaction. No personal seeds. */
class RegistrationRepository(private val db: MotoLogDatabase) {
    private val store = LocalStore(db)
    fun snapshot() = store.snapshot()

    fun register(id: String, manufactureYear: Int?, manualMatch: Boolean,
        reading: OdometerReading?, now: Long): Boolean = db.runInTransaction<Boolean> {
        validateYear(manufactureYear, now)
        require(!manualMatch || manufactureYear != null) { "Confirm manufacture year separately first" }
        reading?.let {
            require(it.bikeId == id && it.source == ReadingSource.OWNER)
            require(it.observed == null || it.observed.epochMillis <= now) { "Observation is in the future" }
        }
        store.write("register:$id", WriteBatch(
            bikes = listOf(Bike(id, model = "YS250", variant = "BlueFlex", market = "BR",
                modelYear = 2017, manufactureYear = manufactureYear, manualConfirmed = manualMatch)),
            readings = listOfNotNull(reading), baselines = if (manualMatch) seeds(id) else emptyList()), now)
    }

    fun confirmManual(bikeId: String, manufactureYear: Int, now: Long) = db.runInTransaction {
        validateYear(manufactureYear, now)
        val bike = db.dao().bikeRows().single()
        require(bike.id == bikeId && bike.model == "YS250" && bike.variant == "BlueFlex" &&
            bike.market == "BR" && bike.modelYear == 2017)
        if (!bike.manualConfirmed) {
            check(db.dao().confirmManual(bikeId, manufactureYear) == 1)
            store.write("admit-manual:$bikeId", WriteBatch(baselines = seeds(bikeId)), now)
        } else require(bike.manufactureYear == manufactureYear) { "Bike already confirmed with a different manufacture year" }
    }

    fun items(snapshot: WriteBatch): List<ChecklistItem> = snapshot.baselines.map { baseline ->
        val override = snapshot.overrides.filter { it.baselineId == baseline.id }.maxByOrNull { it.revision }
        ChecklistItem(baseline, override)
    }

    fun addItem(id: String, bikeId: String, values: RuleValues, now: Long) {
        val rule = values.copy(sourceKind = SourceKind.CUSTOM, sourceIdentity = "Owner", edition = null,
            locator = null, baselineRevision = 1, overrideRevision = null)
        store.write("add:$id", WriteBatch(baselines = listOf(Baseline(id, bikeId, 1, rule))), now)
    }

    fun editItem(baselineId: String, expectedRevision: Int, values: RuleValues, now: Long) = db.runInTransaction {
        val snapshot = store.snapshot()
        val item = items(snapshot).single { it.baseline.id == baselineId }
        require(item.revision == expectedRevision) { "Checklist changed; reload before saving" }
        val revision = expectedRevision + 1
        // Keep manual identity/locator even when every editable field changes.
        val rule = values.copy(sourceKind = SourceKind.CUSTOM, sourceIdentity = item.baseline.rule.sourceIdentity,
            edition = item.baseline.rule.edition, locator = item.baseline.rule.locator,
            baselineRevision = item.baseline.revision, overrideRevision = revision)
        store.write("edit:$baselineId:$revision", WriteBatch(overrides = listOf(
            OwnerOverride("$baselineId:override:$revision", item.baseline.bikeId, baselineId, revision, rule))), now)
    }

    fun preview(draft: SetupEntry, now: Long): EntryPreview {
        val snapshot = store.snapshot()
        require(snapshot.bikes.single().id == draft.bikeId)
        require(draft.id.isNotBlank() && draft.note.isNotBlank()) { "Original source note is required" }
        draft.date?.let { require(!LocalDate.parse(it).isAfter(LocalDate.now(java.time.Clock.fixed(
            java.time.Instant.ofEpochMilli(now), ZoneId.of(draft.zone))))) { "Service date is in the future" } }
        require(draft.km == null || draft.km in 0..999_999_999_999L)
        draft.observed?.validate()
        require(draft.observed == null || draft.observed.epochMillis <= now) { "Observation is in the future" }
        val rule = draft.baselineId?.let { key -> items(snapshot).single { it.baseline.id == key }.effective }
            ?: RuleValues(draft.action, draft.component, SourceKind.CUSTOM, sourceIdentity = "Owner")
        require(rule.action.isNotBlank() && rule.component.isNotBlank())
        val cost = draft.cost?.let { DecimalValues.normalize(it, 2, false) }
        val rows = listOf(ImportRow("setup:${draft.id}", draft.bikeId, draft.id, "entry", draft.note, draft.certainty))
        val batch = WriteBatch(importRows = rows,
            readings = if (draft.kind == EntryKind.READING) listOf(OdometerReading("setup:${draft.id}:reading",
                draft.bikeId, draft.km, draft.certainty, ReadingSource.OWNER_IMPORT, draft.observed, now, draft.note)) else emptyList(),
            histories = if (draft.kind == EntryKind.PERFORMED) listOf(MaintenanceHistory("setup:${draft.id}:history",
                draft.bikeId, null, rule, null, null, null, null, draft.date, draft.date?.let { draft.zone },
                draft.km, draft.certainty, cost, null, draft.note, "${rule.action}: ${rule.component}", null,
                if (draft.certainty == Certainty.CONFIRMED && draft.baselineId != null) draft.note else null, now)) else emptyList(),
            occurrences = if (draft.kind == EntryKind.PROGRAMMED) listOf(MaintenanceOccurrence("setup:${draft.id}:pending",
                draft.bikeId, null, rule, null, null, null, null, kind = MaintenanceKind.PROGRAMMED)) else emptyList())
        if (draft.kind == EntryKind.READING && draft.certainty == Certainty.CONFIRMED)
            require(draft.km != null && draft.observed != null) { "Confirmed reading needs kilometers and observation timestamp" }
        return EntryPreview(draft.id, batch, duplicateCandidates(batch, snapshot))
    }

    fun confirm(preview: EntryPreview, reviewedDuplicates: Boolean, now: Long): Boolean = db.runInTransaction<Boolean> {
        // A duplicate committed after preview must be displayed before it can be approved.
        val duplicates = duplicateCandidates(preview.batch, store.snapshot())
        require(duplicates.all { it in preview.duplicateCandidates }) {
            "New duplicate candidates appeared. Cancel preview and preview again to review them."
        }
        require(reviewedDuplicates || duplicates.isEmpty()) { "Review duplicate candidates before saving a separate entry" }
        store.write("setup:${preview.id}", preview.batch, now)
    }

    private fun duplicateCandidates(batch: WriteBatch, saved: WriteBatch): List<String> {
        val incoming = batch.importRows.single()
        return buildList {
            saved.importRows.filter { it.importId != incoming.importId && it.sourceNote.trim() == incoming.sourceNote.trim() }
                .forEach { add("${it.id}: ${it.sourceNote}; ${it.certainty}") }
            batch.histories.forEach { entry -> saved.histories.filter { it.id != entry.id &&
                (it.description.trim() == entry.description.trim() || (it.rule.component == entry.rule.component &&
                    it.performedDate == entry.performedDate && it.odometerKm == entry.odometerKm)) }
                .forEach { add("${it.id}: ${it.description}; ${it.rule.action} ${it.rule.component}; date=${it.performedDate ?: "unknown"}; km=${it.odometerKm ?: "unknown"}; ${it.certainty}") }
            }
            batch.readings.forEach { entry -> saved.readings.filter { it.id != entry.id &&
                it.kilometers == entry.kilometers && it.observed == entry.observed }
                .forEach { add("${it.id}: reading ${it.kilometers ?: "unknown"} km; ${it.observed?.let { time -> "${java.time.Instant.ofEpochMilli(time.epochMillis)} (${time.zoneId})" } ?: "observation unknown"}; ${it.certainty}; ${it.originalNote.orEmpty()}") }
            }
            batch.occurrences.forEach { entry -> saved.occurrences.filter { it.id != entry.id &&
                it.state == OccurrenceState.PENDING && it.rule.action == entry.rule.action && it.rule.component == entry.rule.component }
                .forEach { add("${it.id}: open ${it.rule.action} ${it.rule.component}; targets ${it.effectiveKm ?: "unknown"} km / ${it.effectiveDate ?: "unknown"}") }
            }
        }.distinct()
    }

    private fun seeds(bikeId: String) = FazerInventory.rules.map { (key, rule) -> Baseline("$bikeId:manual:$key:1", bikeId, 1, rule) }
    private fun validateYear(year: Int?, now: Long) {
        require(year == null || year in 1900..java.time.Instant.ofEpochMilli(now).atZone(ZoneId.of("UTC")).year) { "Invalid manufacture year" }
    }
}

data class ChecklistItem(val baseline: Baseline, val override: OwnerOverride?) {
    val effective get() = override?.rule ?: baseline.rule
    val revision get() = override?.revision ?: 0
    val label get() = if (effective.sourceKind == SourceKind.CUSTOM) "Custom" else "Manual"
    val source get() = if (baseline.rule.sourceKind == SourceKind.MANUAL)
        "${baseline.rule.sourceIdentity}\n${baseline.rule.edition}\n${baseline.rule.locator}" else "Owner"
}

enum class EntryKind { NOTE, READING, PERFORMED, PROGRAMMED }
data class SetupEntry(val id: String, val bikeId: String, val kind: EntryKind, val note: String,
    val certainty: Certainty = Certainty.UNKNOWN, val date: String? = null, val km: Long? = null,
    val observed: Observation? = null, val cost: String? = null, val action: String = "Review",
    val component: String = "Owner note", val baselineId: String? = null, val zone: String = ZoneId.systemDefault().id)
data class EntryPreview(val id: String, val batch: WriteBatch, val duplicateCandidates: List<String>)
