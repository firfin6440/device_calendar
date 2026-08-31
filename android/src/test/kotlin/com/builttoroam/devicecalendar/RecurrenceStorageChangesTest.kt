package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import com.builttoroam.devicecalendar.models.Attendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceStorageChangesTest {
    @Test
    fun removingRecurrenceFromSeriesRequiresDeleteAndReplacement() {
        assertTrue(
            shouldReplaceRecurringSeries(
                recurrenceWasChanged = true,
                currentRawRule = "FREQ=WEEKLY;COUNT=2",
                requestedRawRule = null
            )
        )
        assertTrue(!shouldReplaceRecurringSeries(true, null, null))
        assertTrue(!shouldReplaceRecurringSeries(true, "FREQ=WEEKLY", "FREQ=DAILY"))
        assertTrue(!shouldReplaceRecurringSeries(false, "FREQ=WEEKLY", null))
    }

    @Test
    fun removingRecurrenceSuppliesStartAndClearsEveryRecurrenceSource() {
        val changes = recurrenceStorageChanges(
            rawRule = null,
            startDate = 1000L,
            startTimeZone = "Europe/London",
            endDate = 2000L,
            endTimeZone = "Europe/London",
            allDay = false,
            duration = "+P1S"
        )

        assertEquals(1000L, changes[Events.DTSTART])
        assertEquals(2000L, changes[Events.DTEND])
        assertNull(changes[Events.RRULE])
        assertNull(changes[Events.RDATE])
        assertNull(changes[Events.EXRULE])
        assertNull(changes[Events.EXDATE])
        assertNull(changes[Events.DURATION])
        assertTrue(changes.containsKey(Events.DTSTART))
        assertTrue(changes.containsKey(Events.RRULE))
    }

    @Test
    fun settingRecurrenceSuppliesStartAndUsesDuration() {
        val changes = recurrenceStorageChanges(
            rawRule = "FREQ=WEEKLY;COUNT=2",
            startDate = 1000L,
            startTimeZone = "Europe/London",
            endDate = 2000L,
            endTimeZone = "Europe/London",
            allDay = false,
            duration = "+P1S"
        )

        assertEquals(1000L, changes[Events.DTSTART])
        assertEquals("FREQ=WEEKLY;COUNT=2", changes[Events.RRULE])
        assertEquals("+P1S", changes[Events.DURATION])
        assertNull(changes[Events.DTEND])
    }

    @Test
    fun movingRecurringEventRewritesUnchangedRuleToRebuildInstances() {
        val changes = recurringEventDateStorageChanges(
            rawRule = "FREQ=DAILY;COUNT=5",
            startDate = 20_000L,
            startTimeZone = "Europe/London",
            endDate = 23_600L,
            endTimeZone = "Europe/London",
            allDay = false,
            duration = "+P3600S"
        )

        assertEquals(20_000L, changes[Events.DTSTART])
        assertEquals("FREQ=DAILY;COUNT=5", changes[Events.RRULE])
        assertEquals("+P3600S", changes[Events.DURATION])
        assertNull(changes[Events.DTEND])
    }

    @Test
    fun splitDoesNotInventOwnerAttendeeWhenSourceHasNoAttendees() {
        val ownership = recurrenceSplitOwnershipValues("owner@example.com")
        val attendees = recurrenceSplitAttendees(emptyList())

        assertEquals(1, ownership[Events.HAS_ATTENDEE_DATA])
        assertEquals("owner@example.com", ownership[Events.ORGANIZER])
        assertTrue(attendees.isEmpty())
    }

    @Test
    fun splitKeepsPeopleAsAttendeesAndDoesNotDuplicateExistingOrganizer() {
        val organizer = Attendee(
            "owner@example.com",
            "Owner",
            Attendees.TYPE_REQUIRED,
            Attendees.ATTENDEE_STATUS_ACCEPTED,
            true,
            true
        )
        val guest = Attendee(
            "guest@example.com",
            "Guest",
            Attendees.TYPE_REQUIRED,
            Attendees.ATTENDEE_STATUS_INVITED,
            false,
            false
        )

        val sourceAttendees = listOf(organizer, guest)
        val attendees = recurrenceSplitAttendees(sourceAttendees)

        assertEquals(sourceAttendees, attendees)
        assertEquals(
            Attendees.RELATIONSHIP_ORGANIZER,
            recurrenceSplitAttendeeRelationship(organizer)
        )
        assertEquals(
            Attendees.RELATIONSHIP_ATTENDEE,
            recurrenceSplitAttendeeRelationship(guest)
        )
    }
}
