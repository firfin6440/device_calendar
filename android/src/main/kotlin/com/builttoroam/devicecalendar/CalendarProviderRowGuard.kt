package com.builttoroam.devicecalendar

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract

/** Assert one copied row and its ownership, not just matching payload values. */
internal fun calendarProviderRowGuard(
    uri: Uri,
    rowId: String,
    selection: String,
    selectionArgs: Array<String>,
    values: ContentValues
): ContentProviderOperation {
    // The provider qualifies public projection columns, but not caller WHERE
    // expressions. Attendees/Reminders queries join Events and Calendars:
    // a bare _id is ambiguous even though the initial projection succeeded.
    val rowIdColumn = when (uri) {
        CalendarContract.Attendees.CONTENT_URI -> "Attendees._id"
        CalendarContract.Reminders.CONTENT_URI -> "Reminders._id"
        CalendarContract.Events.CONTENT_URI -> CalendarContract.Events._ID
        else -> error("Unsupported calendar guard URI: $uri")
    }
    val predicates = mutableListOf("$rowIdColumn = ?", "($selection)")
    val args = mutableListOf(rowId, *selectionArgs)
    val guardedValues = ContentValues(values)
    if (uri == CalendarContract.Events.CONTENT_URI && values.containsKey(CalendarContract.Events.RRULE)) {
        RecurrenceRuleStorageGuard.append(predicates, args, values.getAsString(CalendarContract.Events.RRULE))
        guardedValues.remove(CalendarContract.Events.RRULE)
    }
    return ContentProviderOperation.newAssertQuery(uri)
        .withSelection(predicates.joinToString(" AND "), args.toTypedArray())
        .withValues(guardedValues).withExpectedCount(1).build()
}
