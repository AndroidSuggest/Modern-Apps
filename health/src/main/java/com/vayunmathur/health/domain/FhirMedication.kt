package com.vayunmathur.health.domain

import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.RepeatUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import kotlinx.datetime.LocalDate as KotlinLocalDate

/**
 * A medication the user is on, as a FHIR `MedicationRequest` with `intent: plan`.
 *
 * A request rather than a statement because that is the split FHIR draws: `MedicationRequest`
 * is the plan to take something, carrying the dosage instructions; `MedicationStatement` is a
 * report that a dose was actually taken. Recording an ongoing regimen as a statement would
 * assert that the whole course had been consumed.
 *
 * `intent` is `plan` rather than `order` because nobody prescribed this — it is the user's own
 * account of what they intend to take. When a [schedule] is given it becomes
 * `dosageInstruction.timing.repeat`, which is what makes the reminder times part of the record
 * rather than a detail this app keeps to itself.
 */
internal fun medicationRequestJson(
    entry: MedicationEntry,
    schedule: MedicationSchedule? = null,
): String = buildJsonObject {
    put("resourceType", "MedicationRequest")
    put("id", entry.id)
    put("status", requestStatusToFhir(entry.status))
    put("intent", "plan")
    // The user is the source. Without this a reader would assume a clinician entered it.
    put("reportedBoolean", true)
    putJsonObject("medicationCodeableConcept") {
        if (entry.rxcui != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.RXNORM_SYSTEM)
                        put("code", entry.rxcui)
                        put("display", fullMedicationName(entry))
                    }
                )
            }
        }
        put("text", fullMedicationName(entry))
    }
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("authoredOn", formatDateTime(entry.startedAt))

    val dosageText = entry.dosageText?.takeIf { it.isNotBlank() }
    if (dosageText != null || schedule != null) {
        putJsonArray("dosageInstruction") {
            add(
                buildJsonObject {
                    dosageText?.let { put("text", it) }
                    schedule?.let { putJsonObject("timing") { putRepeat(it, entry) } }
                }
            )
        }
    }
    entry.note?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

/**
 * The dose schedule as a FHIR `Timing.repeat`.
 *
 * The app's own model maps onto this almost exactly: times of day become `timeOfDay`, the
 * interval and its unit become `period` and `periodUnit`, chosen weekdays become `dayOfWeek`,
 * and the start and end dates become `boundsPeriod`.
 */
private fun JsonObjectBuilder.putRepeat(
    schedule: MedicationSchedule,
    entry: MedicationEntry,
) {
    putJsonObject("repeat") {
        putJsonObject("boundsPeriod") {
            put("start", formatDateTime(entry.startedAt))
            val end = schedule.endDate?.let { formatScheduleDate(it) }
                ?: entry.endedAt?.let { formatDateTime(it) }
            end?.let { put("end", it) }
        }
        if (schedule.times.isNotEmpty()) {
            putJsonArray("timeOfDay") {
                schedule.times.sorted().forEach { add(JsonPrimitive(formatTimeOfDay(it))) }
            }
            // frequency is the number of times per period, which for a list of clock times is
            // simply how many there are.
            put("frequency", schedule.times.size)
        }
        put("period", schedule.interval)
        put("periodUnit", if (schedule.repeatUnit == RepeatUnit.Weekly) "wk" else "d")
        if (schedule.repeatUnit == RepeatUnit.Weekly && schedule.daysOfWeek != 0) {
            putJsonArray("dayOfWeek") {
                FHIR_DAYS.forEachIndexed { index, day ->
                    if (schedule.daysOfWeek and (1 shl index) != 0) add(JsonPrimitive(day))
                }
            }
        }
    }
}

/** Bit 0 is Sunday, matching `MedicationSchedule.daysOfWeek`. */
private val FHIR_DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

/** A schedule date as a FHIR `date`. */
private fun formatScheduleDate(date: KotlinLocalDate): String =
    "%04d-%02d-%02d".format(date.year, date.monthNumber, date.dayOfMonth)

/**
 * The local calendar date of an instant, for the schedule's anchor.
 *
 * Local rather than UTC because a schedule anchored "today" means the user's today.
 */
internal fun Instant.toScheduleDate(): KotlinLocalDate {
    val local = atZone(ZoneId.systemDefault()).toLocalDate()
    return KotlinLocalDate(local.year, local.monthValue, local.dayOfMonth)
}

/** "08:00:00" from seconds since midnight, the form `Timing.repeat.timeOfDay` takes. */
private fun formatTimeOfDay(seconds: Int): String =
    "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

/** "Amoxicillin", "500 mg", "Capsule" rendered as one line for `display` and `text`. */
internal fun fullMedicationName(entry: MedicationEntry): String =
    listOfNotNull(
        entry.displayName.takeIf { it.isNotBlank() },
        entry.strength?.takeIf { it.isNotBlank() },
        entry.doseForm?.takeIf { it.isNotBlank() },
    ).joinToString(" ")

/**
 * A medication regimen read back from a `MedicationRequest`.
 *
 * Returns the entry and, when the request carries a `timing.repeat`, the schedule behind it —
 * a provider's instructions are worth keeping rather than flattening to a date range.
 */
internal fun parseMedicationRequest(
    data: String,
    dataSourceId: String,
): Pair<MedicationEntry, MedicationSchedule?>? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "MedicationRequest") return null
    val id = root.string("id") ?: return null
    val concept = root["medicationCodeableConcept"] as? JsonObject
    val coding = concept.codingWithSystem(FhirRecords.RXNORM_SYSTEM)
    val displayName = coding?.string("display")
        ?: concept?.string("text")
        ?: concept.anyCoding()?.string("display")
        ?: return null

    val dosage = (root["dosageInstruction"] as? JsonArray)?.firstOrNull() as? JsonObject
    val repeat = ((dosage?.get("timing") as? JsonObject)?.get("repeat")) as? JsonObject
    val bounds = repeat?.get("boundsPeriod") as? JsonObject

    val startedAt = parseDateTime(root.string("authoredOn"))
        ?: parseDateTime(bounds.string("start"))
        ?: return null
    val endedAt = parseDateTime(bounds.string("end"))

    val entry = MedicationEntry(
        id = id,
        rxcui = coding?.string("code"),
        displayName = displayName,
        status = requestStatusFromFhir(root.string("status")),
        startedAt = startedAt,
        endedAt = endedAt,
        dosageText = dosage?.string("text"),
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
    return entry to repeat?.let { parseRepeat(it, id, startedAt, endedAt) }
}

private fun parseRepeat(
    repeat: JsonObject,
    medicationId: String,
    startedAt: Instant,
    endedAt: Instant?,
): MedicationSchedule? {
    val times = (repeat["timeOfDay"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::parseTimeOfDay) }
        .orEmpty()
    // Without clock times there is nothing to remind at, whatever else the timing says.
    if (times.isEmpty()) return null

    val unit = if (repeat.string("periodUnit") == "wk") RepeatUnit.Weekly else RepeatUnit.Daily
    val days = (repeat["dayOfWeek"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.fold(0) { acc, day ->
            val index = FHIR_DAYS.indexOf(day)
            if (index < 0) acc else acc or (1 shl index)
        } ?: 0

    return MedicationSchedule(
        id = medicationId,
        medicationId = medicationId,
        // Imported schedules arrive switched off. Someone else's record should not start a
        // phone ringing on its own.
        enabled = false,
        times = times.distinct().sorted(),
        repeatUnit = unit,
        interval = repeat["period"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1,
        daysOfWeek = days,
        anchorDate = startedAt.toScheduleDate(),
        endDate = endedAt?.toScheduleDate(),
    )
}

/** Seconds since midnight from "08:00:00", tolerating a missing seconds field. */
private fun parseTimeOfDay(value: String): Int? {
    val parts = value.split(":")
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull() ?: 0
    return h * 3600 + m * 60 + s
}

/**
 * One dose actually taken, as a FHIR `MedicationStatement`.
 *
 * This is what `MedicationStatement` is for: a report that a medication was taken, as opposed to
 * the plan to take it. `MedicationAdministration` would be the precise resource for the act of
 * swallowing a tablet, but Health Connect does not accept that type, and a completed statement
 * with a single `effectiveDateTime` says the same thing in a type it does accept.
 *
 * `basedOn` points at the MedicationRequest it fulfils, which is what
 * ties a dose to its regimen without needing an extension to tell the two apart.
 */
internal fun doseEventJson(event: DoseEvent, medication: MedicationEntry): String = buildJsonObject {
    put("resourceType", "MedicationStatement")
    put("id", event.id)
    put("status", "completed")
    putJsonArray("basedOn") {
        add(buildJsonObject { put("reference", "MedicationRequest/${event.medicationId}") })
    }
    putJsonObject("medicationCodeableConcept") {
        if (medication.rxcui != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.RXNORM_SYSTEM)
                        put("code", medication.rxcui)
                        put("display", fullMedicationName(medication))
                    }
                )
            }
        }
        put("text", fullMedicationName(medication))
    }
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    // A moment, not a period: this is one dose, not a course of them.
    put("effectiveDateTime", formatDateTime(event.takenAt))
    put("dateAsserted", formatDateTime(event.takenAt))
    medication.dosageText?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("dosage") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

internal fun parseDoseEvent(data: String, dataSourceId: String): DoseEvent? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "MedicationStatement") return null
    val id = root.string("id") ?: return null
    val takenAt = parseDateTime(root.string("effectiveDateTime")) ?: return null
    val medicationId = (root["basedOn"] as? JsonArray)
        ?.firstNotNullOfOrNull { (it as? JsonObject).string("reference") }
        ?.substringAfter("MedicationRequest/", "")
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    return DoseEvent(
        id = id,
        medicationId = medicationId,
        takenAt = takenAt,
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

/**
 * [MedicationStatus] as a `MedicationRequest.status`.
 *
 * A different value set from `MedicationStatement.status`, which is why this is not shared with
 * the dose events: a request can be `stopped`, a statement cannot.
 */
private fun requestStatusToFhir(status: MedicationStatus): String = when (status) {
    MedicationStatus.Active -> "active"
    MedicationStatus.Completed -> "completed"
    MedicationStatus.Stopped -> "stopped"
}

private fun requestStatusFromFhir(status: String?): MedicationStatus = when (status) {
    "completed" -> MedicationStatus.Completed
    "stopped", "cancelled", "entered-in-error" -> MedicationStatus.Stopped
    else -> MedicationStatus.Active
}
