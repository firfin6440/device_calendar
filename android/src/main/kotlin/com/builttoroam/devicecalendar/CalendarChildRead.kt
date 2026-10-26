package com.builttoroam.devicecalendar

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri

internal const val CALENDAR_CHILD_READ_BATCH_SIZE = 100

internal data class CalendarChildRead<T>(
    val rowsById: Map<String, List<T>>,
    val unreadableIds: Set<String>,
    val firstFailure: Exception?,
    val queryCount: Int,
    val fallbackGroups: Int
)

/** Request-local children keyed by physical Events ID, never occurrence/date.
 * A group is accepted only after EOF and successful close. On a non-permission
 * failure discard the whole group's staging and read each ID independently;
 * an incomplete cursor must never certify that another ID has no children.
 * No cache or provider snapshot/transaction is implied across these queries. */
internal fun <T> readCalendarChildren(
    resolver: ContentResolver?,
    uri: Uri,
    projection: Array<String>,
    eventIds: Iterable<String>,
    parse: (Cursor) -> T?
): CalendarChildRead<T> {
    val rows = linkedMapOf<String, List<T>>()
    val failures = linkedSetOf<String>()
    var firstFailure: Exception? = null
    var queryCount = 0
    var fallbackGroups = 0
    fun read(ids: List<String>): Map<String, List<T>> {
        val staged = ids.associateWith { mutableListOf<T>() }
        val selection = if (ids.size == 1) "event_id = ?"
            else "event_id IN (${ids.joinToString(",") { "?" }})"
        queryCount++
        resolver?.query(uri, projection, selection, ids.toTypedArray(), null).use { cursor ->
            checkNotNull(cursor) { "CalendarProvider returned no child cursor" }
            val ownerColumn = cursor.getColumnIndexOrThrow("event_id")
            while (cursor.moveToNext()) {
                val owner = cursor.getString(ownerColumn)
                val children = checkNotNull(staged[owner]) {
                    "CalendarProvider returned a child outside the requested IDs"
                }
                parse(cursor)?.let { children.add(it) }
            }
        }
        return staged
    }
    fun permissionFailure(error: Exception) {
        // Cursor.close can add a suppressed exception to a read/parse failure.
        // Permission revocation at either point must not trigger more queries.
        if (error is SecurityException) throw error
        error.suppressed.filterIsInstance<SecurityException>().firstOrNull()?.let { throw it }
    }
    for (group in eventIds.distinct().chunked(CALENDAR_CHILD_READ_BATCH_SIZE)) {
        try {
            rows.putAll(read(group))
        } catch (error: Exception) {
            permissionFailure(error)
            if (group.size == 1) {
                failures.add(group.single())
                if (firstFailure == null) firstFailure = error
                continue
            }
            fallbackGroups++
            for (id in group) {
                try {
                    rows.putAll(read(listOf(id)))
                } catch (individualError: Exception) {
                    permissionFailure(individualError)
                    failures.add(id)
                    if (firstFailure == null) firstFailure = individualError
                }
            }
        }
    }
    return CalendarChildRead(rows, failures, firstFailure, queryCount, fallbackGroups)
}
