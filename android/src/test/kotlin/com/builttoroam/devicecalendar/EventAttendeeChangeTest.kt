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

    @Test
    fun occurrenceRsvpReplacesInheritedAttendeesBeforeWritingTheException() {
        val rsvp = recurrenceExceptionAttendeePlan(
            hasAttendeeStatusChange = true,
            hasAttendeesChange = false,
            hasResourcesChange = false
        )
        val unchanged = recurrenceExceptionAttendeePlan(
            hasAttendeeStatusChange = false,
            hasAttendeesChange = false,
            hasResourcesChange = false
        )

        assertTrue(rsvp.replaceInheritedRows)
        assertFalse(unchanged.replaceInheritedRows)
    }

    @Test
    fun occurrenceParticipantEditsAlsoReplaceInheritedAttendees() {
        assertTrue(
            recurrenceExceptionAttendeePlan(
                hasAttendeeStatusChange = false,
                hasAttendeesChange = true,
                hasResourcesChange = false
            ).replaceInheritedRows
        )
        assertTrue(
            recurrenceExceptionAttendeePlan(
                hasAttendeeStatusChange = false,
                hasAttendeesChange = false,
                hasResourcesChange = true
            ).replaceInheritedRows
        )
    }

    @Test
    fun authoritativeSelfStatusRepairsLegacyInheritedRsvpDuplicate() {
        val inherited = Attendee(
            "owner@example.com",
            "Owner",
            Attendees.TYPE_REQUIRED,
            Attendees.ATTENDEE_STATUS_ACCEPTED,
            true,
            true
        )
        val requested = Attendee(
            "OWNER@example.com",
            null,
            Attendees.TYPE_REQUIRED,
            Attendees.ATTENDEE_STATUS_TENTATIVE,
            true,
            true
        )
        val guest = attendee("guest@example.com", Attendees.ATTENDEE_STATUS_INVITED)

        val result = attendeesWithAuthoritativeSelfStatus(
            attendees = listOf(inherited, guest, requested),
            calendarOwnerEmail = "owner@example.com",
            authoritativeSelfStatus = Attendees.ATTENDEE_STATUS_TENTATIVE
        )

        assertEquals(2, result.size)
        assertEquals(guest, result.single { it.emailAddress == "guest@example.com" })
        val owner = result.single {
            it.emailAddress.equals("owner@example.com", ignoreCase = true)
        }
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE, owner.attendanceStatus)
        assertEquals("Owner", owner.name)
        assertEquals(true, owner.isOrganizer)
        assertEquals(true, owner.isCurrentUser)
    }

    @Test
    fun unresolvedSelfContradictionIsPreservedForEvidenceRejection() {
        val accepted = attendee("owner@example.com", Attendees.ATTENDEE_STATUS_ACCEPTED)
        val tentative = attendee("owner@example.com", Attendees.ATTENDEE_STATUS_TENTATIVE)

        val result = attendeesWithAuthoritativeSelfStatus(
            attendees = listOf(accepted, tentative),
            calendarOwnerEmail = "owner@example.com",
            authoritativeSelfStatus = Attendees.ATTENDEE_STATUS_DECLINED
        )

        assertEquals(listOf(accepted, tentative), result)
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
