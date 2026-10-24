package com.builttoroam.devicecalendar

import android.database.sqlite.SQLiteException
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ExceptionDiagnosticPrivacyTest {
    private val secret = "Private appointment private@example.invalid 2031-04-23"
    private class Hostile : RuntimeException() {
        override val message: String get() = error("Private message getter accessed")
        override fun toString(): String = error("Private formatter accessed")
    }
    @Test fun writeFailureDoesNotReadExceptionMessageAndRemainsUncertain() {
        val reply = CalendarWriteResult()
        reply.success("possibly committed")
        reply.failed(Hostile())
        var calls = 0
        reply.deliver(object : MethodChannel.Result {
            override fun success(value: Any?) { fail("Must remain uncertain after failed cleanup") }
            override fun notImplemented() { fail("Wrong result") }
            override fun error(code: String, message: String?, details: Any?) {
                calls++
                assertEquals("500", code)
                assertEquals("Calendar write failed", message)
                assertNull(details)
            }
        })
        assertEquals(1, calls)
    }
    @Test fun atomicRollbackWrapperDoesNotRetainNativeSqlOrCause() {
        val failure = assertThrows(CalendarAtomicWriteRejected::class.java) {
            runAtomicCalendarWrite<Unit> { throw SQLiteException(secret) }
        }
        assertEquals("422", failure.code)
        assertFalse(failure.toString().contains(secret))
        assertNull(failure.cause)
    }
    @Test fun rejectionDiagnosticsDiscardPrivatePayloadAndHostileCause() {
        val details = calendarRejectionDiagnostics(true, listOf("test-rec"),
            "event.atomicBatch", Hostile()) {
            mapOf("nativeChanges" to mapOf("title" to secret),
                "comparisonValues" to secret, "context" to secret,
                "expectedMatchesAtRead" to mapOf("title" to false, secret to true),
                "expectedMismatchesAtRead" to listOf("title", secret),
                "comparisonRead" to "afterBatchFailure",
                "comparisonStorage" to mapOf("dtstart" to 1934699400000L,
                    "rrule" to "FREQ=DAILY;UNTIL=20310423", "startTimeZone" to secret))
        }!!
        assertFalse(details.toString().contains(secret))
        assertFalse(details.toString().contains("1934699400000"))
        assertFalse(details.toString().contains("20310423"))
        assertEquals("event.atomicBatch", details["stage"])
        assertEquals(mapOf("title" to false), details["expectedMatchesAtRead"])
        assertEquals(listOf("title"), details["expectedMismatchesAtRead"])
        assertEquals("afterBatchFailure", details["comparisonRead"])
        assertEquals("other", details["batchExceptionType"])
        assertFalse(details.containsKey("batchExceptionMessage"))
    }
}
