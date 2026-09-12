package com.builttoroam.devicecalendar

import java.util.Calendar
import java.util.TimeZone
import org.dmfs.rfc5545.recur.RecurrenceRule

/** A null resulting range means the recurrence no longer contains this member. */
internal data class RecurrenceExceptionReset(
    val originalSlot: Long,
    val range: EventDateRangeValue?
)

/**
 * Match immutable exception addresses against the resulting definition once,
 * only as far as the last stored exception. Never repurpose an old address by
 * ordinal: a synced exception retains its remote originalStartTime.
 * RRULE iterators preserve local time across DST and timezone changes.
 */
internal fun recurrenceExceptionResets(
    originalSlots: Collection<Long>,
    originalRange: EventDateRangeValue,
    originalRule: String,
    resultingRange: EventDateRangeValue,
    resultingRule: String
): List<RecurrenceExceptionReset> {
    if (originalSlots.isEmpty()) return emptyList()
    // Validate the source definition too, but membership is decided exclusively
    // by the resulting slots. A reset may repair an old out-of-rule exception.
    RecurrenceRule(originalRule)
    if (originalRange.allDay != resultingRange.allDay) {
        return originalSlots.map { RecurrenceExceptionReset(it, null) }
    }
    val next = RecurrenceRule(resultingRule).iterator(
        resultingRange.startDate, TimeZone.getTimeZone(resultingRange.startTimeZone))
    val wanted = originalSlots.toSet()
    val last = wanted.maxOrNull()!!
    val mapped = mutableMapOf<Long, EventDateRangeValue?>()
    while (next.hasNext()) {
        val slot = next.nextMillis()
        if (slot > last) break
        if (slot in wanted) mapped[slot] = resultingRange.copy(
            startDate = slot, endDate = recurrenceResetEnd(resultingRange, slot))
    }
    // Existing out-of-rule rows are not allowed to become unrelated members.
    return originalSlots.map { RecurrenceExceptionReset(it, mapped[it]) }
}

private fun recurrenceResetEnd(template: EventDateRangeValue, start: Long): Long {
    fun floating(instant: Long, zone: String): Long {
        val local = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = instant }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH),
                local.get(Calendar.HOUR_OF_DAY), local.get(Calendar.MINUTE), local.get(Calendar.SECOND))
            set(Calendar.MILLISECOND, local.get(Calendar.MILLISECOND))
        }.timeInMillis
    }
    val civilDuration = floating(template.endDate, template.endTimeZone) -
        floating(template.startDate, template.startTimeZone)
    val end = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = floating(start, template.startTimeZone) + civilDuration
    }
    return Calendar.getInstance(TimeZone.getTimeZone(template.endTimeZone)).apply {
        clear()
        set(end.get(Calendar.YEAR), end.get(Calendar.MONTH), end.get(Calendar.DAY_OF_MONTH),
            end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE), end.get(Calendar.SECOND))
        set(Calendar.MILLISECOND, end.get(Calendar.MILLISECOND))
    }.timeInMillis
}

/** Preserve all identity columns by omission; clear recurrence on the member. */
internal fun recurrenceExceptionResetValues(range: EventDateRangeValue): Map<String, Any?> =
    recurrenceStorageChanges(null, range.startDate, range.startTimeZone,
        range.endDate, range.endTimeZone, range.allDay, null)
