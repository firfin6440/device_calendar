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
        assertNull(parseDurationMillis("P"))
        assertNull(parseDurationMillis("PT"))
    }

    @Test
    fun rejectsComponentMultiplicationAdditionAndNumericOverflow() {
        for (duration in listOf("P9223372036854775807S", "PT9223372036854775807H",
            "PT18446744073709551617H1S", "P100000000000DT2000000000000000S")) {
            assertNull(duration, parseDurationMillis(duration))
        }
        assertEquals(9_000_000_000_000_000_000L, parseDurationMillis("P9000000000000000S"))
    }

    @Test
    fun instanceFallbackCannotOverflowOrReplaceAnInvalidEndWithUnknownDuration() {
        val start = Long.MAX_VALUE - 1000
        assertEquals(0L, resolveInstanceEndMillis(start, 0L, "P3600S"))
        for (duration in listOf(null, "broken", "P0S", "-P3600S")) {
            assertEquals(1000L, resolveInstanceEndMillis(2000L, 1000L, duration))
        }
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
