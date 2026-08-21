package com.builttoroam.devicecalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDateRangeChangeTest {
    @Test
    fun parsesAndSerializesTheWholeRange() {
        val range = parseEventDateRangeValue(
            mapOf(
                "startDate" to 1_000L,
                "startTimeZone" to "Europe/London",
                "endDate" to 3_601_000L,
                "endTimeZone" to "Europe/Madrid",
                "allDay" to false
            )
        )

        assertEquals(1_000L, range?.startDate)
        assertEquals("Europe/Madrid", range?.endTimeZone)
        assertEquals(false, range?.allDay)
        assertEquals("P3600S", range?.let(::durationForDateRange))
        assertEquals(3_601_000L, range?.toMap()?.get("endDate"))
    }

    @Test
    fun rejectsIncompleteAndBackwardsRanges() {
        assertNull(parseEventDateRangeValue(emptyMap<String, Any>()))
        assertNull(
            parseEventDateRangeValue(
                mapOf(
                    "startDate" to 2_000L,
                    "startTimeZone" to "Europe/London",
                    "endDate" to 1_000L,
                    "endTimeZone" to "Europe/London",
                    "allDay" to false
                )
            )
        )
    }

    @Test
    fun recurringDurationRequiresWholeSeconds() {
        val range = EventDateRangeValue(0L, "UTC", 1_001L, "UTC", false)
        assertNull(durationForDateRange(range))
    }
}
