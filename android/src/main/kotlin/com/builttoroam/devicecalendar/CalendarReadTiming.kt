package com.builttoroam.devicecalendar

import android.os.Trace
import com.builttoroam.devicecalendar.diagnostics.*

/** Fixed phase labels only: never include event IDs, dates or calendar content.
 * Trace sections are native system-trace slices; optional logs report durations
 * in microseconds. Neither changes the method-channel response contract. */
internal enum class CalendarReadPhase { metadata, instances, attendees, reminders, serialization }

internal inline fun <T> calendarReadPhase(
    phase: CalendarReadPhase, logging: Boolean, block: () -> T
): T {
    val start = if (logging) System.nanoTime() else 0L
    Trace.beginSection("KeepCal.provider.${phase.name}")
    try {
        return block()
    } finally {
        Trace.endSection()
        NativeDiagnostics.record(ProviderDiagnostic.rangeReadPhase, enabled = logging) {
            mapOf(DiagnosticField.stage to state(phase),
                DiagnosticField.elapsedUs to count((System.nanoTime() - start) / 1000))
        }
    }
}
