package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Events
import java.util.Calendar
import java.util.TimeZone

internal data class EventDateRangeValue(
    val startDate: Long,
    val startTimeZone: String,
    val endDate: Long,
    val endTimeZone: String,
    val allDay: Boolean
) {
    val isChronological: Boolean
        get() = endDate >= startDate

    fun toMap(): Map<String, Any> = mapOf(
        "startDate" to startDate,
        "startTimeZone" to startTimeZone,
        "endDate" to endDate,
        "endTimeZone" to endTimeZone,
        "allDay" to allDay
    )
}

internal fun parseEventDateRangeValue(value: Any?): EventDateRangeValue? {
    val map = value as? Map<*, *> ?: return null
    val startDate = (map["startDate"] as? Number)?.toLong() ?: return null
    val startTimeZone = map["startTimeZone"] as? String ?: return null
    val endDate = (map["endDate"] as? Number)?.toLong() ?: return null
    val endTimeZone = map["endTimeZone"] as? String ?: return null
    val allDay = map["allDay"] as? Boolean ?: return null
    if (startTimeZone.isBlank() || endTimeZone.isBlank()) return null

    return EventDateRangeValue(
        startDate = startDate,
        startTimeZone = startTimeZone,
        endDate = endDate,
        endTimeZone = endTimeZone,
        allDay = allDay
    ).takeIf { it.isChronological }
}

internal fun durationForDateRange(value: EventDateRangeValue): String? {
    val durationMillis = value.endDate - value.startDate
    if (durationMillis < 0L || durationMillis % 1000L != 0L) return null
    return "P${durationMillis / 1000L}S"
}

/**
 * Chooses the concrete occurrence that survives when a recurring series is
 * replaced by one standalone event.
 *
 * Without a date edit, the selected recurrence slot supplies the start while
 * the master supplies duration and timezone metadata. With a date edit, the
 * requested occurrence range is already the exact standalone range wanted by
 * the user.
 */
internal fun standaloneReplacementRange(
    masterRange: EventDateRangeValue,
    selectedOccurrenceStart: Long,
    requestedOccurrenceRange: EventDateRangeValue?
): EventDateRangeValue = requestedOccurrenceRange ?: EventDateRangeValue(
    startDate = selectedOccurrenceStart,
    startTimeZone = masterRange.startTimeZone,
    endDate = selectedOccurrenceStart + (masterRange.endDate - masterRange.startDate),
    endTimeZone = masterRange.endTimeZone,
    allDay = masterRange.allDay
)

/**
 * Translates a date range edited on one recurrence slot back onto the master.
 * A start-only editor can transiently report requested end == requested start;
 * that must never turn a healthy recurring master into a P0S event. In that
 * case retain the selected occurrence's known duration (or the master's as a
 * final fallback).
 */
internal fun translateOccurrenceRangeToSeriesMaster(
    masterRange: EventDateRangeValue,
    expectedOccurrence: EventDateRangeValue,
    requestedOccurrence: EventDateRangeValue
): EventDateRangeValue {
    val expectedCalendar = calendarAt(
        expectedOccurrence.startDate,
        expectedOccurrence.startTimeZone
    )
    val requestedCalendar = calendarAt(
        requestedOccurrence.startDate,
        requestedOccurrence.startTimeZone
    )
    val masterCalendar = calendarAt(masterRange.startDate, masterRange.startTimeZone)
    val dayShift = (
        localDateNumber(requestedCalendar) - localDateNumber(expectedCalendar)
        ).toInt()
    val translatedStart = Calendar.getInstance(
        timeZone(requestedOccurrence.startTimeZone)
    ).apply {
        clear()
        set(
            masterCalendar.get(Calendar.YEAR),
            masterCalendar.get(Calendar.MONTH),
            masterCalendar.get(Calendar.DAY_OF_MONTH),
            requestedCalendar.get(Calendar.HOUR_OF_DAY),
            requestedCalendar.get(Calendar.MINUTE),
            requestedCalendar.get(Calendar.SECOND)
        )
        set(Calendar.MILLISECOND, requestedCalendar.get(Calendar.MILLISECOND))
        add(Calendar.DAY_OF_MONTH, dayShift)
    }.timeInMillis
    val requestedDuration = requestedOccurrence.endDate - requestedOccurrence.startDate
    val expectedDuration = expectedOccurrence.endDate - expectedOccurrence.startDate
    val masterDuration = masterRange.endDate - masterRange.startDate
    val translatedDuration = when {
        requestedDuration > 0L -> requestedDuration
        expectedDuration > 0L -> expectedDuration
        else -> masterDuration
    }
    return EventDateRangeValue(
        startDate = translatedStart,
        startTimeZone = requestedOccurrence.startTimeZone,
        endDate = translatedStart + translatedDuration,
        endTimeZone = requestedOccurrence.endTimeZone,
        allDay = requestedOccurrence.allDay
    )
}

private fun timeZone(id: String): TimeZone = TimeZone.getTimeZone(id)

private fun calendarAt(value: Long, timeZoneId: String): Calendar =
    Calendar.getInstance(timeZone(timeZoneId)).apply { timeInMillis = value }

private fun localDateNumber(value: Calendar): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            value.get(Calendar.YEAR),
            value.get(Calendar.MONTH),
            value.get(Calendar.DAY_OF_MONTH)
        )
    }.timeInMillis / 86_400_000L

internal data class RecurrenceExceptionResetQuery(
    val selection: String,
    val selectionArgs: Array<String>
)

/**
 * Selects every detached row belonging to a recurring master. Android
 * providers may store ORIGINAL_ID as either the local numeric id or the
 * sync-adapter id, so both identities must be covered. Cancellations are
 * intentionally included: flattening an entire series restores those slots
 * just like Google Calendar and Samsung Calendar do.
 */
internal fun recurrenceExceptionResetQuery(
    calendarId: String,
    masterEventId: String,
    masterSyncId: String?
): RecurrenceExceptionResetQuery {
    val originalIds = listOfNotNull(masterEventId, masterSyncId).distinct()
    val placeholders = originalIds.joinToString(",") { "?" }
    return RecurrenceExceptionResetQuery(
        selection = "${Events.CALENDAR_ID} = ? AND ${Events._ID} != ? AND " +
            "${Events.ORIGINAL_ID} IN ($placeholders) AND " +
            "${Events.ORIGINAL_INSTANCE_TIME} IS NOT NULL AND " +
            "${Events.DELETED} != 1",
        selectionArgs =
            (listOf(calendarId, masterEventId) + originalIds).toTypedArray()
    )
}

/**
 * Columns accepted by CalendarProvider's CONTENT_EXCEPTION_URI for changing a
 * single occurrence. DTEND is deliberately absent: the provider rejects it
 * on this URI and derives the exception end from DTSTART + DURATION.
 */
internal fun recurrenceExceptionDateChanges(
    value: EventDateRangeValue
): Map<String, Any?> {
    val durationMillis = value.endDate - value.startDate
    val duration = if (value.allDay && durationMillis >= 0L &&
        durationMillis % 86_400_000L == 0L
    ) {
        "P${durationMillis / 86_400_000L}D"
    } else {
        durationForDateRange(value)
    }
    return mapOf(
        Events.DTSTART to value.startDate,
        Events.DURATION to duration,
        Events.EVENT_TIMEZONE to value.startTimeZone,
        Events.EVENT_END_TIMEZONE to value.endTimeZone,
        Events.ALL_DAY to if (value.allDay) 1 else 0
    )
}

internal data class RecurrenceExceptionColorWritePlan(
    val exceptionInsertValues: Map<String, Any?>,
    val eventUpdateValues: Map<String, Any?>
)

/**
 * Samsung's CalendarProvider crashes when EVENT_COLOR_KEY is supplied while
 * inserting through CONTENT_EXCEPTION_URI. The same value is accepted when
 * the newly inserted exception is updated through the normal Events URI, so
 * keep that second operation in the same provider batch.
 */
internal fun recurrenceExceptionColorWritePlan(
    hasColorChange: Boolean,
    requestedColorKey: Int?
): RecurrenceExceptionColorWritePlan {
    if (!hasColorChange) {
        return RecurrenceExceptionColorWritePlan(emptyMap(), emptyMap())
    }
    val eventUpdateValues = if (requestedColorKey == null) {
        mapOf(
            Events.EVENT_COLOR_KEY to null,
            Events.EVENT_COLOR to null
        )
    } else {
        mapOf(Events.EVENT_COLOR_KEY to requestedColorKey)
    }
    return RecurrenceExceptionColorWritePlan(
        exceptionInsertValues = emptyMap(),
        eventUpdateValues = eventUpdateValues
    )
}
