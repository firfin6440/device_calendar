package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Attendees
import com.builttoroam.devicecalendar.models.Attendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAttendeeChangeTest {
    @Test
    fun ownedEventWithNoAttendeesDoesNotAddOwner() {
        assertTrue(
            attendeesForOwnedEventWrite(
                attendees = emptyList(),
                ownerEmail = "owner@example.com",
                eventIsOwnedByCurrentUser = true
            ).isEmpty()
        )
    }

    @Test
    fun invitingGuestToOwnedEventAddsAcceptedOwner() {
        val guest = attendee("guest@example.com", Attendees.ATTENDEE_STATUS_INVITED)

        val result = attendeesForOwnedEventWrite(
            attendees = listOf(guest),
            ownerEmail = "owner@example.com",
            eventIsOwnedByCurrentUser = true
        )

        assertEquals(2, result.size)
        assertTrue(result.contains(guest))
        val owner = result.single { it.emailAddress == "owner@example.com" }
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, owner.attendanceStatus)
        assertEquals(Attendees.TYPE_REQUIRED, owner.role)
        assertEquals(true, owner.isOrganizer)
        assertEquals(true, owner.isCurrentUser)
    }

    @Test
    fun laterOwnerRsvpIsPreservedWhenGuestsChange() {
        val owner = Attendee(
            "owner@example.com",
            null,
            Attendees.TYPE_REQUIRED,
            Attendees.ATTENDEE_STATUS_DECLINED,
            true,
            true
        )
        val guest = attendee("guest@example.com", Attendees.ATTENDEE_STATUS_INVITED)

        val result = attendeesForOwnedEventWrite(
            attendees = listOf(owner, guest),
            ownerEmail = "owner@example.com",
            eventIsOwnedByCurrentUser = true
        )

        assertEquals(owner, result.single { it.emailAddress == "owner@example.com" })
        assertEquals(Attendees.ATTENDEE_STATUS_DECLINED, owner.attendanceStatus)
    }

    @Test
    fun editingEventOwnedBySomeoneElseDoesNotAddCalendarOwner() {
        val guest = attendee("guest@example.com", Attendees.ATTENDEE_STATUS_INVITED)

        val result = attendeesForOwnedEventWrite(
            attendees = listOf(guest),
            ownerEmail = "calendar-user@example.com",
            eventIsOwnedByCurrentUser = false
        )

        assertEquals(listOf(guest), result)
        assertFalse(result.any { it.emailAddress == "calendar-user@example.com" })
    }

    @Test
    fun ownershipMatchesOrganizerCaseInsensitively() {
        assertTrue(eventIsOwnedByCalendarOwner("Owner@Example.com", "owner@example.com"))
        assertTrue(eventIsOwnedByCalendarOwner(null, "owner@example.com"))
        assertFalse(eventIsOwnedByCalendarOwner("other@example.com", "owner@example.com"))
        assertFalse(eventIsOwnedByCalendarOwner(null, null))
    }

    private fun attendee(email: String, status: Int) = Attendee(
        email,
        null,
        Attendees.TYPE_REQUIRED,
        status,
        false,
        false
    )
}
