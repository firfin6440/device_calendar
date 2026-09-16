package com.builttoroam.devicecalendar.models

/** Freeze a caller-owned write before it waits for permission or the write lane.
 * Attendee/Reminder contain only immutable values; RRULE and its lists do not. */
internal fun Event.snapshotForWrite(): Event = Event().also { copy ->
    copy.selfAttendeeStatus = selfAttendeeStatus
    copy.eventTitle = eventTitle
    copy.eventId = eventId
    copy.syncId = syncId
    copy.uid2445 = uid2445
    copy.originalSyncId = originalSyncId
    copy.eventIsDirty = eventIsDirty
    copy.eventIsDeleted = eventIsDeleted
    copy.eventMutators = eventMutators
    copy.calendarId = calendarId
    copy.eventIsDetached = eventIsDetached
    copy.eventOriginalStartDate = eventOriginalStartDate
    copy.originalEventId = originalEventId
    copy.eventDescription = eventDescription
    copy.eventStartDate = eventStartDate
    copy.eventEndDate = eventEndDate
    copy.eventStartTimeZone = eventStartTimeZone
    copy.eventEndTimeZone = eventEndTimeZone
    copy.eventAllDay = eventAllDay
    copy.eventLocation = eventLocation
    copy.eventURL = eventURL
    copy.attendees = attendees.toMutableList()
    copy.organizer = organizer
    copy.reminders = reminders.toMutableList()
    copy.availability = availability
    copy.eventStatus = eventStatus
    copy.eventColor = eventColor
    copy.eventColorKey = eventColorKey
    copy.recurrenceRule = recurrenceRule?.let { rule ->
        RecurrenceRule(rule.freq).also {
            it.count = rule.count
            it.interval = rule.interval
            it.until = rule.until
            it.sourceRruleString = rule.sourceRruleString
            it.wkst = rule.wkst
            it.byday = rule.byday?.toMutableList()
            it.bymonthday = rule.bymonthday?.toMutableList()
            it.byyearday = rule.byyearday?.toMutableList()
            it.byweekno = rule.byweekno?.toMutableList()
            it.bymonth = rule.bymonth?.toMutableList()
            it.bysetpos = rule.bysetpos?.toMutableList()
        }
    }
}
