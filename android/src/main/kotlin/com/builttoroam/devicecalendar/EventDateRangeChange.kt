package com.builttoroam.devicecalendar

internal data class EventDateRangeValue(
    val startDate: Long,
    val startTimeZone: String,
    val endDate: Long,
    val endTimeZone: String,
    val allDay: Boolean
) {
    val isChronological: Boolean
        get() = endDate >= startDate

    fun toMap(): Map<String, Any> = mapOf(
        "startDate" to startDate,
        "startTimeZone" to startTimeZone,
        "endDate" to endDate,
        "endTimeZone" to endTimeZone,
        "allDay" to allDay
    )
}

internal fun parseEventDateRangeValue(value: Any?): EventDateRangeValue? {
    val map = value as? Map<*, *> ?: return null
    val startDate = (map["startDate"] as? Number)?.toLong() ?: return null
    val startTimeZone = map["startTimeZone"] as? String ?: return null
    val endDate = (map["endDate"] as? Number)?.toLong() ?: return null
    val endTimeZone = map["endTimeZone"] as? String ?: return null
    val allDay = map["allDay"] as? Boolean ?: return null
    if (startTimeZone.isBlank() || endTimeZone.isBlank()) return null

    return EventDateRangeValue(
        startDate = startDate,
        startTimeZone = startTimeZone,
        endDate = endDate,
        endTimeZone = endTimeZone,
        allDay = allDay
    ).takeIf { it.isChronological }
}

internal fun durationForDateRange(value: EventDateRangeValue): String? {
    val durationMillis = value.endDate - value.startDate
    if (durationMillis < 0L || durationMillis % 1000L != 0L) return null
    return "P${durationMillis / 1000L}S"
}
