package com.builttoroam.devicecalendar

import org.dmfs.rfc5545.recur.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class RecurrenceSeriesSplitTest {
    @Test
    fun detachedEarlierOccurrencesDoNotChangeTheRruleOrdinal() {
        val monday = ZonedDateTime.parse("2026-09-07T19:00:00+01:00[Europe/London]")
        val wednesday = ZonedDateTime.parse("2026-09-09T19:00:00+01:00[Europe/London]")

        val position = recurrenceSeriesSplitPosition(
            rule = RecurrenceRule("FREQ=DAILY;COUNT=5"),
            masterStart = monday.toInstant().toEpochMilli(),
            masterTimeZone = "Europe/London",
            splitStart = wednesday.toInstant().toEpochMilli()
        )

        // This stays two even if Tuesday and/or Wednesday have detached
        // provider rows: RRULE ordinal is independent from Instances.EVENT_ID.
        assertEquals(2, position.occurrencesBefore)
        assertEquals(
            ZonedDateTime.parse("2026-09-08T19:00:00+01:00[Europe/London]")
                .toInstant()
                .toEpochMilli(),
            position.previousOccurrenceStart
        )
        assertEquals(3, 5 - position.occurrencesBefore)
    }

    @Test
    fun weeklyOrdinalRemainsCorrectAcrossTheDstBoundary() {
        val first = ZonedDateTime.parse("2026-10-18T09:00:00+01:00[Europe/London]")
        val third = ZonedDateTime.parse("2026-11-01T09:00:00Z[Europe/London]")

        val position = recurrenceSeriesSplitPosition(
            rule = RecurrenceRule("FREQ=WEEKLY;COUNT=5;BYDAY=SU"),
            masterStart = first.toInstant().toEpochMilli(),
            masterTimeZone = "Europe/London",
            splitStart = third.toInstant().toEpochMilli()
        )

        assertEquals(2, position.occurrencesBefore)
        assertEquals(
            ZonedDateTime.parse("2026-10-25T09:00:00Z[Europe/London]")
                .toInstant()
                .toEpochMilli(),
            position.previousOccurrenceStart
        )
    }
}
