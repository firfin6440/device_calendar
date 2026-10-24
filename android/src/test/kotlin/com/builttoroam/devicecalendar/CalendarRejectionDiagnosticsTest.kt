package com.builttoroam.devicecalendar

import android.content.OperationApplicationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CalendarRejectionDiagnosticsTest {
    @Test fun disabledAndUnrelatedDiagnosticsNeverEvaluateDetails() {
        assertNull(calendarRejectionDiagnostics(false, listOf("test-rec"), "stage") {
            error("disabled payload evaluated")
        })
        assertNull(calendarRejectionDiagnostics(true, listOf("Other meeting", null), "stage") {
            error("unrelated payload evaluated")
        })
    }

    @Test fun renamedTestEventIsStillWatchedWithoutRawBatchCause() {
        val failure = OperationApplicationException("Expected 1 rows but actual 0")
        val result = calendarRejectionDiagnostics(true,
            listOf("renamed", "TEST-REC 🥕", null), "occurrence.atomicBatch", failure) {
            mapOf("expected" to null, "actual" to 123L)
        }!!
        assertFalse(result.containsKey("expected"))
        assertFalse(result.containsKey("actual"))
        assertEquals("occurrence.atomicBatch", result["stage"])
        assertEquals("other", result["batchExceptionType"])
        assertFalse(result.containsKey("batchExceptionMessage"))
    }

    @Test fun diagnosticFailureIsContained() {
        val result = calendarRejectionDiagnostics(true, listOf("test-rec"), "stage") {
            error("bad diagnostic")
        }!!
        assertEquals("unknown", result["stage"])
        assertEquals("state", result["diagnosticErrorType"])
    }

    // Exercise the real result builder rather than duplicating its comparisons
    // in a fake provider. No resolver/provider is registered or queried here.
    @Suppress("UNCHECKED_CAST")
    private fun response(debug: Boolean, failure: Exception? = null,
                         alreadyCurrent: Boolean = false): Map<String, Any?> {
        val app = RuntimeEnvironment.getApplication()
        val delegate = CalendarDelegate(null, app).also {
            it.debugLoggingEnabled = debug
        }
        val stored = CalendarDelegate::class.java.declaredClasses.single {
            it.simpleName == "StoredEventChangeValues"
        }.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            false, null, null, "test-rec", null, 1000L, null, "P3600S",
            "Europe/London", null, false, "FREQ=DAILY;COUNT=2",
            emptyList<EventReminderValue>(), emptyList<EventAttendeeValue>(),
            emptyList<EventResourceValue>()
        )
        val expected = EventDateRangeValue(1000L, "UTC", 3601000L, "UTC", false)
        val requested = if (alreadyCurrent)
            EventDateRangeValue(1000L, "Europe/London", 3601000L, "Europe/London", false)
        else expected.copy(startDate = 2000L, endDate = 3602000L)
        val method = CalendarDelegate::class.java.declaredMethods.single {
            it.name == "eventChangeResultForCurrent"
        }.apply { isAccessible = true }
        return method.invoke(delegate,
            if (failure == null) "event.precondition" else "event.atomicBatch",
            stored, null, null, null, null, null,
            mapOf("expected" to expected.toMap(), "requested" to requested.toMap()), requested,
            null, null, null, null, null, null, null, null, null, null,
            failure, emptyMap<String, Any?>()
        ) as Map<String, Any?>
    }

    @Test fun realBuilderIdentifiesTimezoneMismatchAndPreservesStorageNulls() {
        val result = response(true)
        assertEquals("conflict", result["outcome"])
        val diagnostics = result["diagnostics"] as Map<*, *>
        assertEquals(listOf("dateRange"), diagnostics["expectedMismatchesAtRead"])
        assertEquals("preflight", diagnostics["comparisonRead"])
        val storage = diagnostics["comparisonStorage"] as Map<*, *>
        assertTrue((storage["startTimeZone"] as String).startsWith("[identifier:"))
        assertNull(storage["endTimeZone"])
        assertNull(storage["dtend"])
        assertTrue((storage["duration"] as String).startsWith("[identifier:"))
        assertTrue((storage["rrule"] as String).startsWith("[recurrence:"))
    }

    @Test fun batchReReadDoesNotPretendToBeValuesAtTheFailedAssertion() {
        val failure = OperationApplicationException("Expected 1 rows but actual 0")
        val result = response(true, failure, alreadyCurrent = true)
        assertEquals("alreadyCurrent", result["outcome"])
        assertEquals(emptyList<String>(), result["conflictingFields"])
        val diagnostics = result["diagnostics"] as Map<*, *>
        assertEquals(listOf("dateRange"), diagnostics["expectedMismatchesAtRead"])
        assertEquals("afterBatchFailure", diagnostics["comparisonRead"])
        assertFalse(diagnostics.containsKey("batchExceptionMessage"))
    }

    @Test fun releaseResultIsUnchangedApartFromOmittingDiagnostics() {
        val debug = response(true).toMutableMap()
        assertNotNull(debug.remove("diagnostics"))
        assertEquals(debug, response(false))
    }
}
