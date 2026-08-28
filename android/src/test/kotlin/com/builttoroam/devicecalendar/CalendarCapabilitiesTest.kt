package com.builttoroam.devicecalendar

import android.provider.CalendarContract
import com.builttoroam.devicecalendar.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarCapabilitiesTest {
    @Test
    fun calendarProjectionContainsProviderCapabilities() {
        assertEquals(
            listOf(
                CalendarContract.Calendars.MAX_REMINDERS,
                CalendarContract.Calendars.ALLOWED_REMINDERS,
                CalendarContract.Calendars.ALLOWED_AVAILABILITY,
                CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES,
                CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE,
                CalendarContract.Calendars.CAN_ORGANIZER_RESPOND,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS,
                CalendarContract.Calendars.CALENDAR_TIME_ZONE,
                CalendarContract.Calendars.CALENDAR_LOCATION,
                CalendarContract.Calendars.CALENDAR_COLOR_KEY
            ),
            Constants.CALENDAR_PROJECTION.slice(
                Constants.CALENDAR_PROJECTION_MAX_REMINDERS_INDEX..Constants.CALENDAR_PROJECTION_COLOR_KEY_INDEX
            )
        )
    }

    @Test
    fun parsesCommaSeparatedCapabilities() {
        assertNull(parseCalendarCapabilityValues(null))
        assertEquals(emptyList<Int>(), parseCalendarCapabilityValues(""))
        assertEquals(listOf(0, 1, 4), parseCalendarCapabilityValues("0, 1,4,1"))
        assertNull(parseCalendarCapabilityValues("0,nope,1"))
    }

    @Test
    fun convertsAndroidAvailabilityValuesToPortableNames() {
        assertEquals(
            listOf("Busy", "Free", "Tentative"),
            parseCalendarAvailabilityValues("0,1,2")
        )
    }
}
