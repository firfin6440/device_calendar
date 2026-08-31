package com.builttoroam.devicecalendar

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.annotation.NonNull
import com.builttoroam.devicecalendar.common.Constants
import com.builttoroam.devicecalendar.models.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.EventChannel
import org.dmfs.rfc5545.recur.Freq

// Methods
private const val REQUEST_PERMISSIONS_METHOD = "requestPermissions"
private const val HAS_PERMISSIONS_METHOD = "hasPermissions"
private const val RETRIEVE_CALENDARS_METHOD = "retrieveCalendars"
private const val RETRIEVE_EVENTS_METHOD = "retrieveEvents"
private const val RETRIEVE_EVENT_METHOD = "retrieveEvent"
private const val RETRIEVE_MASTER_EVENT_METHOD = "retrieveMasterEvent"
private const val UPDATE_ATTENDEE_STATUS_METHOD = "updateAttendeeStatus"
private const val APPLY_EVENT_CHANGES_METHOD = "applyEventChanges"
private const val DELETE_EVENT_METHOD = "deleteEvent"
private const val DELETE_EVENT_INSTANCE_METHOD = "deleteEventInstance"
private const val CREATE_OR_UPDATE_EVENT_METHOD = "createOrUpdateEvent"
private const val CREATE_CALENDAR_METHOD = "createCalendar"
private const val DELETE_CALENDAR_METHOD = "deleteCalendar"
private const val RETRIEVE_EVENT_COLORS_METHOD = "retrieveEventColors"
private const val RETRIEVE_CALENDAR_COLORS_METHOD = "retrieveCalendarColors"
private const val UPDATE_CALENDAR_COLOR = "updateCalendarColor"

// Method arguments
private const val CALENDAR_ID_ARGUMENT = "calendarId"
private const val CALENDAR_NAME_ARGUMENT = "calendarName"
private const val CALENDAR_ACCOUNT_NAME_ARGUMENT = "accountName"
private const val START_DATE_ARGUMENT = "startDate"
private const val END_DATE_ARGUMENT = "endDate"
private const val EVENT_IDS_ARGUMENT = "eventIds"
private const val EVENT_ID_ARGUMENT = "eventId"
private const val ORIGINAL_EVENT_ID_ARGUMENT = "originalEventId"
private const val ATTENDEE_EMAIL_ARGUMENT = "attendeeEmail"
private const val EXPECTED_ATTENDEE_STATUS_ARGUMENT = "expectedAttendeeStatus"
private const val NEW_ATTENDEE_STATUS_ARGUMENT = "newAttendeeStatus"
private const val EVENT_CHANGES_ARGUMENT = "eventChanges"
private const val RECURRENCE_CHANGE_TARGET_ARGUMENT = "recurrenceChangeTarget"
private const val EVENT_TITLE_ARGUMENT = "eventTitle"
private const val EVENT_LOCATION_ARGUMENT = "eventLocation"
private const val EVENT_URL_ARGUMENT = "eventURL"
private const val EVENT_DESCRIPTION_ARGUMENT = "eventDescription"
private const val EVENT_ALL_DAY_ARGUMENT = "eventAllDay"
private const val EVENT_START_DATE_ARGUMENT = "eventStartDate"
private const val EVENT_END_DATE_ARGUMENT = "eventEndDate"
private const val EVENT_START_TIMEZONE_ARGUMENT = "eventStartTimeZone"
private const val EVENT_END_TIMEZONE_ARGUMENT = "eventEndTimeZone"
private const val RECURRENCE_RULE_ARGUMENT = "recurrenceRule"
private const val FREQUENCY_ARGUMENT = "freq"
private const val COUNT_ARGUMENT = "count"
private const val UNTIL_ARGUMENT = "until"
private const val INTERVAL_ARGUMENT = "interval"
private const val BY_WEEK_DAYS_ARGUMENT = "byday"
private const val BY_MONTH_DAYS_ARGUMENT = "bymonthday"
private const val BY_YEAR_DAYS_ARGUMENT = "byyearday"
private const val BY_WEEKS_ARGUMENT = "byweekno"
private const val BY_MONTH_ARGUMENT = "bymonth"
private const val BY_SET_POSITION_ARGUMENT = "bysetpos"

private const val ATTENDEES_ARGUMENT = "attendees"
private const val EMAIL_ADDRESS_ARGUMENT = "emailAddress"
private const val NAME_ARGUMENT = "name"
private const val ROLE_ARGUMENT = "role"
private const val REMINDERS_ARGUMENT = "reminders"
private const val REMINDER_METHOD_ARGUMENT = "method"
private const val MINUTES_ARGUMENT = "minutes"
private const val FOLLOWING_INSTANCES = "followingInstances"
private const val CALENDAR_COLOR_ARGUMENT = "calendarColor"
private const val LOCAL_ACCOUNT_NAME_ARGUMENT = "localAccountName"
private const val EVENT_AVAILABILITY_ARGUMENT = "availability"
private const val ATTENDANCE_STATUS_ARGUMENT = "attendanceStatus"
private const val EVENT_STATUS_ARGUMENT = "eventStatus"
private const val EVENT_COLOR_KEY_ARGUMENT = "eventColorKey"
private const val CALENDAR_COLOR_KEY_ARGUMENT = "calendarColorKey"

class DeviceCalendarPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, EventChannel.StreamHandler {

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var calendarChangesChannel: EventChannel
    private var context: Context? = null
    private var activity: Activity? = null

    private lateinit var _calendarDelegate: CalendarDelegate
    private var calendarChangesSink: EventChannel.EventSink? = null
    private var calendarContentObserver: ContentObserver? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "plugins.builttoroam.com/device_calendar"
        )
        channel.setMethodCallHandler(this)
        calendarChangesChannel = EventChannel(
            flutterPluginBinding.binaryMessenger,
            "plugins.builttoroam.com/device_calendar/calendar_changes"
        )
        calendarChangesChannel.setStreamHandler(this)
        _calendarDelegate = CalendarDelegate(null, context!!)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        stopObservingCalendarChanges()
        calendarChangesChannel.setStreamHandler(null)
        channel.setMethodCallHandler(null)
        context = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        calendarChangesSink = events
        if (calendarContentObserver != null) return
        val applicationContext = context ?: return
        calendarContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                calendarChangesSink?.success(System.currentTimeMillis())
            }
        }
        try {
            applicationContext.contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI,
                true,
                calendarContentObserver!!
            )
        } catch (error: SecurityException) {
            calendarContentObserver = null
            events?.error(
                "calendar_permissions_missing",
                "Calendar permission is required to observe calendar changes.",
                null
            )
        }
    }

    override fun onCancel(arguments: Any?) {
        stopObservingCalendarChanges()
    }

    private fun stopObservingCalendarChanges() {
        val observer = calendarContentObserver
        if (observer != null) {
            context?.contentResolver?.unregisterContentObserver(observer)
        }
        calendarContentObserver = null
        calendarChangesSink = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        _calendarDelegate = CalendarDelegate(binding, context!!)
        binding.addRequestPermissionsResultListener(_calendarDelegate)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        _calendarDelegate = CalendarDelegate(binding, context!!)
        binding.addRequestPermissionsResultListener(_calendarDelegate)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            REQUEST_PERMISSIONS_METHOD -> {
                _calendarDelegate.requestPermissions(result)
            }
            HAS_PERMISSIONS_METHOD -> {
                _calendarDelegate.hasPermissions(result)
            }
            RETRIEVE_CALENDARS_METHOD -> {
                _calendarDelegate.retrieveCalendars(result)
            }
            RETRIEVE_EVENTS_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val startDate = call.argument<Long>(START_DATE_ARGUMENT)
                val endDate = call.argument<Long>(END_DATE_ARGUMENT)
                val eventIds = call.argument<List<String>>(EVENT_IDS_ARGUMENT) ?: listOf()
                _calendarDelegate.retrieveEvents(calendarId!!, startDate, endDate, eventIds, result)
            }
            RETRIEVE_EVENT_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val eventId = call.argument<String>(EVENT_ID_ARGUMENT)
                _calendarDelegate.retrieveEvent(calendarId!!, eventId!!, result)
            }
            RETRIEVE_MASTER_EVENT_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val originalEventId = call.argument<String>(ORIGINAL_EVENT_ID_ARGUMENT)
                _calendarDelegate.retrieveMasterEvent(calendarId!!, originalEventId!!, result)
            }
            UPDATE_ATTENDEE_STATUS_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val eventId = call.argument<String>(EVENT_ID_ARGUMENT)
                val attendeeEmail = call.argument<String>(ATTENDEE_EMAIL_ARGUMENT)
                val expectedStatus = call.argument<Int>(EXPECTED_ATTENDEE_STATUS_ARGUMENT)
                val newStatus = call.argument<Int>(NEW_ATTENDEE_STATUS_ARGUMENT)
                val recurrenceChangeTarget =
                    call.argument<Map<String, Any?>>(RECURRENCE_CHANGE_TARGET_ARGUMENT)
                _calendarDelegate.updateAttendeeStatus(
                    calendarId!!,
                    eventId!!,
                    attendeeEmail!!,
                    expectedStatus!!,
                    newStatus!!,
                    recurrenceChangeTarget,
                    result
                )
            }
            APPLY_EVENT_CHANGES_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val eventId = call.argument<String>(EVENT_ID_ARGUMENT)
                val eventChanges = call.argument<Map<String, Any?>>(EVENT_CHANGES_ARGUMENT)
                val recurrenceChangeTarget =
                    call.argument<Map<String, Any?>>(RECURRENCE_CHANGE_TARGET_ARGUMENT)
                _calendarDelegate.applyEventChanges(
                    calendarId!!,
                    eventId!!,
                    eventChanges!!,
                    recurrenceChangeTarget,
                    result
                )
            }
            CREATE_OR_UPDATE_EVENT_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val event = parseEventArgs(call, calendarId)
                _calendarDelegate.createOrUpdateEvent(calendarId!!, event, result)
            }
            DELETE_EVENT_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val eventId = call.argument<String>(EVENT_ID_ARGUMENT)

                _calendarDelegate.deleteEvent(calendarId!!, eventId!!, result)
            }
            DELETE_EVENT_INSTANCE_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                val eventId = call.argument<String>(EVENT_ID_ARGUMENT)
                val startDate = call.argument<Long>(EVENT_START_DATE_ARGUMENT)
                val endDate = call.argument<Long>(EVENT_END_DATE_ARGUMENT)
                val followingInstances = call.argument<Boolean>(FOLLOWING_INSTANCES)

                _calendarDelegate.deleteEvent(
                    calendarId!!,
                    eventId!!,
                    result,
                    startDate,
                    endDate,
                    followingInstances
                )
            }
            CREATE_CALENDAR_METHOD -> {
                val calendarName = call.argument<String>(CALENDAR_NAME_ARGUMENT)
                val calendarColor = call.argument<String>(CALENDAR_COLOR_ARGUMENT)
                val localAccountName = call.argument<String>(LOCAL_ACCOUNT_NAME_ARGUMENT)

                _calendarDelegate.createCalendar(
                    calendarName!!,
                    calendarColor,
                    localAccountName!!,
                    result
                )
            }
            DELETE_CALENDAR_METHOD -> {
                val calendarId = call.argument<String>(CALENDAR_ID_ARGUMENT)
                _calendarDelegate.deleteCalendar(calendarId!!, result)
            }
            RETRIEVE_EVENT_COLORS_METHOD -> {
                val accountName  = call.argument<String>(CALENDAR_ACCOUNT_NAME_ARGUMENT)
                if (accountName == null) {
                    result.success(intArrayOf())
                    return;
                }
                val colors = _calendarDelegate.retrieveEventColors(accountName!!, )
                result.success(colors.map { listOf(it.first, it.second) })
            }
            RETRIEVE_CALENDAR_COLORS_METHOD -> {
                val accountName  = call.argument<String>(CALENDAR_ACCOUNT_NAME_ARGUMENT)
                if (accountName == null) {
                    result.success(intArrayOf())
                    return;
                }
                val colors = _calendarDelegate.retrieveCalendarColors(accountName)
                result.success(colors.map { listOf(it.first, it.second) })
            }
            UPDATE_CALENDAR_COLOR -> {
                val calendarId  = call.argument<Number>(CALENDAR_ID_ARGUMENT)?.toLong()
                if (calendarId == null) {
                    result.success(false)
                    return
                }
                val newColorKey  = (call.argument<Number>(CALENDAR_COLOR_KEY_ARGUMENT))?.toInt()
                val newColor  = (call.argument<Number>(CALENDAR_COLOR_ARGUMENT))?.toInt()
                val success = _calendarDelegate.updateCalendarColor(calendarId, newColorKey, newColor)
                result.success(success)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun parseEventArgs(call: MethodCall, calendarId: String?): Event {
        val event = Event()
        event.eventTitle = call.argument<String>(EVENT_TITLE_ARGUMENT)
        event.calendarId = calendarId
        event.eventId = call.argument<String>(EVENT_ID_ARGUMENT)
        event.eventDescription = call.argument<String>(EVENT_DESCRIPTION_ARGUMENT)
        event.eventAllDay = call.argument<Boolean>(EVENT_ALL_DAY_ARGUMENT) ?: false
        event.eventStartDate = call.argument<Long>(EVENT_START_DATE_ARGUMENT)!!
        event.eventEndDate = call.argument<Long>(EVENT_END_DATE_ARGUMENT)!!
        event.eventStartTimeZone = call.argument<String>(EVENT_START_TIMEZONE_ARGUMENT)
        event.eventEndTimeZone = call.argument<String>(EVENT_END_TIMEZONE_ARGUMENT)
        event.eventLocation = call.argument<String>(EVENT_LOCATION_ARGUMENT)
        event.eventURL = call.argument<String>(EVENT_URL_ARGUMENT)
        event.availability = parseAvailability(call.argument<String>(EVENT_AVAILABILITY_ARGUMENT))
        event.eventStatus = parseEventStatus(call.argument<String>(EVENT_STATUS_ARGUMENT))
        event.eventColorKey = call.argument<Int>(EVENT_COLOR_KEY_ARGUMENT)

        if (call.hasArgument(RECURRENCE_RULE_ARGUMENT) && call.argument<Map<String, Any>>(
                RECURRENCE_RULE_ARGUMENT
            ) != null
        ) {
            val recurrenceRule = parseRecurrenceRuleArgs(call)
            event.recurrenceRule = recurrenceRule
        }

        if (call.hasArgument(ATTENDEES_ARGUMENT) && call.argument<List<Map<String, Any>>>(
                ATTENDEES_ARGUMENT
            ) != null
        ) {
            event.attendees = mutableListOf()
            val attendeesArgs = call.argument<List<Map<String, Any>>>(ATTENDEES_ARGUMENT)!!
            for (attendeeArgs in attendeesArgs) {
                event.attendees.add(
                    Attendee(
                        attendeeArgs[EMAIL_ADDRESS_ARGUMENT] as String,
                        attendeeArgs[NAME_ARGUMENT] as String?,
                        attendeeArgs[ROLE_ARGUMENT] as Int,
                        attendeeArgs[ATTENDANCE_STATUS_ARGUMENT] as Int?,
                        null, null
                    )
                )
            }
        }

        if (call.hasArgument(REMINDERS_ARGUMENT) && call.argument<List<Map<String, Any>>>(
                REMINDERS_ARGUMENT
            ) != null
        ) {
            event.reminders = mutableListOf()
            val remindersArgs = call.argument<List<Map<String, Any>>>(REMINDERS_ARGUMENT)!!
            for (reminderArgs in remindersArgs) {
                event.reminders.add(
                    Reminder(
                        reminderArgs[MINUTES_ARGUMENT] as Int,
                        (reminderArgs[REMINDER_METHOD_ARGUMENT] as? Int)
                            ?: android.provider.CalendarContract.Reminders.METHOD_ALERT
                    )
                )
            }
        }
        return event
    }

    private fun parseRecurrenceRuleArgs(call: MethodCall): RecurrenceRule {
        val recurrenceRuleArgs = call.argument<Map<String, Any>>(RECURRENCE_RULE_ARGUMENT)!!
        val recurrenceFrequencyString = recurrenceRuleArgs[FREQUENCY_ARGUMENT] as String
        val recurrenceFrequency = Freq.valueOf(recurrenceFrequencyString)
        val recurrenceRule = RecurrenceRule(recurrenceFrequency)

        if (recurrenceRuleArgs.containsKey(COUNT_ARGUMENT)) {
            recurrenceRule.count = recurrenceRuleArgs[COUNT_ARGUMENT] as Int?
        }

        if (recurrenceRuleArgs.containsKey(INTERVAL_ARGUMENT)) {
            recurrenceRule.interval = recurrenceRuleArgs[INTERVAL_ARGUMENT] as Int
        }

        if (recurrenceRuleArgs.containsKey(UNTIL_ARGUMENT)) {
            recurrenceRule.until = recurrenceRuleArgs[UNTIL_ARGUMENT] as String?
        }

        if (recurrenceRuleArgs.containsKey(BY_WEEK_DAYS_ARGUMENT)) {
            recurrenceRule.byday =
                recurrenceRuleArgs[BY_WEEK_DAYS_ARGUMENT].toListOf<String>()?.toMutableList()
        }

        if (recurrenceRuleArgs.containsKey(BY_MONTH_DAYS_ARGUMENT)) {
            recurrenceRule.bymonthday =
                recurrenceRuleArgs[BY_MONTH_DAYS_ARGUMENT] as MutableList<Int>?
        }

        if (recurrenceRuleArgs.containsKey(BY_YEAR_DAYS_ARGUMENT)) {
            recurrenceRule.byyearday =
                recurrenceRuleArgs[BY_YEAR_DAYS_ARGUMENT] as MutableList<Int>?
        }

        if (recurrenceRuleArgs.containsKey(BY_WEEKS_ARGUMENT)) {
            recurrenceRule.byweekno = recurrenceRuleArgs[BY_WEEKS_ARGUMENT] as MutableList<Int>?
        }

        if (recurrenceRuleArgs.containsKey(BY_MONTH_ARGUMENT)) {
            recurrenceRule.bymonth = recurrenceRuleArgs[BY_MONTH_ARGUMENT] as MutableList<Int>?
        }

        if (recurrenceRuleArgs.containsKey(BY_SET_POSITION_ARGUMENT)) {
            recurrenceRule.bysetpos =
                recurrenceRuleArgs[BY_SET_POSITION_ARGUMENT] as MutableList<Int>?
        }
        return recurrenceRule
    }

    private inline fun <reified T : Any> Any?.toListOf(): List<T>? {
        return (this as List<*>?)?.filterIsInstance<T>()?.toList()
    }

    private fun parseAvailability(value: String?): Availability? =
            if (value == null || value == Constants.AVAILABILITY_UNAVAILABLE) {
                null
            } else {
                Availability.valueOf(value)
            }

    private fun parseEventStatus(value: String?): EventStatus? =
        if (value == null || value == Constants.EVENT_STATUS_NONE) {
            null
        } else {
            EventStatus.valueOf(value)
        }
}
