package com.builttoroam.devicecalendar

import android.text.format.DateUtils

private val androidSecondsDuration = Regex("^([+-])?P(\\d+)S$")
private val rfc2445Duration = Regex(
    "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$"
)

internal fun parseDurationMillis(duration: String?): Long? {
    if (duration == null) return null
    return try {
        androidSecondsDuration.matchEntire(duration)?.let { match ->
            val seconds = match.groupValues[2].toLongOrNull() ?: return null
            val millis = Math.multiplyExact(seconds, DateUtils.SECOND_IN_MILLIS)
            return if (match.groupValues[1] == "-") -millis else millis
        }
        val match = rfc2445Duration.matchEntire(duration) ?: return null
        val components = match.groupValues.drop(2)
        if (components.all { it.isEmpty() }) return null
        val units = listOf(7 * DateUtils.DAY_IN_MILLIS, DateUtils.DAY_IN_MILLIS,
            DateUtils.HOUR_IN_MILLIS, DateUtils.MINUTE_IN_MILLIS, DateUtils.SECOND_IN_MILLIS)
        var millis = 0L
        components.zip(units).forEach { (component, unit) ->
            val amount = if (component.isEmpty()) 0L else component.toLongOrNull() ?: return null
            millis = Math.addExact(millis, Math.multiplyExact(amount, unit))
        }
        if (match.groupValues[1] == "-") -millis else millis
    } catch (_: ArithmeticException) {
        null
    }
}

/** Events storage compatibility: zero DTEND is the same missing-end sentinel
 * as SQL NULL. A nonzero explicit end remains authoritative (even if invalid).
 * Never invent a date without a usable duration. This is read normalization,
 * NOT a raw-row mutation: CAS guards must retain the exact observed DTEND.
 * Recurring writes use NULL + DURATION, never this zero sentinel. */
internal fun resolveStoredEventEndMillis(start: Long, rawEnd: Long?, duration: String?): Long? {
    if (rawEnd != null && rawEnd != 0L) return rawEnd
    val millis = parseDurationMillis(duration)?.takeIf { it >= 0L } ?: return null
    return try { Math.addExact(start, millis) } catch (_: ArithmeticException) { null }
}

/**
 * Instances can briefly expose BEGIN == END while its generated cache catches
 * up with a recurring master stored as DTSTART + DURATION. Prefer a positive
 * provider duration in that transient state, but preserve a healthy END.
 */
internal fun resolveInstanceEndMillis(
    start: Long,
    rawEnd: Long,
    duration: String?
): Long {
    if (rawEnd > start) return rawEnd
    return resolveStoredEventEndMillis(start, null, duration)?.takeIf { it > start } ?: rawEnd
}
