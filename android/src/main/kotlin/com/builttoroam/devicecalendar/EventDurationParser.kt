package com.builttoroam.devicecalendar

import android.text.format.DateUtils

private val androidSecondsDuration = Regex("^([+-])?P(\\d+)S$")
private val rfc2445Duration = Regex(
    "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$"
)

internal fun parseDurationMillis(duration: String?): Long? {
    if (duration == null) return null

    androidSecondsDuration.matchEntire(duration)?.let { match ->
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        return sign * seconds * DateUtils.SECOND_IN_MILLIS
    }

    val match = rfc2445Duration.matchEntire(duration) ?: return null
    val sign = if (match.groupValues[1] == "-") -1 else 1
    val weeks = match.groupValues[2].toLongOrNull() ?: 0L
    val days = match.groupValues[3].toLongOrNull() ?: 0L
    val hours = match.groupValues[4].toLongOrNull() ?: 0L
    val minutes = match.groupValues[5].toLongOrNull() ?: 0L
    val seconds = match.groupValues[6].toLongOrNull() ?: 0L
    return sign * (
        weeks * 7 * DateUtils.DAY_IN_MILLIS +
            days * DateUtils.DAY_IN_MILLIS +
            hours * DateUtils.HOUR_IN_MILLIS +
            minutes * DateUtils.MINUTE_IN_MILLIS +
            seconds * DateUtils.SECOND_IN_MILLIS
        )
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
    val durationMillis = parseDurationMillis(duration)
    return if (durationMillis != null && durationMillis > 0L) {
        start + durationMillis
    } else {
        rawEnd
    }
}
