package com.builttoroam.devicecalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDurationParserTest {
    @Test
    fun parsesAndroidSecondsDurationUsedByRecurringEvents() {
        assertEquals(3_600_000L, parseDurationMillis("P3600S"))
        assertEquals(3_600_000L, parseDurationMillis("+P3600S"))
        assertEquals(-3_600_000L, parseDurationMillis("-P3600S"))
    }

    @Test
    fun parsesRfc2445DurationComponents() {
        assertEquals(3_600_000L, parseDurationMillis("PT1H"))
        assertEquals(5_400_000L, parseDurationMillis("PT1H30M"))
        assertEquals(86_400_000L, parseDurationMillis("P1D"))
        assertEquals(604_800_000L, parseDurationMillis("P1W"))
    }

    @Test
    fun rejectsMissingAndMalformedDurations() {
        assertNull(parseDurationMillis(null))
        assertNull(parseDurationMillis("3600"))
        assertNull(parseDurationMillis("P1X"))
    }

    @Test
    fun instanceDurationRepairsATransientZeroLengthGeneratedOccurrence() {
        assertEquals(
            7_200_000L,
            resolveInstanceEndMillis(
                start = 3_600_000L,
                rawEnd = 3_600_000L,
                duration = "P3600S"
            )
        )
    }

    @Test
    fun instanceEndWinsWhenTheProviderAlreadyGeneratedAHealthyRange() {
        assertEquals(
            9_000_000L,
            resolveInstanceEndMillis(
                start = 3_600_000L,
                rawEnd = 9_000_000L,
                duration = "P3600S"
            )
        )
    }
}
