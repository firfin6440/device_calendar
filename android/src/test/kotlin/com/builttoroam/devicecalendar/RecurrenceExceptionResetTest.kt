package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Events
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class RecurrenceExceptionResetTest {
    private fun at(text: String) = ZonedDateTime.parse(text)
    private fun ms(date: ZonedDateTime) = date.toInstant().toEpochMilli()
    private fun range(start: ZonedDateTime, end: ZonedDateTime = start.plusHours(1)) =
        EventDateRangeValue(ms(start), start.zone.id, ms(end), end.zone.id, false)
    private val monday = at("2026-09-07T19:45:00+01:00[Europe/London]")

    @Test fun fieldOnlyResetUpdatesTuesdayInsteadOfCancellingIt() {
        val reset = recurrenceExceptionResets(listOf(ms(monday.plusDays(1))), range(monday),
            "FREQ=DAILY;COUNT=2", range(monday), "FREQ=DAILY;COUNT=2").single()
        assertEquals(range(monday.plusDays(1)), reset.range)
        val values = recurrenceExceptionResetValues(reset.range!!)
        assertFalse(values.containsKey(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals(ms(monday.plusDays(1)), values[Events.DTSTART])
        assertFalse(values.containsKey(Events._ID))
        assertFalse(values.containsKey(Events._SYNC_ID))
        assertFalse(values.containsKey(Events.ORIGINAL_ID))
        assertFalse(values.containsKey(Events.ORIGINAL_SYNC_ID))
        assertFalse(values.containsKey(Events.DELETED))
        for (column in listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION)) {
            assertTrue(values.containsKey(column))
            assertNull(values[column])
        }
    }

    @Test fun moving1945SeriesTo1950RetiresTheOldOriginalAnchor() {
        val next = monday.plusMinutes(5)
        val reset = recurrenceExceptionResets(listOf(ms(monday.plusDays(1))), range(monday),
            "FREQ=DAILY;COUNT=2", range(next), "FREQ=DAILY;COUNT=2").single()
        assertEquals(ms(monday.plusDays(1)), reset.originalSlot)
        assertNull(reset.range)
    }

    @Test fun shortenedRuleUpdatesSurvivorsAndRemovesOnlyExcludedMembers() {
        val slots = (0L..4L).map { ms(monday.plusDays(it)) }
        val resets = recurrenceExceptionResets(slots, range(monday), "FREQ=DAILY;COUNT=5",
            range(monday.plusDays(1)), "FREQ=DAILY;COUNT=3")
        assertEquals(slots, resets.map { it.originalSlot })
        assertNull(resets[0].range)
        for (i in 1..3) assertEquals(range(monday.plusDays(i.toLong())), resets[i].range)
        assertNull(resets[4].range)
    }

    @Test fun sparseUnorderedExceptionsMatchResultingSlotsNotListPosition() {
        val resets = recurrenceExceptionResets(listOf(ms(monday.plusDays(4)), ms(monday)),
            range(monday), "FREQ=DAILY;COUNT=5", range(monday), "FREQ=WEEKLY;COUNT=5")
        assertNull(resets[0].range)
        assertEquals(range(monday), resets[1].range)
    }

    @Test fun weeklyMoveAcrossDstUsesLocalTimeAndNewZone() {
        val before = at("2026-10-18T09:00:00+01:00[Europe/London]")
        for (zone in listOf("Europe/London", "America/New_York", "Asia/Kolkata")) {
            val after = before.withZoneSameLocal(java.time.ZoneId.of(zone)).plusMinutes(15)
            val reset = recurrenceExceptionResets(listOf(ms(before.plusWeeks(3))), range(before),
                "FREQ=WEEKLY;COUNT=5", range(after), "FREQ=WEEKLY;COUNT=5").single()
            assertNull(reset.range)
        }
    }

    @Test fun allDayAndOvernightKeepCivilDurationAcrossDst() {
        val before = at("2026-10-24T00:00:00+01:00[Europe/London]")
        for (allDay in listOf(false, true)) {
            val template = range(before, before.plusDays(1)).copy(allDay = allDay)
            val reset = recurrenceExceptionResets(listOf(ms(before.plusDays(1))), template,
                "FREQ=DAILY;COUNT=3", template, "FREQ=DAILY;COUNT=3").single()
            assertEquals(range(before.plusDays(1), before.plusDays(2)).copy(allDay = allDay), reset.range)
            assertFalse(recurrenceExceptionResetValues(reset.range!!).containsKey(Events.ORIGINAL_ALL_DAY))
        }
    }

    @Test fun unboundedRuleStopsAtLastStoredExceptionAndDoesNotInventOrphans() {
        val resets = recurrenceExceptionResets(listOf(ms(monday.plusWeeks(10)), ms(monday.plusDays(1))),
            range(monday), "FREQ=WEEKLY", range(monday), "FREQ=WEEKLY")
        assertEquals(range(monday.plusWeeks(10)), resets[0].range)
        assertNull(resets[1].range)
        assertTrue(recurrenceExceptionResets(emptyList(), range(monday), "FREQ=WEEKLY",
            range(monday), "FREQ=WEEKLY").isEmpty())
    }
}
