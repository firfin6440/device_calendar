package com.builttoroam.devicecalendar.common

import android.provider.CalendarContract

class Constants {
    companion object {
        const val CALENDAR_PROJECTION_ID_INDEX: Int = 0
        const val CALENDAR_PROJECTION_ACCOUNT_NAME_INDEX: Int = 1
        const val CALENDAR_PROJECTION_ACCOUNT_TYPE_INDEX: Int = 2
        const val CALENDAR_PROJECTION_DISPLAY_NAME_INDEX: Int = 3
        const val CALENDAR_PROJECTION_OWNER_ACCOUNT_INDEX: Int = 4
        const val CALENDAR_PROJECTION_ACCESS_LEVEL_INDEX: Int = 5
        const val CALENDAR_PROJECTION_COLOR_INDEX: Int = 6
        const val CALENDAR_PROJECTION_IS_PRIMARY_INDEX: Int = 7
        const val CALENDAR_PROJECTION_MAX_REMINDERS_INDEX: Int = 8
        const val CALENDAR_PROJECTION_ALLOWED_REMINDERS_INDEX: Int = 9
        const val CALENDAR_PROJECTION_ALLOWED_AVAILABILITY_INDEX: Int = 10
        const val CALENDAR_PROJECTION_ALLOWED_ATTENDEE_TYPES_INDEX: Int = 11
        const val CALENDAR_PROJECTION_CAN_MODIFY_TIME_ZONE_INDEX: Int = 12
        const val CALENDAR_PROJECTION_CAN_ORGANIZER_RESPOND_INDEX: Int = 13
        const val CALENDAR_PROJECTION_VISIBLE_INDEX: Int = 14
        const val CALENDAR_PROJECTION_SYNC_EVENTS_INDEX: Int = 15
        const val CALENDAR_PROJECTION_TIME_ZONE_INDEX: Int = 16
        const val CALENDAR_PROJECTION_LOCATION_INDEX: Int = 17
        const val CALENDAR_PROJECTION_COLOR_KEY_INDEX: Int = 18

        // API 17 or higher
        val CALENDAR_PROJECTION: Array<String> = arrayOf(
            CalendarContract.Calendars._ID,                           // 0
            CalendarContract.Calendars.ACCOUNT_NAME,                  // 1
            CalendarContract.Calendars.ACCOUNT_TYPE,                  // 2
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,         // 3
            CalendarContract.Calendars.OWNER_ACCOUNT,                 // 4
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,         // 5
            CalendarContract.Calendars.CALENDAR_COLOR,                // 6
            CalendarContract.Calendars.IS_PRIMARY,                    // 7
            CalendarContract.Calendars.MAX_REMINDERS,                 // 8
            CalendarContract.Calendars.ALLOWED_REMINDERS,             // 9
            CalendarContract.Calendars.ALLOWED_AVAILABILITY,          // 10
            CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES,        // 11
            CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE,          // 12
            CalendarContract.Calendars.CAN_ORGANIZER_RESPOND,         // 13
            CalendarContract.Calendars.VISIBLE,                       // 14
            CalendarContract.Calendars.SYNC_EVENTS,                   // 15
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,            // 16
            CalendarContract.Calendars.CALENDAR_LOCATION,             // 17
            CalendarContract.Calendars.CALENDAR_COLOR_KEY             // 18

        )

        // API 16 or lower
        val CALENDAR_PROJECTION_OLDER_API: Array<String> = arrayOf(
            CalendarContract.Calendars._ID,                           // 0
            CalendarContract.Calendars.ACCOUNT_NAME,                  // 1
            CalendarContract.Calendars.ACCOUNT_TYPE,                  // 2
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,         // 3
            CalendarContract.Calendars.OWNER_ACCOUNT,                 // 4
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,         // 5
            CalendarContract.Calendars.CALENDAR_COLOR,                // 6
            CalendarContract.Calendars.MAX_REMINDERS,                 // 7
            CalendarContract.Calendars.ALLOWED_REMINDERS,             // 8
            CalendarContract.Calendars.ALLOWED_AVAILABILITY,          // 9
            CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES,        // 10
            CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE,          // 11
            CalendarContract.Calendars.CAN_ORGANIZER_RESPOND,         // 12
            CalendarContract.Calendars.VISIBLE,                       // 13
            CalendarContract.Calendars.SYNC_EVENTS,                   // 14
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,            // 15
            CalendarContract.Calendars.CALENDAR_LOCATION,             // 16
            CalendarContract.Calendars.CALENDAR_COLOR_KEY             // 17
        )

        const val EVENT_PROJECTION_ID_INDEX: Int = 0
        const val EVENT_PROJECTION_TITLE_INDEX: Int = 1
        const val EVENT_PROJECTION_DESCRIPTION_INDEX: Int = 2
        const val EVENT_PROJECTION_BEGIN_INDEX: Int = 3
        const val EVENT_PROJECTION_END_INDEX: Int = 4
        const val EVENT_PROJECTION_DURATION_INDEX: Int = 5
        const val EVENT_PROJECTION_RECURRING_RULE_INDEX: Int = 7
        const val EVENT_PROJECTION_ALL_DAY_INDEX: Int = 8
        const val EVENT_PROJECTION_EVENT_LOCATION_INDEX: Int = 9
        const val EVENT_PROJECTION_CUSTOM_APP_URI_INDEX: Int = 10
        const val EVENT_PROJECTION_START_TIMEZONE_INDEX: Int = 11
        const val EVENT_PROJECTION_END_TIMEZONE_INDEX: Int = 12
        const val EVENT_PROJECTION_AVAILABILITY_INDEX: Int = 13
        const val EVENT_PROJECTION_STATUS_INDEX: Int = 14
        const val EVENT_PROJECTION_EVENT_COLOR_INDEX: Int = 15
        const val EVENT_PROJECTION_EVENT_COLOR_KEY_INDEX: Int = 16
        const val EVENT_PROJECTION_ORIGINAL_ID_INDEX: Int = 17
        const val EVENT_PROJECTION_ORIGINAL_INSTANCE_TIME_INDEX: Int = 18
        const val EVENT_PROJECTION_SYNC_ID_INDEX: Int = 19
        const val EVENT_PROJECTION_UID_2445_INDEX: Int = 20
        const val EVENT_PROJECTION_ORIGINAL_SYNC_ID_INDEX: Int = 21
        const val EVENT_PROJECTION_DELETED_INDEX: Int = 22
        const val EVENT_PROJECTION_SELF_ATTENDEE_STATUS_INDEX: Int = 23

        val EVENT_PROJECTION: Array<String> = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.DURATION,
                CalendarContract.Events.RDATE,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CUSTOM_APP_URI,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.EVENT_END_TIMEZONE,
                CalendarContract.Events.AVAILABILITY,
                CalendarContract.Events.STATUS,
                CalendarContract.Events.EVENT_COLOR,
                CalendarContract.Events.EVENT_COLOR_KEY,
                CalendarContract.Events.ORIGINAL_ID,
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                CalendarContract.Events._SYNC_ID,
                CalendarContract.Events.UID_2445,
                CalendarContract.Events.ORIGINAL_SYNC_ID,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.SELF_ATTENDEE_STATUS
        )

        const val MASTER_EVENT_PROJECTION_ID_INDEX: Int = 0
        const val MASTER_EVENT_PROJECTION_CALENDAR_ID_INDEX: Int = 1
        const val MASTER_EVENT_PROJECTION_TITLE_INDEX: Int = 2
        const val MASTER_EVENT_PROJECTION_DESCRIPTION_INDEX: Int = 3
        const val MASTER_EVENT_PROJECTION_START_INDEX: Int = 4
        const val MASTER_EVENT_PROJECTION_END_INDEX: Int = 5
        const val MASTER_EVENT_PROJECTION_DURATION_INDEX: Int = 6
        const val MASTER_EVENT_PROJECTION_RECURRING_RULE_INDEX: Int = 7
        const val MASTER_EVENT_PROJECTION_ALL_DAY_INDEX: Int = 8
        const val MASTER_EVENT_PROJECTION_EVENT_LOCATION_INDEX: Int = 9
        const val MASTER_EVENT_PROJECTION_CUSTOM_APP_URI_INDEX: Int = 10
        const val MASTER_EVENT_PROJECTION_START_TIMEZONE_INDEX: Int = 11
        const val MASTER_EVENT_PROJECTION_END_TIMEZONE_INDEX: Int = 12
        const val MASTER_EVENT_PROJECTION_AVAILABILITY_INDEX: Int = 13
        const val MASTER_EVENT_PROJECTION_STATUS_INDEX: Int = 14
        const val MASTER_EVENT_PROJECTION_EVENT_COLOR_INDEX: Int = 15
        const val MASTER_EVENT_PROJECTION_EVENT_COLOR_KEY_INDEX: Int = 16
        const val MASTER_EVENT_PROJECTION_SYNC_ID_INDEX: Int = 17
        const val MASTER_EVENT_PROJECTION_ORIGINAL_ID_INDEX: Int = 18
        const val MASTER_EVENT_PROJECTION_ORIGINAL_INSTANCE_TIME_INDEX: Int = 19
        const val MASTER_EVENT_PROJECTION_DIRTY_INDEX: Int = 20
        const val MASTER_EVENT_PROJECTION_UID_2445_INDEX: Int = 21
        const val MASTER_EVENT_PROJECTION_ORIGINAL_SYNC_ID_INDEX: Int = 22
        const val MASTER_EVENT_PROJECTION_DELETED_INDEX: Int = 23
        const val MASTER_EVENT_PROJECTION_SELF_ATTENDEE_STATUS_INDEX: Int = 24

        val MASTER_EVENT_PROJECTION: Array<String> = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CUSTOM_APP_URI,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.EVENT_COLOR,
            CalendarContract.Events.EVENT_COLOR_KEY,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.DIRTY,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.ORIGINAL_SYNC_ID,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.SELF_ATTENDEE_STATUS
        )

        const val EVENT_INSTANCE_DELETION_ID_INDEX: Int = 0
        const val EVENT_INSTANCE_DELETION_RRULE_INDEX: Int = 1
        const val EVENT_INSTANCE_DELETION_LAST_DATE_INDEX: Int = 2
        const val EVENT_INSTANCE_DELETION_BEGIN_INDEX: Int = 3
        const val EVENT_INSTANCE_DELETION_END_INDEX: Int = 4

        val EVENT_INSTANCE_DELETION: Array<String> = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.LAST_DATE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )

        const val ATTENDEE_ID_INDEX: Int = 0
        const val ATTENDEE_EVENT_ID_INDEX: Int = 1
        const val ATTENDEE_NAME_INDEX: Int = 2
        const val ATTENDEE_EMAIL_INDEX: Int = 3
        const val ATTENDEE_TYPE_INDEX: Int = 4
        const val ATTENDEE_RELATIONSHIP_INDEX: Int = 5
        const val ATTENDEE_STATUS_INDEX: Int = 6

        val ATTENDEE_PROJECTION: Array<String> = arrayOf(
            CalendarContract.Attendees._ID,
            CalendarContract.Attendees.EVENT_ID,
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_TYPE,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            CalendarContract.Attendees.ATTENDEE_STATUS
        )

        const val REMINDER_MINUTES_INDEX = 1
        const val REMINDER_METHOD_INDEX = 2
        val REMINDER_PROJECTION: Array<String> = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES,
            CalendarContract.Reminders.METHOD
        )

        const val AVAILABILITY_UNAVAILABLE = "UNAVAILABLE"

        const val EVENT_STATUS_NONE = "NONE"
    }
}
