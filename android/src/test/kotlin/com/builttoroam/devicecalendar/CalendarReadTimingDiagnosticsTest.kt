package com.builttoroam.devicecalendar

import java.io.OutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CalendarReadTimingDiagnosticsTest {
    private val previousStream = ShadowLog.stream
    @After fun restore() { ShadowLog.stream = previousStream }

    @Test fun disabledTimingPreservesResultAndDoesNotLog() {
        ShadowLog.clear()
        val result = Any()
        var calls = 0
        assertSame(result, calendarReadPhase(CalendarReadPhase.instances, false) { calls++; result })
        assertEquals(1, calls)
        assertTrue(ShadowLog.getLogsForTag("KeepCalDiagnostics").isEmpty())
    }

    @Test fun phaseFailureKeepsOriginalErrorWithoutInspectingIt() {
        ShadowLog.clear()
        val original = object : IllegalStateException("private@example.invalid") {
            override fun toString(): String = throw AssertionError("Never format provider failures")
        }
        val caught = try {
            calendarReadPhase(CalendarReadPhase.reminders, true) { throw original }
        } catch (failure: Throwable) { failure }
        assertSame(original, caught)
        val logs = ShadowLog.getLogsForTag("KeepCalDiagnostics")
        assertEquals(1, logs.size)
        assertTrue(logs.single().msg.startsWith("rangeReadPhase {stage=reminders, elapsedUs="))
        assertFalse(logs.single().msg.contains("private@example.invalid"))
    }

    @Test fun brokenConsoleCannotReplaceSuccessOrOriginalProviderFailure() {
        var attempts = 0
        ShadowLog.stream = PrintStream(object : OutputStream() {
            override fun write(value: Int) {
                attempts++
                throw IllegalStateException("broken diagnostic sink")
            }
        })
        val result = Any()
        assertSame(result, calendarReadPhase(CalendarReadPhase.metadata, true) { result })
        val original = IllegalArgumentException("private provider data")
        val caught = try {
            calendarReadPhase(CalendarReadPhase.attendees, true) { throw original }
        } catch (failure: Throwable) { failure }
        assertSame(original, caught)
        assertTrue("Fault must reach the real native console sink", attempts >= 2)
    }
}
