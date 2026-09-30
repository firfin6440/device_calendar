package com.builttoroam.devicecalendar

import org.dmfs.rfc5545.recur.RecurrenceRule

/** Count from RRULE/DTSTART, never from a loaded Instances subset or moved dates. */
internal fun recurrenceDeletionPrefix(rawRule: String, start: Long, zone: String, boundary: Long): String? {
    val rule = RecurrenceRule(rawRule)
    if (!recurrenceRuleContainsOccurrenceStart(rule, start, zone, boundary)) return null
    val count = recurrenceSeriesSplitPosition(rule, start, zone, boundary).occurrencesBefore
    if (count == 0) return null // Caller deletes the entire root at DTSTART.
    // COUNT and UNTIL are mutually exclusive. Use a finite COUNT for exactly
    // the same ordinal contract as the mutation layer's expected prefix.
    val parts = rule.toString().split(";").filterNot { it.startsWith("COUNT=") || it.startsWith("UNTIL=") }
    return (parts + "COUNT=$count").joinToString(";")
}
