package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import com.builttoroam.devicecalendar.models.Attendee

internal fun shouldReplaceRecurringSeries(
    recurrenceWasChanged: Boolean,
    currentRawRule: String?,
    requestedRawRule: String?
): Boolean = recurrenceWasChanged && currentRawRule != null && requestedRawRule == null

/**
 * Complete provider fields for changing an event's recurrence definition.
 *
 * DTSTART is intentionally present even when it did not change. Android's
 * incremental Instances updater is handed only the modified columns and needs
 * DTSTART in order to invalidate and rebuild generated occurrences.
 */
internal fun recurrenceStorageChanges(
    rawRule: String?,
    startDate: Long,
    startTimeZone: String?,
    endDate: Long,
    endTimeZone: String?,
    allDay: Boolean,
    duration: String?
): Map<String, Any?> = buildMap {
    put(Events.DTSTART, startDate)
    put(Events.EVENT_TIMEZONE, startTimeZone)
    put(Events.ALL_DAY, if (allDay) 1 else 0)
    put(Events.RRULE, rawRule)
    if (rawRule == null) {
        put(Events.RDATE, null)
        put(Events.EXRULE, null)
        put(Events.EXDATE, null)
        put(Events.DTEND, endDate)
        put(Events.EVENT_END_TIMEZONE, endTimeZone)
        put(Events.DURATION, null)
    } else {
        put(Events.DTEND, null)
        put(Events.EVENT_END_TIMEZONE, null)
        put(Events.DURATION, duration)
    }
}

/**
 * Complete provider fields for moving an already-recurring event.
 *
 * Rewriting the unchanged RRULE is intentional: some CalendarProvider
 * implementations do not invalidate their generated Instances rows when a
 * recurring master is updated with DTSTART/DURATION alone.
 */
internal fun recurringEventDateStorageChanges(
    rawRule: String,
    startDate: Long,
    startTimeZone: String?,
    endDate: Long,
    endTimeZone: String?,
    allDay: Boolean,
    duration: String?
): Map<String, Any?> = recurrenceStorageChanges(
    rawRule = rawRule,
    startDate = startDate,
    startTimeZone = startTimeZone,
    endDate = endDate,
    endTimeZone = endTimeZone,
    allDay = allDay,
    duration = duration
)

/** Preserve provider attendee rows exactly when a recurring series is split. */
internal fun recurrenceSplitAttendees(attendees: List<Attendee>): List<Attendee> =
    attendees.toList()

internal fun recurrenceSplitOwnershipValues(organizerEmail: String?): Map<String, Any> =
    buildMap {
        put(Events.HAS_ATTENDEE_DATA, 1)
        if (!organizerEmail.isNullOrBlank()) put(Events.ORGANIZER, organizerEmail)
    }

internal fun recurrenceSplitAttendeeRelationship(attendee: Attendee): Int =
    if (attendee.isOrganizer == true) {
        Attendees.RELATIONSHIP_ORGANIZER
    } else {
        Attendees.RELATIONSHIP_ATTENDEE
    }
