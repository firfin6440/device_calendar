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
    fun masterEventsProjectTheSyncId() {
        assertEquals(
            CalendarContract.Events._SYNC_ID,
            Constants.MASTER_EVENT_PROJECTION[Constants.MASTER_EVENT_PROJECTION_SYNC_ID_INDEX]
        )
    }
}
