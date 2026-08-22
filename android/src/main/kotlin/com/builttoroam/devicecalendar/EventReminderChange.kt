package com.builttoroam.devicecalendar

data class EventReminderValue(
    val minutes: Int,
    val method: Int
) {
    fun toMap(): Map<String, Int> = mapOf(
        "minutes" to minutes,
        "method" to method
    )
}

fun parseEventReminderValues(value: Any?): List<EventReminderValue>? {
    val rawReminders = value as? List<*> ?: return null
    val reminders = rawReminders.map { rawReminder ->
        val reminder = rawReminder as? Map<*, *> ?: return null
        val minutes = (reminder["minutes"] as? Number)?.toInt() ?: return null
        val method = (reminder["method"] as? Number)?.toInt() ?: return null
        if (minutes < 0 || method < 0) return null
        EventReminderValue(minutes = minutes, method = method)
    }
    return reminders.sortedWith(compareBy(EventReminderValue::minutes, EventReminderValue::method))
}
