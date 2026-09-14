package com.builttoroam.devicecalendar

internal data class CalendarInstanceQuery(
    val selection: String,
    val selectionArgs: Array<String>
)

/**
 * Interpret locally linked exceptions even when CalendarProvider's derived
 * Instances table still includes the generated occurrence (notably while the
 * master has no remote sync ID). This is read normalization, not optimism.
 *
 * The uncorrelated list subquery collects replaced instance IDs once, using
 * the provider's calendar and instance indexes. Reading Events and Instances
 * in the same statement avoids a race between a separate exception lookup
 * and the visible-range query. A moved-out or cancelled exception need not
 * have a visible Instances row to replace its original slot.
 *
 * Never match on title/current time, change native rows, or wait for sync.
 * Tombstones and malformed self-links are not live replacement authority.
 */
internal fun calendarInstanceQuery(
    calendarId: String,
    start: Long,
    end: Long,
    eventIds: List<String>
): CalendarInstanceQuery {
    val selection = """
        calendar_id = ? AND deleted != 1
        AND (eventStatus IS NULL OR eventStatus != 2)
        AND (
            original_id IS NOT NULL OR original_sync_id IS NOT NULL
            OR (COALESCE(rrule, '') = '' AND COALESCE(rdate, '') = '')
            OR Instances._id NOT IN (
                SELECT keepcal_original._id
                FROM Events AS keepcal_exception
                INNER JOIN Instances AS keepcal_original
                  ON keepcal_original.event_id = keepcal_exception.original_id
                 AND keepcal_original.begin = keepcal_exception.originalInstanceTime
                WHERE keepcal_exception.calendar_id = ?
                  AND keepcal_exception.deleted != 1
                  AND keepcal_exception._id != keepcal_exception.original_id
                  AND COALESCE(keepcal_exception.rrule, '') = ''
                  AND COALESCE(keepcal_exception.rdate, '') = ''
                  AND keepcal_original.begin <= ? AND keepcal_original.end >= ?
            )
        )
    """.trimIndent() + if (eventIds.isEmpty()) "" else
        " AND Instances.event_id IN (${eventIds.joinToString(",") { "?" }})"
    return CalendarInstanceQuery(
        selection,
        (listOf(calendarId, calendarId, end.toString(), start.toString()) + eventIds).toTypedArray()
    )
}
