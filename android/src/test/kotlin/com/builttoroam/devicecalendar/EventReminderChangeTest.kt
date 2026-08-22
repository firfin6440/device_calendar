package com.builttoroam.devicecalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventReminderChangeTest {
    @Test
    fun parsesAndCanonicalizesReminderValues() {
        val reminders = parseEventReminderValues(
            listOf(
                mapOf("minutes" to 30, "method" to 2),
                mapOf("minutes" to 10, "method" to 1),
                mapOf("minutes" to 30, "method" to 1)
            )
        )

        assertEquals(
            listOf(
                EventReminderValue(10, 1),
                EventReminderValue(30, 1),
                EventReminderValue(30, 2)
            ),
            reminders
        )
    }

    @Test
    fun rejectsMalformedReminderValues() {
        assertNull(parseEventReminderValues(null))
        assertNull(parseEventReminderValues(listOf(mapOf("minutes" to -1, "method" to 1))))
        assertNull(parseEventReminderValues(listOf(mapOf("minutes" to 10))))
    }
}
