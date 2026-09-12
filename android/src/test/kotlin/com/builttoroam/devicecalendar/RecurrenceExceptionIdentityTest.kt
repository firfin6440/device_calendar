package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Events
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class RecurrenceExceptionIdentityTest {
    private val monday = ZonedDateTime.parse("2026-09-07T20:45:00+01:00[Europe/London]")
    private fun range(start: ZonedDateTime) = EventDateRangeValue(
        start.toInstant().toEpochMilli(), start.zone.id,
        start.plusHours(1).toInstant().toEpochMilli(), start.zone.id, false)

    @Test fun overlappingShiftKeepsTuesdayIdentityInsteadOfMovingItToWednesday() {
        for (days in listOf(1L, -1L)) {
            val before = if (days == 1L) monday else monday.plusDays(1)
            val after = before.plusDays(days)
            val tuesday = range(monday.plusDays(1))
            val reset = recurrenceExceptionResets(listOf(tuesday.startDate), range(before),
                "FREQ=DAILY;COUNT=2", range(after), "FREQ=DAILY;COUNT=2").single()
            assertEquals(tuesday, reset.range)
        }
    }

    @Test fun disjointTimeShiftRetiresOldAddressInsteadOfRelabellingIt() {
        val tuesday = range(monday.plusDays(1))
        val reset = recurrenceExceptionResets(listOf(tuesday.startDate), range(monday),
            "FREQ=DAILY;COUNT=2", range(monday.plusHours(1)), "FREQ=DAILY;COUNT=2").single()
        assertNull(reset.range)
    }

    @Test fun resetNeverWritesImmutableIdentityColumns() {
        val values = recurrenceExceptionResetValues(range(monday.plusDays(1)))
        for (column in listOf(Events._ID, Events._SYNC_ID, Events.ORIGINAL_ID,
                Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY)) {
            assertFalse("Must not rewrite $column", values.containsKey(column))
        }
    }
}
