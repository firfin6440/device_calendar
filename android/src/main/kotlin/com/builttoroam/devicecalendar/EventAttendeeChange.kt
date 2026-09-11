package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Attendees
import com.builttoroam.devicecalendar.models.Attendee

data class EventAttendeeValue(
    val name: String?,
    val email: String,
    val role: Int
) {
    fun identity(): String = "${email.trim().lowercase()}:$role"

    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "email" to email,
        "role" to role
    )
}

fun parseEventAttendeeValues(value: Any?): List<EventAttendeeValue>? {
    val rawValues = value as? List<*> ?: return null
    val parsed = rawValues.map { raw ->
        val map = raw as? Map<*, *> ?: return null
        val name = (map["name"] as? String)?.trim()?.ifEmpty { null }
        val email = (map["email"] as? String)?.trim()?.ifEmpty { null }
            ?: return null
        val role = (map["role"] as? Number)?.toInt() ?: return null
        if (role !in setOf(
                android.provider.CalendarContract.Attendees.TYPE_NONE,
                android.provider.CalendarContract.Attendees.TYPE_REQUIRED,
                android.provider.CalendarContract.Attendees.TYPE_OPTIONAL
            )
        ) return null
        EventAttendeeValue(name, email, role)
    }
    return parsed.distinctBy(EventAttendeeValue::identity)
        .sortedBy(EventAttendeeValue::identity)
}

internal fun eventIsOwnedByCalendarOwner(
    organizerEmail: String?,
    ownerEmail: String?
): Boolean = !ownerEmail.isNullOrBlank() &&
    (organizerEmail.isNullOrBlank() || organizerEmail.equals(ownerEmail, ignoreCase = true))

/**
 * Adds the calendar owner only when an owned event actually has attendees.
 * An existing owner row is retained verbatim so a later RSVP change is not
 * overwritten by an unrelated attendee edit.
 */
internal fun attendeesForOwnedEventWrite(
    attendees: List<Attendee>,
    ownerEmail: String?,
    eventIsOwnedByCurrentUser: Boolean
): List<Attendee> {
    if (attendees.isEmpty() || ownerEmail.isNullOrBlank() ||
        !eventIsOwnedByCurrentUser || attendees.any {
            it.emailAddress.equals(ownerEmail, ignoreCase = true)
        }
    ) {
        return attendees.toList()
    }
    return attendees + Attendee(
        ownerEmail,
        null,
        Attendees.TYPE_REQUIRED,
        Attendees.ATTENDEE_STATUS_ACCEPTED,
        true,
        true
    )
}

internal data class RecurrenceExceptionAttendeePlan(
    val replaceInheritedRows: Boolean
)

/**
 * Participant rows inserted through CONTENT_EXCEPTION_URI are inherited from
 * the master by Android. Any occurrence-local participant or RSVP write must
 * replace that inherited set before inserting its requested rows; appending
 * would duplicate every attendee and can leave contradictory current-user
 * statuses on the exception.
 */
internal fun recurrenceExceptionAttendeePlan(
    hasAttendeeStatusChange: Boolean,
    hasAttendeesChange: Boolean,
    hasResourcesChange: Boolean
): RecurrenceExceptionAttendeePlan = RecurrenceExceptionAttendeePlan(
    replaceInheritedRows = hasAttendeeStatusChange ||
        hasAttendeesChange || hasResourcesChange
)

/**
 * Collapses duplicate rows for the calendar owner only when the Events row
 * supplies an authoritative SELF_ATTENDEE_STATUS matching one of them.
 *
 * Older KeepCal builds appended occurrence attendees after Android had
 * already inherited the master's rows. That can leave both the inherited and
 * requested RSVP rows on a detached exception. Row ordering is not evidence,
 * so this deliberately does not pick the first or last row. If the event-level
 * status cannot prove which row is current, the contradiction is preserved for
 * the higher evidence layer to reject.
 */
internal fun attendeesWithAuthoritativeSelfStatus(
    attendees: List<Attendee>,
    calendarOwnerEmail: String?,
    authoritativeSelfStatus: Int?
): MutableList<Attendee> {
    if (calendarOwnerEmail.isNullOrBlank() || authoritativeSelfStatus == null) {
        return attendees.toMutableList()
    }

    val indexedOwnerRows = attendees.withIndex().filter {
        it.value.emailAddress.equals(calendarOwnerEmail, ignoreCase = true)
    }
    if (indexedOwnerRows.size < 2) return attendees.toMutableList()

    val replacements = mutableMapOf<Int, Attendee>()
    val suppressedIndexes = mutableSetOf<Int>()
    indexedOwnerRows.groupBy { it.value.role }.values.forEach { roleRows ->
        if (roleRows.size < 2) return@forEach
        val authoritative = roleRows.lastOrNull {
            it.value.attendanceStatus == authoritativeSelfStatus
        } ?: return@forEach
        val firstIndex = roleRows.first().index
        val displayName = authoritative.value.name
            ?: roleRows.firstNotNullOfOrNull { it.value.name }
        replacements[firstIndex] = Attendee(
            authoritative.value.emailAddress,
            displayName,
            authoritative.value.role,
            authoritativeSelfStatus,
            roleRows.any { it.value.isOrganizer == true },
            true
        )
        roleRows.drop(1).forEach { suppressedIndexes.add(it.index) }
    }

    return attendees.mapIndexedNotNull { index, attendee ->
        when {
            replacements.containsKey(index) -> replacements[index]
            suppressedIndexes.contains(index) -> null
            else -> attendee
        }
    }.toMutableList()
}
