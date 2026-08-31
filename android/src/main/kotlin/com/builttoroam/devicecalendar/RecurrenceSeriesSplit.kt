package com.builttoroam.devicecalendar

import org.dmfs.rfc5545.recur.RecurrenceRule
import java.util.TimeZone

internal data class RecurrenceSeriesSplitPosition(
    val occurrencesBefore: Int,
    val previousOccurrenceStart: Long?
)

/**
 * Returns the RRULE ordinal immediately before [splitStart].
 *
 * CalendarContract.Instances cannot be used to calculate this ordinal: when
 * an occurrence has a detached exception, its Instances.EVENT_ID is the
 * exception row rather than the recurring master. Counting only master rows
 * therefore shortens the old series too far and gives the new series an extra
 * occurrence.
 */
internal fun recurrenceSeriesSplitPosition(
    rule: RecurrenceRule,
    masterStart: Long,
    masterTimeZone: String,
    splitStart: Long
): RecurrenceSeriesSplitPosition {
    val iterator = rule.iterator(masterStart, TimeZone.getTimeZone(masterTimeZone))
    var occurrencesBefore = 0
    var previousOccurrenceStart: Long? = null
    while (iterator.hasNext()) {
        val occurrenceStart = iterator.nextMillis()
        if (occurrenceStart >= splitStart) break
        occurrencesBefore++
        previousOccurrenceStart = occurrenceStart
    }
    return RecurrenceSeriesSplitPosition(
        occurrencesBefore = occurrencesBefore,
        previousOccurrenceStart = previousOccurrenceStart
    )
}
