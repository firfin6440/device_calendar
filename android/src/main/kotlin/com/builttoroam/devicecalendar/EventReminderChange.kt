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

internal data class RecurrenceExceptionReminderPlan(
    val deleteInherited: Boolean,
    val remindersToInsert: List<EventReminderValue>
)

/**
 * CalendarProvider clones reminder rows from the recurring master when it
 * creates a single-occurrence exception. Leave those rows untouched unless
 * reminders were explicitly edited; an edit replaces the cloned set.
 */
internal fun recurrenceExceptionReminderPlan(
    requestedReminders: List<EventReminderValue>?
): RecurrenceExceptionReminderPlan = if (requestedReminders == null) {
    RecurrenceExceptionReminderPlan(
        deleteInherited = false,
        remindersToInsert = emptyList()
    )
} else {
    RecurrenceExceptionReminderPlan(
        deleteInherited = true,
        remindersToInsert = requestedReminders
    )
}
