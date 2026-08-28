package com.builttoroam.devicecalendar

import android.provider.CalendarContract

fun parseCalendarCapabilityValues(value: String?): List<Int>? {
    if (value == null) return null
    if (value.isBlank()) return emptyList()
    return value.split(',').map { part ->
        part.trim().toIntOrNull() ?: return null
    }.distinct()
}

fun parseCalendarAvailabilityValues(value: String?): List<String>? =
    parseCalendarCapabilityValues(value)?.mapNotNull { availability ->
        when (availability) {
            CalendarContract.Events.AVAILABILITY_BUSY -> "Busy"
            CalendarContract.Events.AVAILABILITY_FREE -> "Free"
            CalendarContract.Events.AVAILABILITY_TENTATIVE -> "Tentative"
            else -> null
        }
    }
