package com.builttoroam.devicecalendar

import android.content.OperationApplicationException
import android.content.pm.ApplicationInfo
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

    @Test fun renamedTestEventIsStillWatchedAndBatchCauseIsPreserved() {
        val failure = OperationApplicationException("Expected 1 rows but actual 0")
        val result = calendarRejectionDiagnostics(true,
            listOf("renamed", "TEST-REC 🥕", null), "occurrence.atomicBatch", failure) {
            mapOf("expected" to null, "actual" to 123L)
        }!!
        assertTrue(result.containsKey("expected"))
        assertEquals(123L, result["actual"])
        assertEquals("occurrence.atomicBatch", result["stage"])
        assertEquals(failure.javaClass.name, result["batchExceptionType"])
        assertEquals(failure.message, result["batchExceptionMessage"])
    }

    @Test fun diagnosticFailureIsContained() {
        val result = calendarRejectionDiagnostics(true, listOf("test-rec"), "stage") {
            error("bad diagnostic")
        }!!
        assertEquals("stage", result["stage"])
        assertEquals(IllegalStateException::class.java.name, result["diagnosticErrorType"])
    }

    // Exercise the real result builder rather than duplicating its comparisons
    // in a fake provider. No resolver/provider is registered or queried here.
    @Suppress("UNCHECKED_CAST")
    private fun response(debug: Boolean, failure: Exception? = null,
                         alreadyCurrent: Boolean = false): Map<String, Any?> {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = if (debug) ApplicationInfo.FLAG_DEBUGGABLE else 0
        val delegate = CalendarDelegate(null, app)
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
        assertEquals("Europe/London", storage["startTimeZone"])
        assertNull(storage["endTimeZone"])
        assertNull(storage["dtend"])
        assertEquals("P3600S", storage["duration"])
        assertEquals("FREQ=DAILY;COUNT=2", storage["rrule"])
    }

    @Test fun batchReReadDoesNotPretendToBeValuesAtTheFailedAssertion() {
        val failure = OperationApplicationException("Expected 1 rows but actual 0")
        val result = response(true, failure, alreadyCurrent = true)
        assertEquals("alreadyCurrent", result["outcome"])
        assertEquals(emptyList<String>(), result["conflictingFields"])
        val diagnostics = result["diagnostics"] as Map<*, *>
        assertEquals(listOf("dateRange"), diagnostics["expectedMismatchesAtRead"])
        assertEquals("afterBatchFailure", diagnostics["comparisonRead"])
        assertEquals(failure.message, diagnostics["batchExceptionMessage"])
    }

    @Test fun releaseResultIsUnchangedApartFromOmittingDiagnostics() {
        val debug = response(true).toMutableMap()
        assertNotNull(debug.remove("diagnostics"))
        assertEquals(debug, response(false))
    }
}
