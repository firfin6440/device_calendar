package com.builttoroam.devicecalendar

import android.os.Trace
import android.util.Log

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
        if (logging) Log.d("KeepCalRangeRead", "${phase.name}Us=${(System.nanoTime() - start) / 1000}")
    }
}
