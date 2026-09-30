package com.builttoroam.devicecalendar

import org.dmfs.rfc5545.recur.RecurrenceRule
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.util.TimeZone

class RecurrenceDeletionTest {
    private val start = ZonedDateTime.parse("2026-10-18T09:00:00+01:00[Europe/London]")
    @Test fun followingKeepsExactlyThePrefixAcrossDstForEveryLimit() {
        for (suffix in listOf("", ";COUNT=5", ";UNTIL=20261201T235959Z")) {
            val prefix = recurrenceDeletionPrefix("FREQ=WEEKLY;BYDAY=SU$suffix", start.toInstant().toEpochMilli(),
                "Europe/London", start.plusWeeks(2).toInstant().toEpochMilli())!!
            val iterator = RecurrenceRule(prefix).iterator(start.toInstant().toEpochMilli(), TimeZone.getTimeZone("Europe/London"))
            val slots = mutableListOf<Long>()
            while (iterator.hasNext()) slots.add(iterator.nextMillis())
            assertEquals(listOf(start.toInstant().toEpochMilli(), start.plusWeeks(1).toInstant().toEpochMilli()), slots)
        }
    }
    @Test fun firstOccurrenceCannotLeaveAnEmptyPrefix() {
        assertNull(recurrenceDeletionPrefix("FREQ=DAILY;COUNT=5", start.toInstant().toEpochMilli(), "Europe/London", start.toInstant().toEpochMilli()))
    }
    @Test fun movedDisplayTimeIsNotAnOriginalBoundary() {
        assertNull(recurrenceDeletionPrefix("FREQ=WEEKLY;COUNT=5", start.toInstant().toEpochMilli(), "Europe/London", start.plusWeeks(2).plusHours(1).toInstant().toEpochMilli()))
    }
}
