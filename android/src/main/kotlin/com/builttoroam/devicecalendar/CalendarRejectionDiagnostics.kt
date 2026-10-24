package com.builttoroam.devicecalendar

import com.builttoroam.devicecalendar.diagnostics.NativeObfuscation

// Fixed native protocol vocabulary. Unknown fields/stages never echo input.
private val rejectionStages = setOf(
    "event.precondition", "event.atomicBatch", "occurrence.precondition",
    "occurrence.atomicBatch", "thisAndFollowing.precondition", "thisAndFollowing.atomicBatch",
    "entireSeries.precondition", "entireSeries.atomicBatch",
    "entireSeries.selectedOccurrence.precondition", "thisAndFollowing.selectedOccurrence.precondition",
    "standaloneReplacement.precondition", "standaloneReplacement.atomicBatch"
)
private val changeFields = setOf("color", "title", "location", "dateRange",
    "reminders", "attendees", "resources", "recurrence")

internal fun calendarFailureDiagnostics(stage: String, failure: Throwable?): Map<String, Any?> =
    mapOf("stage" to stage.takeIf { it in rejectionStages }.orEmpty().ifEmpty { "unknown" },
        "batchExceptionType" to NativeObfuscation.session.failure(failure).toString())

/** Debug-only evidence. Payloads and exception getters are never serialized.
 * This view cannot be used as provider truth or proof of transaction rollback.
 */
internal fun calendarRejectionDiagnostics(
    enabled: Boolean,
    titles: List<String?>,
    stage: String,
    batchFailure: Exception? = null,
    details: () -> Map<String, Any?>
): Map<String, Any?>? {
    if (!enabled || titles.none { it?.contains("test-rec", ignoreCase = true) == true }) return null
    val header = calendarFailureDiagnostics(stage, batchFailure)
    return try {
        val raw = details()
        val matches = raw["expectedMatchesAtRead"] as? Map<*, *>
        val storage = raw["comparisonStorage"] as? Map<*, *>
        val obfuscation = NativeObfuscation.session
        header + mapOf(
            "expectedMatchesAtRead" to changeFields.mapNotNull { field ->
                (matches?.get(field) as? Boolean)?.let { field to it }
            }.toMap(),
            "expectedMismatchesAtRead" to changeFields.filter { matches?.get(it) == false },
            "comparisonRead" to when (raw["comparisonRead"]) {
                "preflight" -> "preflight"
                "afterBatchFailure" -> "afterBatchFailure"
                else -> "unknown"
            },
            // Only schema-selected primitives reach aliases. Unknown maps,
            // raw SQL values, private field names and context are omitted.
            "comparisonStorage" to storage?.let {
                mapOf("deleted" to (it["deleted"] as? Boolean),
                    "allDay" to (it["allDay"] as? Boolean),
                    "dtstart" to (it["dtstart"] as? Long)?.let { v -> obfuscation.instant(v).toString() },
                    "dtend" to (it["dtend"] as? Long)?.let { v -> obfuscation.instant(v).toString() },
                    "duration" to (it["duration"] as? String)?.let { v -> obfuscation.id(v).toString() },
                    "startTimeZone" to (it["startTimeZone"] as? String)?.let { v -> obfuscation.id(v).toString() },
                    "endTimeZone" to (it["endTimeZone"] as? String)?.let { v -> obfuscation.id(v).toString() },
                    "rrule" to (it["rrule"] as? String)?.let { v -> obfuscation.recurrence(v).toString() })
            })
    } catch (error: Throwable) {
        header + ("diagnosticErrorType" to NativeObfuscation.session.failure(error).toString())
    }
}
