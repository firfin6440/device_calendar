package com.builttoroam.devicecalendar

import android.provider.CalendarContract.Events
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun removingRecurrenceKeepsTheSelectedOccurrenceRatherThanTheMaster() {
        val mondayMaster = EventDateRangeValue(
            startDate = 1_786_492_800_000L,
            startTimeZone = "Europe/London",
            endDate = 1_786_496_400_000L,
            endTimeZone = "Europe/London",
            allDay = false
        )
        val wednesdayOccurrenceStart = 1_786_665_600_000L

        val standalone = standaloneReplacementRange(
            masterRange = mondayMaster,
            selectedOccurrenceStart = wednesdayOccurrenceStart,
            requestedOccurrenceRange = null
        )

        assertEquals(wednesdayOccurrenceStart, standalone.startDate)
        assertEquals(wednesdayOccurrenceStart + 3_600_000L, standalone.endDate)
        assertEquals("Europe/London", standalone.startTimeZone)
        assertEquals("Europe/London", standalone.endTimeZone)
        assertEquals(false, standalone.allDay)
    }

    @Test
    fun movingAnExceptionForTheWholeSeriesUsesRequestedClockTimeAndDuration() {
        val master = EventDateRangeValue(
            startDate = 1_788_807_600_000L,
            startTimeZone = "UTC",
            endDate = 1_788_811_200_000L,
            endTimeZone = "UTC",
            allDay = false
        )
        val selectedException = EventDateRangeValue(
            startDate = 1_788_892_200_000L,
            startTimeZone = "UTC",
            endDate = 1_788_895_800_000L,
            endTimeZone = "UTC",
            allDay = false
        )
        val startOnlyPayload = EventDateRangeValue(
            startDate = 1_788_897_600_000L,
            startTimeZone = "UTC",
            endDate = 1_788_897_600_000L,
            endTimeZone = "UTC",
            allDay = false
        )

        val translated = translateOccurrenceRangeToSeriesMaster(
            master,
            selectedException,
            startOnlyPayload
        )

        assertEquals(1_788_811_200_000L, translated.startDate)
        assertEquals(1_788_814_800_000L, translated.endDate)
        assertEquals("P3600S", durationForDateRange(translated))
    }

    @Test
    fun wholeSeriesTranslationUsesTheRequestedPositiveDuration() {
        val master = EventDateRangeValue(
            1_788_807_600_000L,
            "UTC",
            1_788_811_200_000L,
            "UTC",
            false
        )
        val expected = EventDateRangeValue(
            1_788_894_000_000L,
            "UTC",
            1_788_897_600_000L,
            "UTC",
            false
        )
        val requested = EventDateRangeValue(
            1_788_984_000_000L,
            "UTC",
            1_788_991_200_000L,
            "UTC",
            false
        )

        val translated = translateOccurrenceRangeToSeriesMaster(
            master,
            expected,
            requested
        )

        assertEquals(1_788_897_600_000L, translated.startDate)
        assertEquals(1_788_904_800_000L, translated.endDate)
    }

    @Test
    fun singleOccurrenceChangesLetTheProviderDeriveDtendFromDuration() {
        val requested = EventDateRangeValue(
            startDate = 10_000L,
            startTimeZone = "Europe/London",
            endDate = 3_610_000L,
            endTimeZone = "Europe/London",
            allDay = false
        )

        val changes = recurrenceExceptionDateChanges(requested)

        assertEquals(10_000L, changes[Events.DTSTART])
        assertEquals("P3600S", changes[Events.DURATION])
        assertFalse(
            "CalendarProvider rejects DTEND on CONTENT_EXCEPTION_URI",
            changes.containsKey(Events.DTEND)
        )
    }

    @Test
    fun allDayOccurrenceDurationUsesWholeDays() {
        val requested = EventDateRangeValue(
            startDate = 0L,
            startTimeZone = "UTC",
            endDate = 86_400_000L,
            endTimeZone = "UTC",
            allDay = true
        )

        assertEquals(
            "P1D",
            recurrenceExceptionDateChanges(requested)[Events.DURATION]
        )
    }

    @Test
    fun wholeSeriesResetFindsExceptionsByLocalAndSyncMasterIds() {
        val query = recurrenceExceptionResetQuery(
            calendarId = "7",
            masterEventId = "20265",
            masterSyncId = "google-sync-id"
        )

        assertArrayEquals(
            arrayOf("7", "20265", "20265", "google-sync-id"),
            query.selectionArgs
        )
        assertTrue(query.selection.contains("${Events.ORIGINAL_ID} IN (?,?)"))
        assertTrue(query.selection.contains(Events.ORIGINAL_INSTANCE_TIME))
        assertFalse(
            "Cancelled exceptions must also be flattened",
            query.selection.contains(Events.STATUS)
        )
    }

    @Test
    fun wholeSeriesResetDoesNotDuplicateIdenticalMasterIds() {
        val query = recurrenceExceptionResetQuery(
            calendarId = "7",
            masterEventId = "20265",
            masterSyncId = "20265"
        )

        assertArrayEquals(arrayOf("7", "20265", "20265"), query.selectionArgs)
        assertTrue(query.selection.contains("${Events.ORIGINAL_ID} IN (?)"))
    }

    @Test
    fun singleOccurrenceColorIsDeferredPastTheExceptionInsert() {
        val plan = recurrenceExceptionColorWritePlan(
            hasColorChange = true,
            requestedColorKey = 9
        )

        assertFalse(plan.exceptionInsertValues.containsKey(Events.EVENT_COLOR_KEY))
        assertFalse(plan.exceptionInsertValues.containsKey(Events.EVENT_COLOR))
        assertEquals(9, plan.eventUpdateValues[Events.EVENT_COLOR_KEY])
    }

    @Test
    fun clearingSingleOccurrenceColorIsAlsoDeferred() {
        val plan = recurrenceExceptionColorWritePlan(
            hasColorChange = true,
            requestedColorKey = null
        )

        assertEquals(emptyMap<String, Any?>(), plan.exceptionInsertValues)
        assertEquals(null, plan.eventUpdateValues[Events.EVENT_COLOR_KEY])
        assertEquals(null, plan.eventUpdateValues[Events.EVENT_COLOR])
    }
}
