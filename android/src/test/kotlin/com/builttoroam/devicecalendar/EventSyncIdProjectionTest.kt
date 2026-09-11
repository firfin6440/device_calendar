package com.builttoroam.devicecalendar

import android.provider.CalendarContract
import com.builttoroam.devicecalendar.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class EventSyncIdProjectionTest {
    @Test
    fun eventInstancesProjectTheSyncId() {
        assertEquals(
            CalendarContract.Events._SYNC_ID,
            Constants.EVENT_PROJECTION[Constants.EVENT_PROJECTION_SYNC_ID_INDEX]
        )
    }

    @Test
    fun eventInstancesDoNotProjectTheUnsupportedDirtyColumn() {
        assertEquals(false, Constants.EVENT_PROJECTION.contains(CalendarContract.Events.DIRTY))
    }

    @Test
    fun eventInstancesDoNotProjectTheProviderPrivateMutatorsColumn() {
        assertEquals(false, Constants.EVENT_PROJECTION.contains(CalendarContract.Events.MUTATORS))
    }

    @Test
    fun eventInstancesProjectTheAuthoritativeSelfAttendeeStatus() {
        assertEquals(
            CalendarContract.Events.SELF_ATTENDEE_STATUS,
            Constants.EVENT_PROJECTION[Constants.EVENT_PROJECTION_SELF_ATTENDEE_STATUS_INDEX]
        )
    }

    @Test
    fun masterEventsProjectTheSyncId() {
        assertEquals(
            CalendarContract.Events._SYNC_ID,
            Constants.MASTER_EVENT_PROJECTION[Constants.MASTER_EVENT_PROJECTION_SYNC_ID_INDEX]
        )
    }

    @Test
    fun masterEventsProjectTheDirtyState() {
        assertEquals(
            CalendarContract.Events.DIRTY,
            Constants.MASTER_EVENT_PROJECTION[Constants.MASTER_EVENT_PROJECTION_DIRTY_INDEX]
        )
    }

    @Test
    fun masterEventsProjectTheAuthoritativeSelfAttendeeStatus() {
        assertEquals(
            CalendarContract.Events.SELF_ATTENDEE_STATUS,
            Constants.MASTER_EVENT_PROJECTION[
                Constants.MASTER_EVENT_PROJECTION_SELF_ATTENDEE_STATUS_INDEX
            ]
        )
    }

    @Test
    fun masterEventsDoNotProjectTheProviderPrivateMutatorsColumn() {
        assertEquals(
            false,
            Constants.MASTER_EVENT_PROJECTION.contains(CalendarContract.Events.MUTATORS)
        )
    }
}
