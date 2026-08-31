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
