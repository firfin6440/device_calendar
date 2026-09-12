package com.builttoroam.devicecalendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.CALLER_IS_SYNCADAPTER
import android.provider.CalendarContract.Events
import android.text.format.DateUtils
import android.util.Log
import com.builttoroam.devicecalendar.common.ErrorMessages
import com.builttoroam.devicecalendar.models.*
import com.builttoroam.devicecalendar.models.Calendar
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.*
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.DateTime.UTC
import org.dmfs.rfc5545.Weekday
import org.dmfs.rfc5545.recur.RecurrenceRule.WeekdayNum
import java.util.*
import kotlin.math.absoluteValue
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import com.builttoroam.devicecalendar.common.Constants.Companion as Cst
import com.builttoroam.devicecalendar.common.ErrorCodes.Companion as EC
import com.builttoroam.devicecalendar.common.ErrorMessages.Companion as EM
import org.dmfs.rfc5545.recur.Freq as RruleFreq
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException
import org.dmfs.rfc5545.recur.RecurrenceRule as Rrule
import android.provider.CalendarContract.Colors
import androidx.collection.SparseArrayCompat

private const val RETRIEVE_CALENDARS_REQUEST_CODE = 0
private const val RETRIEVE_EVENTS_REQUEST_CODE = RETRIEVE_CALENDARS_REQUEST_CODE + 1
private const val RETRIEVE_MASTER_EVENT_REQUEST_CODE = RETRIEVE_EVENTS_REQUEST_CODE + 1
private const val UPDATE_ATTENDEE_STATUS_REQUEST_CODE = RETRIEVE_MASTER_EVENT_REQUEST_CODE + 1
private const val APPLY_EVENT_CHANGES_REQUEST_CODE = UPDATE_ATTENDEE_STATUS_REQUEST_CODE + 1
private const val RETRIEVE_CALENDAR_REQUEST_CODE = APPLY_EVENT_CHANGES_REQUEST_CODE + 1
private const val CREATE_OR_UPDATE_EVENT_REQUEST_CODE = RETRIEVE_CALENDAR_REQUEST_CODE + 1
private const val DELETE_EVENT_REQUEST_CODE = CREATE_OR_UPDATE_EVENT_REQUEST_CODE + 1
private const val REQUEST_PERMISSIONS_REQUEST_CODE = DELETE_EVENT_REQUEST_CODE + 1
private const val DELETE_CALENDAR_REQUEST_CODE = REQUEST_PERMISSIONS_REQUEST_CODE + 1
private const val TEST_REC_TRACE_TAG = "TEST_REC_STATE"

class CalendarDelegate(binding: ActivityPluginBinding?, context: Context) :
    PluginRegistry.RequestPermissionsResultListener {

    private val _cachedParametersMap: MutableMap<Int, CalendarMethodsParametersCacheModel> =
        mutableMapOf()
    private var _binding: ActivityPluginBinding? = binding
    private var _context: Context? = context
    private var _gson: Gson? = null

    private val uiThreadHandler = Handler(Looper.getMainLooper())

    init {
        val gsonBuilder = GsonBuilder()
        gsonBuilder.registerTypeAdapter(Availability::class.java, AvailabilitySerializer())
        gsonBuilder.registerTypeAdapter(EventStatus::class.java, EventStatusSerializer())
        _gson = gsonBuilder.create()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        val permissionGranted =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (!_cachedParametersMap.containsKey(requestCode)) {
            // this plugin doesn't handle this request code
            return false
        }

        val cachedValues: CalendarMethodsParametersCacheModel = _cachedParametersMap[requestCode]
            ?: // unlikely scenario where another plugin is potentially using the same request code but it's not one we are tracking so return to
            // indicate we're not handling the request
            return false

        try {
            if (!permissionGranted) {
                finishWithError(
                    EC.NOT_AUTHORIZED,
                    EM.NOT_AUTHORIZED_MESSAGE,
                    cachedValues.pendingChannelResult
                )
                return false
            }

            when (cachedValues.calendarDelegateMethodCode) {
                RETRIEVE_CALENDARS_REQUEST_CODE -> {
                    retrieveCalendars(cachedValues.pendingChannelResult)
                }
                RETRIEVE_EVENTS_REQUEST_CODE -> {
                    retrieveEvents(
                        cachedValues.calendarId,
                        cachedValues.calendarEventsStartDate,
                        cachedValues.calendarEventsEndDate,
                        cachedValues.calendarEventsIds,
                        cachedValues.pendingChannelResult
                    )
                }
                RETRIEVE_MASTER_EVENT_REQUEST_CODE -> {
                    retrieveMasterEvent(
                        cachedValues.calendarId,
                        cachedValues.eventId,
                        cachedValues.pendingChannelResult
                    )
                }
                UPDATE_ATTENDEE_STATUS_REQUEST_CODE -> {
                    updateAttendeeStatus(
                        cachedValues.calendarId,
                        cachedValues.eventId,
                        cachedValues.attendeeEmail,
                        cachedValues.expectedAttendeeStatus!!,
                        cachedValues.newAttendeeStatus!!,
                        cachedValues.recurrenceChangeTarget,
                        cachedValues.pendingChannelResult
                    )
                }
                APPLY_EVENT_CHANGES_REQUEST_CODE -> {
                    applyEventChanges(
                        cachedValues.calendarId,
                        cachedValues.eventId,
                        cachedValues.eventChanges,
                        cachedValues.recurrenceChangeTarget,
                        cachedValues.pendingChannelResult
                    )
                }
                RETRIEVE_CALENDAR_REQUEST_CODE -> {
                    retrieveCalendar(cachedValues.calendarId, cachedValues.pendingChannelResult)
                }
                CREATE_OR_UPDATE_EVENT_REQUEST_CODE -> {
                    createOrUpdateEvent(
                        cachedValues.calendarId,
                        cachedValues.event,
                        cachedValues.pendingChannelResult
                    )
                }
                DELETE_EVENT_REQUEST_CODE -> {
                    deleteEvent(
                        cachedValues.calendarId,
                        cachedValues.eventId,
                        cachedValues.pendingChannelResult
                    )
                }
                REQUEST_PERMISSIONS_REQUEST_CODE -> {
                    finishWithSuccess(permissionGranted, cachedValues.pendingChannelResult)
                }
                DELETE_CALENDAR_REQUEST_CODE -> {
                    deleteCalendar(cachedValues.calendarId, cachedValues.pendingChannelResult)
                }
            }

            return true
        } finally {
            _cachedParametersMap.remove(cachedValues.calendarDelegateMethodCode)
        }
    }

    fun requestPermissions(pendingChannelResult: MethodChannel.Result) {
        if (arePermissionsGranted()) {
            finishWithSuccess(true, pendingChannelResult)
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
            requestPermissions(parameters)
        }
    }

    fun hasPermissions(pendingChannelResult: MethodChannel.Result) {
        finishWithSuccess(arePermissionsGranted(), pendingChannelResult)
    }

    @SuppressLint("MissingPermission")
    fun retrieveCalendars(pendingChannelResult: MethodChannel.Result) {
        if (arePermissionsGranted()) {
            val contentResolver: ContentResolver? = _context?.contentResolver
            val uri: Uri = CalendarContract.Calendars.CONTENT_URI
            val cursor: Cursor? = if (atLeastAPI(17)) {
                contentResolver?.query(uri, Cst.CALENDAR_PROJECTION, null, null, null)
            } else {
                contentResolver?.query(uri, Cst.CALENDAR_PROJECTION_OLDER_API, null, null, null)
            }
            val calendars: MutableList<Calendar> = mutableListOf()
            try {
                while (cursor?.moveToNext() == true) {
                    val calendar = parseCalendarRow(cursor) ?: continue
                    calendars.add(calendar)
                }

                finishWithSuccess(_gson?.toJson(calendars), pendingChannelResult)
            } catch (e: Exception) {
                finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
            } finally {
                cursor?.close()
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_CALENDARS_REQUEST_CODE
            )
            requestPermissions(parameters)
        }
    }

    private fun retrieveCalendar(
        calendarId: String,
        pendingChannelResult: MethodChannel.Result,
        isInternalCall: Boolean = false
    ): Calendar? {
        if (isInternalCall || arePermissionsGranted()) {
            val calendarIdNumber = calendarId.toLongOrNull()
            if (calendarIdNumber == null) {
                if (!isInternalCall) {
                    finishWithError(
                        EC.INVALID_ARGUMENT,
                        EM.CALENDAR_ID_INVALID_ARGUMENT_NOT_A_NUMBER_MESSAGE,
                        pendingChannelResult
                    )
                }
                return null
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val uri: Uri = CalendarContract.Calendars.CONTENT_URI

            val cursor: Cursor? = if (atLeastAPI(17)) {
                contentResolver?.query(
                    ContentUris.withAppendedId(uri, calendarIdNumber),
                    Cst.CALENDAR_PROJECTION,
                    null,
                    null,
                    null
                )
            } else {
                contentResolver?.query(
                    ContentUris.withAppendedId(uri, calendarIdNumber),
                    Cst.CALENDAR_PROJECTION_OLDER_API,
                    null,
                    null,
                    null
                )
            }

            try {
                if (cursor?.moveToFirst() == true) {
                    val calendar = parseCalendarRow(cursor)
                    if (isInternalCall) {
                        return calendar
                    } else {
                        finishWithSuccess(_gson?.toJson(calendar), pendingChannelResult)
                    }
                } else {
                    if (!isInternalCall) {
                        finishWithError(
                            EC.NOT_FOUND,
                            "The calendar with the ID $calendarId could not be found",
                            pendingChannelResult
                        )
                    }
                }
            } catch (e: Exception) {
                finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
            } finally {
                cursor?.close()
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_CALENDAR_REQUEST_CODE,
                calendarId
            )
            requestPermissions(parameters)
        }

        return null
    }

    fun deleteCalendar(
        calendarId: String,
        pendingChannelResult: MethodChannel.Result,
        isInternalCall: Boolean = false
    ): Calendar? {
        if (isInternalCall || arePermissionsGranted()) {
            val calendarIdNumber = calendarId.toLongOrNull()
            if (calendarIdNumber == null) {
                if (!isInternalCall) {
                    finishWithError(
                        EC.INVALID_ARGUMENT,
                        EM.CALENDAR_ID_INVALID_ARGUMENT_NOT_A_NUMBER_MESSAGE,
                        pendingChannelResult
                    )
                }
                return null
            }

            val contentResolver: ContentResolver? = _context?.contentResolver

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar != null) {
                val calenderUriWithId = ContentUris.withAppendedId(
                    CalendarContract.Calendars.CONTENT_URI,
                    calendarIdNumber
                )
                val deleteSucceeded = contentResolver?.delete(calenderUriWithId, null, null) ?: 0
                finishWithSuccess(deleteSucceeded > 0, pendingChannelResult)
            } else {
                if (!isInternalCall) {
                    finishWithError(
                        EC.NOT_FOUND,
                        "The calendar with the ID $calendarId could not be found",
                        pendingChannelResult
                    )
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult = pendingChannelResult,
                calendarDelegateMethodCode = DELETE_CALENDAR_REQUEST_CODE,
                calendarId = calendarId
            )
            requestPermissions(parameters)
        }

        return null
    }

    fun createCalendar(
        calendarName: String,
        calendarColor: String?,
        localAccountName: String,
        pendingChannelResult: MethodChannel.Result
    ) {
        val contentResolver: ContentResolver? = _context?.contentResolver

        var uri = CalendarContract.Calendars.CONTENT_URI
        uri = uri.buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, localAccountName)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            .build()
        val values = ContentValues()
        values.put(CalendarContract.Calendars.NAME, calendarName)
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, calendarName)
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, localAccountName)
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        values.put(
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CAL_ACCESS_OWNER
        )
        values.put(
            CalendarContract.Calendars.CALENDAR_COLOR, Color.parseColor(
                (calendarColor
                    ?: "0xFFFF0000").replace("0x", "#")
            )
        ) // Red colour as a default
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, localAccountName)
        values.put(
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            java.util.Calendar.getInstance().timeZone.id
        )

        val result = contentResolver?.insert(uri, values)
        // Get the calendar ID that is the last element in the Uri
        val calendarId = java.lang.Long.parseLong(result?.lastPathSegment!!)

        finishWithSuccess(calendarId.toString(), pendingChannelResult)
    }

    fun retrieveEvents(
        calendarId: String,
        startDate: Long?,
        endDate: Long?,
        eventIds: List<String>,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (startDate == null && endDate == null && eventIds.isEmpty()) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                ErrorMessages.RETRIEVE_EVENTS_ARGUMENTS_NOT_VALID_MESSAGE,
                pendingChannelResult
            )
            return
        }

        if (arePermissionsGranted()) {
            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "Couldn't retrieve the Calendar with ID $calendarId",
                    pendingChannelResult
                )
                return
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val eventsUriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(eventsUriBuilder, startDate ?: Date(0).time)
            ContentUris.appendId(eventsUriBuilder, endDate ?: Date(Long.MAX_VALUE).time)

            val eventsUri = eventsUriBuilder.build()
            val eventsCalendarQuery = "(${Events.CALENDAR_ID} = $calendarId)"
            val eventsNotDeletedQuery = "(${Events.DELETED} != 1)"
            val eventsIdsQuery =
                "(${CalendarContract.Instances.EVENT_ID} IN (${eventIds.joinToString()}))"

            var eventsSelectionQuery = "$eventsCalendarQuery AND $eventsNotDeletedQuery"
            if (eventIds.isNotEmpty()) {
                eventsSelectionQuery += " AND ($eventsIdsQuery)"
            }
            val eventsSortOrder = Events.DTSTART + " DESC"

            val eventsCursor = contentResolver?.query(
                eventsUri,
                Cst.EVENT_PROJECTION,
                eventsSelectionQuery,
                null,
                eventsSortOrder
            )

            val events: MutableList<Event> = mutableListOf()

            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                uiThreadHandler.post {
                    finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                }
            }

            GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                while (eventsCursor?.moveToNext() == true) {
                    val event = parseEvent(calendarId, eventsCursor) ?: continue
                    events.add(event)
                }
                for (event in events) {
                    val attendees = attendeesWithAuthoritativeSelfStatus(
                        retrieveAttendees(calendar, event.eventId!!, contentResolver),
                        calendar.ownerAccount,
                        event.selfAttendeeStatus
                    )
                    event.organizer =
                        attendees.firstOrNull { it.isOrganizer != null && it.isOrganizer }
                    event.attendees = attendees
                    event.reminders = retrieveReminders(event.eventId!!, contentResolver)
                }
            }.invokeOnCompletion { cause ->
                eventsCursor?.close()
                if (cause == null) {
                    events.asSequence()
                        .filter { isTestRecTitle(it.eventTitle) }
                        .mapNotNull { event ->
                            if (event.eventIsDetached) event.originalEventId else event.eventId
                        }
                        .distinct()
                        .forEach { masterId ->
                            traceTestRecProviderState(
                                "RETRIEVE range=${startDate ?: "none"}..${endDate ?: "none"}",
                                calendarId,
                                masterId
                            )
                        }
                    uiThreadHandler.post {
                        finishWithSuccess(_gson?.toJson(events), pendingChannelResult)
                    }
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_EVENTS_REQUEST_CODE,
                calendarId,
                startDate,
                endDate
            )
            requestPermissions(parameters)
        }

        return
    }

    fun retrieveMasterEvent(
        calendarId: String,
        originalEventId: String,
        pendingChannelResult: MethodChannel.Result
    ) = retrieveEvent(calendarId, originalEventId, pendingChannelResult)

    fun retrieveEvent(
        calendarId: String,
        eventId: String,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (!arePermissionsGranted()) {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_MASTER_EVENT_REQUEST_CODE,
                calendarId,
                eventId = eventId
            )
            requestPermissions(parameters)
            return
        }

        val eventIdNumber = eventId.toLongOrNull()
        if (eventIdNumber == null) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "The event ID must be a number",
                pendingChannelResult
            )
            return
        }

        val contentResolver = _context?.contentResolver
        val eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
        val cursor = contentResolver?.query(
            eventUri,
            Cst.MASTER_EVENT_PROJECTION,
            null,
            null,
            null
        )

        try {
            if (cursor?.moveToFirst() != true) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The event with the ID $eventId could not be found",
                    pendingChannelResult
                )
                return
            }

            val event = parseMasterEvent(cursor)
            if (event == null || event.calendarId != calendarId) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The event with the ID $eventId could not be found in calendar $calendarId",
                    pendingChannelResult
                )
                return
            }

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "Couldn't retrieve the Calendar with ID $calendarId",
                    pendingChannelResult
                )
                return
            }

            event.attendees = attendeesWithAuthoritativeSelfStatus(
                retrieveAttendees(calendar, eventId, contentResolver),
                calendar.ownerAccount,
                event.selfAttendeeStatus
            )
            event.organizer = event.attendees.firstOrNull {
                it.isOrganizer != null && it.isOrganizer
            }
            event.reminders = retrieveReminders(eventId, contentResolver)
            finishWithSuccess(_gson?.toJson(event), pendingChannelResult)
        } catch (e: Exception) {
            finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
        } finally {
            cursor?.close()
        }
    }

    fun updateAttendeeStatus(
        calendarId: String,
        eventId: String,
        attendeeEmail: String,
        expectedStatus: Int,
        newStatus: Int,
        recurrenceChangeTarget: Map<String, Any?>?,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (!arePermissionsGranted()) {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                UPDATE_ATTENDEE_STATUS_REQUEST_CODE,
                calendarId,
                eventId = eventId,
                attendeeEmail = attendeeEmail,
                expectedAttendeeStatus = expectedStatus,
                newAttendeeStatus = newStatus,
                recurrenceChangeTarget = recurrenceChangeTarget
            )
            requestPermissions(parameters)
            return
        }

        val eventIdNumber = eventId.toLongOrNull()
        val calendarIdNumber = calendarId.toLongOrNull()
        val validStatuses = setOf(
            CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
            CalendarContract.Attendees.ATTENDEE_STATUS_INVITED,
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
        )
        if (eventIdNumber == null || calendarIdNumber == null ||
            expectedStatus !in validStatuses || newStatus !in validStatuses
        ) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid calendar, event, or attendee status",
                pendingChannelResult
            )
            return
        }

        if (recurrenceChangeTarget != null) {
            val scope = recurrenceChangeTarget["scope"] as? String
            val originalOccurrenceStart =
                (recurrenceChangeTarget["originalOccurrenceStart"] as? Number)?.toLong()
            val originalEventId = recurrenceChangeTarget["originalEventId"] as? String
            val selectedOccurrenceWasDetached =
                recurrenceChangeTarget["selectedOccurrenceWasDetached"] as? Boolean
            if (scope == null || originalOccurrenceStart == null ||
                selectedOccurrenceWasDetached == null) {
                finishWithError(
                    EC.INVALID_ARGUMENT,
                    "Invalid recurrence change target",
                    pendingChannelResult
                )
                return
            }
            val masterEventId = if (selectedOccurrenceWasDetached) originalEventId else eventId
            if (masterEventId.isNullOrEmpty()) {
                finishWithError(
                    EC.INVALID_ARGUMENT,
                    "A recurring master event ID is required",
                    pendingChannelResult
                )
                return
            }
            val attendeeChange = AttendeeStatusChange(
                attendeeEmail,
                expectedStatus,
                newStatus
            )
            when (scope) {
                "thisOccurrence" -> {
                    if (selectedOccurrenceWasDetached) {
                        updateAttendeeStatusForEvent(
                            calendarId,
                            eventId,
                            attendeeChange,
                            pendingChannelResult
                        )
                    } else {
                        applyEventChangesToOccurrence(
                            calendarId,
                            masterEventId,
                            originalOccurrenceStart,
                            emptyMap(),
                            pendingChannelResult,
                            attendeeChange
                        )
                    }
                    return
                }
                "entireSeries" -> {
                    updateAttendeeStatusForEntireSeries(
                        calendarId,
                        masterEventId,
                        attendeeChange,
                        pendingChannelResult
                    )
                    return
                }
                "thisAndFollowing" -> {
                    applyEventChangesToThisAndFollowing(
                        calendarId,
                        masterEventId,
                        eventId,
                        originalOccurrenceStart,
                        selectedOccurrenceWasDetached,
                        emptyMap(),
                        pendingChannelResult,
                        attendeeChange
                    )
                    return
                }
                else -> {
                    finishWithError(
                        EC.INVALID_ARGUMENT,
                        "Unknown recurrence change scope: $scope",
                        pendingChannelResult
                    )
                    return
                }
            }
        }

        updateAttendeeStatusForEvent(
            calendarId,
            eventId,
            AttendeeStatusChange(attendeeEmail, expectedStatus, newStatus),
            pendingChannelResult
        )
    }

    private data class AttendeeStatusChange(
        val email: String,
        val expectedStatus: Int,
        val newStatus: Int
    )

    /**
     * Assigns one RSVP status to the recurring master and all of its detached
     * exceptions without touching any other exception field.
     *
     * The selected/master status can already equal [AttendeeStatusChange.newStatus]
     * while another detached occurrence differs. Such a whole-series command
     * is not a no-op: the exception attendee rows still have to be updated.
     */
    private fun updateAttendeeStatusForEntireSeries(
        calendarId: String,
        masterEventId: String,
        change: AttendeeStatusChange,
        pendingChannelResult: MethodChannel.Result
    ) {
        val resolver = _context?.contentResolver
        val masterId = masterEventId.toLongOrNull()
        val calendarIdNumber = calendarId.toLongOrNull()
        if (resolver == null || masterId == null || calendarIdNumber == null) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid calendar or recurring master event",
                pendingChannelResult
            )
            return
        }

        val currentMasterStatus = queryAttendeeStatus(
            resolver,
            masterEventId,
            change.email
        )
        if (currentMasterStatus == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee ${change.email} could not be found for event $masterEventId",
                pendingChannelResult
            )
            return
        }
        if (currentMasterStatus != change.expectedStatus &&
            currentMasterStatus != change.newStatus
        ) {
            finishWithSuccess(
                attendeeStatusResult("conflict", currentMasterStatus, masterEventId,
                    diagnostics = mapOf("stage" to "entireSeries.rsvp.precondition")),
                pendingChannelResult
            )
            return
        }

        val eventIds = mutableListOf(masterEventId)
        val exceptionCursor = resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} = ? AND " +
                "${Events.DELETED} != 1",
            arrayOf(calendarId, masterEventId),
            null
        )
        exceptionCursor.use { cursor ->
            while (cursor?.moveToNext() == true) {
                val exceptionId = cursor.getLong(0).toString()
                if (exceptionId !in eventIds) eventIds.add(exceptionId)
            }
        }

        val placeholders = eventIds.joinToString(",") { "?" }
        val attendeeSelection =
            "(${CalendarContract.Attendees.EVENT_ID} IN ($placeholders)) AND " +
                "(${CalendarContract.Attendees.ATTENDEE_EMAIL} = ?)"
        val attendeeSelectionArgs =
            (eventIds + change.email).toTypedArray()
        var hasDifferingStatus = false
        var foundMasterAttendee = false
        val attendeeCursor = resolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(
                CalendarContract.Attendees.EVENT_ID,
                CalendarContract.Attendees.ATTENDEE_STATUS
            ),
            attendeeSelection,
            attendeeSelectionArgs,
            null
        )
        attendeeCursor.use { cursor ->
            while (cursor?.moveToNext() == true) {
                if (cursor.getLong(0) == masterId) foundMasterAttendee = true
                if (cursor.getInt(1) != change.newStatus) {
                    hasDifferingStatus = true
                }
            }
        }
        if (!foundMasterAttendee) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee ${change.email} could not be found for event $masterEventId",
                pendingChannelResult
            )
            return
        }

        val values = ContentValues().apply {
            put(CalendarContract.Attendees.ATTENDEE_STATUS, change.newStatus)
        }
        val updatedRows = resolver.update(
            CalendarContract.Attendees.CONTENT_URI,
            values,
            attendeeSelection,
            attendeeSelectionArgs
        )
        if (updatedRows <= 0) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The recurring RSVP assignment updated no attendee rows",
                pendingChannelResult
            )
            return
        }

        finishWithSuccess(
            attendeeStatusResult(
                if (hasDifferingStatus) "updated" else "alreadyCurrent",
                change.newStatus,
                masterEventId
            ),
            pendingChannelResult
        )
    }

    private fun updateAttendeeStatusForEvent(
        calendarId: String,
        eventId: String,
        change: AttendeeStatusChange,
        pendingChannelResult: MethodChannel.Result
    ) {
        val eventIdNumber = eventId.toLongOrNull()
        val calendarIdNumber = calendarId.toLongOrNull()
        if (eventIdNumber == null || calendarIdNumber == null) {
            finishWithError(EC.INVALID_ARGUMENT, "Invalid calendar or event", pendingChannelResult)
            return
        }
        val contentResolver = _context?.contentResolver
        val eventCursor = contentResolver?.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber),
            arrayOf(Events.CALENDAR_ID, Events.DELETED),
            null,
            null,
            null
        )
        val eventExists = eventCursor.use { cursor ->
            cursor?.moveToFirst() == true &&
                cursor.getLong(0) == calendarIdNumber &&
                cursor.getInt(1) != 1
        }
        if (!eventExists) {
            finishWithError(
                EC.NOT_FOUND,
                "The event with the ID $eventId could not be found in calendar $calendarId",
                pendingChannelResult
            )
            return
        }

        val selection =
            "(${CalendarContract.Attendees.EVENT_ID} = ?) AND " +
                "(${CalendarContract.Attendees.ATTENDEE_EMAIL} = ?) AND " +
                "(${CalendarContract.Attendees.ATTENDEE_STATUS} = ?)"
        val selectionArgs = arrayOf(
            eventId,
            change.email,
            change.expectedStatus.toString()
        )
        val values = ContentValues().apply {
            put(CalendarContract.Attendees.ATTENDEE_STATUS, change.newStatus)
        }
        val updatedRows = contentResolver?.update(
            CalendarContract.Attendees.CONTENT_URI,
            values,
            selection,
            selectionArgs
        ) ?: 0
        if (updatedRows > 0) {
            finishWithSuccess(
                attendeeStatusResult("updated", change.newStatus, eventId),
                pendingChannelResult
            )
            return
        }

        val currentStatusCursor = contentResolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS),
            "(${CalendarContract.Attendees.EVENT_ID} = ?) AND " +
                "(${CalendarContract.Attendees.ATTENDEE_EMAIL} = ?)",
            arrayOf(eventId, change.email),
            null
        )
        val currentStatus = currentStatusCursor.use { cursor ->
            if (cursor?.moveToFirst() == true) cursor.getInt(0) else null
        }
        if (currentStatus == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee ${change.email} could not be found for event $eventId",
                pendingChannelResult
            )
            return
        }

        finishWithSuccess(
            attendeeStatusResult(
                if (currentStatus == change.newStatus) "alreadyCurrent" else "conflict",
                currentStatus,
                eventId,
                diagnostics = mapOf("stage" to "event.rsvp.compareAndSetNoRows")
            ),
            pendingChannelResult
        )
    }

    private fun attendeeStatusResult(
        outcome: String,
        currentStatus: Int,
        resultingEventId: String? = null,
        diagnostics: Map<String, Any?>? = null
    ): Map<String, Any?> = mapOf(
        "outcome" to outcome,
        "currentStatus" to currentStatus,
        "resultingEventId" to resultingEventId,
        "diagnostics" to diagnostics.takeIf {
            ((_context?.applicationInfo?.flags ?: 0) and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
    )

    private fun queryAttendeeStatus(
        resolver: ContentResolver?,
        eventId: String,
        email: String
    ): Int? {
        val cursor = resolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS),
            "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                "${CalendarContract.Attendees.ATTENDEE_EMAIL} = ?",
            arrayOf(eventId, email),
            null
        )
        return cursor.use {
            if (it?.moveToFirst() == true) it.getInt(0) else null
        }
    }

    fun applyEventChanges(
        calendarId: String,
        eventId: String,
        eventChanges: Map<String, Any?>,
        recurrenceChangeTarget: Map<String, Any?>?,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (!arePermissionsGranted()) {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                APPLY_EVENT_CHANGES_REQUEST_CODE,
                calendarId,
                eventId = eventId,
                eventChanges = eventChanges,
                recurrenceChangeTarget = recurrenceChangeTarget
            )
            requestPermissions(parameters)
            return
        }

        if (recurrenceChangeTarget != null) {
            val scope = recurrenceChangeTarget["scope"] as? String
            val originalOccurrenceStart =
                (recurrenceChangeTarget["originalOccurrenceStart"] as? Number)?.toLong()
            val originalEventId = recurrenceChangeTarget["originalEventId"] as? String
            val selectedOccurrenceWasDetached =
                recurrenceChangeTarget["selectedOccurrenceWasDetached"] as? Boolean
            if (scope == null || originalOccurrenceStart == null ||
                selectedOccurrenceWasDetached == null) {
                finishWithError(
                    EC.INVALID_ARGUMENT,
                    "Invalid recurrence change target",
                    pendingChannelResult
                )
                return
            }

            val masterEventId = if (selectedOccurrenceWasDetached) originalEventId else eventId
            if (masterEventId.isNullOrEmpty()) {
                finishWithError(
                    EC.INVALID_ARGUMENT,
                    "A recurring master event ID is required",
                    pendingChannelResult
                )
                return
            }

            traceTestRecProviderState("SAVE-BEFORE scope=$scope", calendarId, masterEventId)

            when (scope) {
                "thisOccurrence" -> {
                    if (selectedOccurrenceWasDetached) {
                        applyEventChangesToEvent(
                            calendarId,
                            eventId,
                            eventChanges,
                            pendingChannelResult
                        )
                    } else {
                        applyEventChangesToOccurrence(
                            calendarId,
                            masterEventId,
                            originalOccurrenceStart,
                            eventChanges,
                            pendingChannelResult
                        )
                    }
                    traceTestRecProviderState(
                        "SAVE-AFTER scope=$scope",
                        calendarId,
                        masterEventId
                    )
                    return
                }
                "entireSeries" -> {
                    applyEventChangesToEntireSeries(
                        calendarId,
                        masterEventId,
                        eventId,
                        originalOccurrenceStart,
                        selectedOccurrenceWasDetached,
                        eventChanges,
                        pendingChannelResult,
                        resetDetachedOverrides = recurrenceChangeTarget["resetDetachedOverrides"] == true
                    )
                    traceTestRecProviderState(
                        "SAVE-AFTER scope=$scope",
                        calendarId,
                        masterEventId
                    )
                    return
                }
                "thisAndFollowing" -> {
                    applyEventChangesToThisAndFollowing(
                        calendarId,
                        masterEventId,
                        eventId,
                        originalOccurrenceStart,
                        selectedOccurrenceWasDetached,
                        eventChanges,
                        pendingChannelResult
                    )
                    traceTestRecProviderState(
                        "SAVE-AFTER scope=$scope",
                        calendarId,
                        masterEventId
                    )
                    return
                }
                else -> {
                    finishWithError(
                        EC.INVALID_ARGUMENT,
                        "Unknown recurrence change scope: $scope",
                        pendingChannelResult
                    )
                    return
                }
            }
        }

        applyEventChangesToEvent(
            calendarId,
            eventId,
            eventChanges,
            pendingChannelResult
        )
    }

    private fun applyEventChangesToEntireSeries(
        calendarId: String,
        masterEventId: String,
        selectedEventId: String,
        originalOccurrenceStart: Long,
        selectedOccurrenceWasDetached: Boolean,
        eventChanges: Map<String, Any?>,
        pendingChannelResult: MethodChannel.Result,
        resetDetachedOverrides: Boolean = false
    ) {
        val currentMaster = queryStoredEventChangeValues(
            _context?.contentResolver,
            calendarId,
            masterEventId
        )
        val masterRange = currentMaster?.dateRange
        if (currentMaster == null || currentMaster.deleted ||
            currentMaster.recurrenceRule == null || masterRange == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The recurring master event $masterEventId could not be found",
                pendingChannelResult
            )
            return
        }

        val dateRangeChange = eventChanges["dateRange"] as? Map<*, *>
        val remindersChange = eventChanges["reminders"] as? Map<*, *>
        val attendeesChange = eventChanges["attendees"] as? Map<*, *>
        val resourcesChange = eventChanges["resources"] as? Map<*, *>
        val recurrenceChange = eventChanges["recurrence"] as? Map<*, *>
        val locationChange = eventChanges["location"] as? Map<*, *>
        val requestedReminders =
            parseEventReminderValues(remindersChange?.get("requested"))
        val requestedAttendees =
            parseEventAttendeeValues(attendeesChange?.get("requested"))
        val requestedResources =
            parseEventResourceValues(resourcesChange?.get("requested"))
        val requestedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["requested"])
        }
        if (attendeesChange != null && requestedAttendees == null) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid attendee changes",
                pendingChannelResult
            )
            return
        }
        if (dateRangeChange == null) {
            if (shouldReplaceRecurringSeries(
                    recurrenceChange != null,
                    currentMaster.recurrenceRule,
                    requestedRecurrence?.rawRule
                )
            ) {
                val selectedRange = if (selectedOccurrenceWasDetached) {
                    queryStoredEventChangeValues(
                        _context?.contentResolver,
                        calendarId,
                        selectedEventId
                    )?.dateRange
                } else {
                    null
                }
                replaceRecurringSeriesWithStandalone(
                    calendarId,
                    masterEventId,
                    currentMaster,
                    eventChanges,
                    standaloneReplacementRange(
                        masterRange,
                        originalOccurrenceStart,
                        selectedRange
                    ),
                    pendingChannelResult
                )
            } else {
                val removedExceptionOperations = when (recurrenceExceptionCleanup(
                    resetDetachedOverrides,
                    recurrenceChange != null && requestedRecurrence?.rawRule != null
                )) {
                    RecurrenceExceptionCleanup.ALL -> emptyList()
                    RecurrenceExceptionCleanup.OUTSIDE_RULE -> recurringExceptionsOutsideRuleOperations(
                        contentResolver = _context?.contentResolver,
                        calendarId = calendarId,
                        masterEventId = masterEventId,
                        masterStart = masterRange.startDate,
                        masterTimeZone = masterRange.startTimeZone,
                        requestedRule = requestedRecurrence!!.rawRule!!
                    )
                    RecurrenceExceptionCleanup.NONE -> emptyList()
                }
                if (isTestRecTitle(currentMaster.title)) {
                    Log.i(
                        TEST_REC_TRACE_TAG,
                        "TEST_REC_STATE trigger=\"SAVE-WRITE-PLAN recurrence-prune\" " +
                            "source=device_calendar count=${removedExceptionOperations.size}"
                    )
                }
                applyEventChangesToEvent(
                    calendarId,
                    masterEventId,
                    eventChanges,
                    pendingChannelResult,
                    preEventOperations = removedExceptionOperations,
                    resetSeriesExceptions = resetDetachedOverrides
                )
            }
            return
        }

        val expectedOccurrence = parseEventDateRangeValue(dateRangeChange["expected"])
        val requestedOccurrence = parseEventDateRangeValue(dateRangeChange["requested"])
        val occurrenceDuration = masterRange.endDate - masterRange.startDate
        val currentOccurrence = if (selectedOccurrenceWasDetached) {
            queryStoredEventChangeValues(
                _context?.contentResolver,
                calendarId,
                selectedEventId
            )?.dateRange
        } else {
            EventDateRangeValue(
                startDate = originalOccurrenceStart,
                startTimeZone = masterRange.startTimeZone,
                endDate = originalOccurrenceStart + occurrenceDuration,
                endTimeZone = masterRange.endTimeZone,
                allDay = masterRange.allDay
            )
        }
        if (expectedOccurrence == null || requestedOccurrence == null ||
            currentOccurrence == null || expectedOccurrence != currentOccurrence) {
            val conflictRange = currentOccurrence ?: EventDateRangeValue(
                startDate = originalOccurrenceStart,
                startTimeZone = masterRange.startTimeZone,
                endDate = originalOccurrenceStart + occurrenceDuration,
                endTimeZone = masterRange.endTimeZone,
                allDay = masterRange.allDay
            )
            val occurrenceValues = currentMaster.copy(
                rawStartDate = conflictRange.startDate,
                rawEndDate = conflictRange.endDate,
                duration = null,
                rawStartTimeZone = conflictRange.startTimeZone,
                rawEndTimeZone = conflictRange.endTimeZone,
                recurrenceRule = currentMaster.recurrenceRule
            )
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "entireSeries.selectedOccurrence.precondition",
                    occurrenceValues,
                    eventChanges["color"] as? Map<*, *>,
                    requestedColorValue(eventChanges),
                    requestedColorKey(eventChanges),
                    eventChanges["title"] as? Map<*, *>,
                    requestedTitle(eventChanges),
                    dateRangeChange,
                    requestedOccurrence,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation(eventChanges),
                    recurrenceChange = recurrenceChange,
                    requestedRecurrence = requestedRecurrence,
                    diagnosticContext = mapOf(
                        "masterEventId" to masterEventId,
                        "selectedEventId" to selectedEventId,
                        "originalOccurrenceStart" to originalOccurrenceStart,
                        "selectedOccurrenceWasDetached" to selectedOccurrenceWasDetached,
                        "masterRange" to masterRange.toMap(),
                        "currentOccurrence" to currentOccurrence?.toMap()
                    )
                ),
                pendingChannelResult
            )
            return
        }

        val translatedExpected = masterRange
        val translatedRequested = translateOccurrenceRangeToSeriesMaster(
            masterRange,
            expectedOccurrence,
            requestedOccurrence
        )
        if (isTestRecTitle(currentMaster.title)) {
            Log.i(
                TEST_REC_TRACE_TAG,
                "TEST_REC_STATE trigger=\"SAVE-WRITE-PLAN scope=entireSeries\" " +
                    "source=device_calendar expected=" +
                    "${expectedOccurrence.startDate}..${expectedOccurrence.endDate} " +
                    "requested=${requestedOccurrence.startDate}..${requestedOccurrence.endDate} " +
                    "translated=${translatedRequested.startDate}..${translatedRequested.endDate} " +
                    "storage=DTSTART+DURATION+RRULE " +
                    "duration=${durationForDateRange(translatedRequested)}"
            )
        }
        val translatedChanges = eventChanges.toMutableMap().apply {
            put(
                "dateRange",
                mapOf(
                    "expected" to translatedExpected.toMap(),
                    "requested" to translatedRequested.toMap()
                )
            )
        }
        if (shouldReplaceRecurringSeries(
                recurrenceChange != null,
                currentMaster.recurrenceRule,
                requestedRecurrence?.rawRule
            )
        ) {
            replaceRecurringSeriesWithStandalone(
                calendarId,
                masterEventId,
                currentMaster,
                translatedChanges,
                standaloneReplacementRange(
                    masterRange,
                    originalOccurrenceStart,
                    requestedOccurrence
                ),
                pendingChannelResult
            )
        } else {
            applyEventChangesToEvent(
                calendarId,
                masterEventId,
                translatedChanges,
                pendingChannelResult,
                resetSeriesExceptions = true
            )
        }
    }

    private fun requestedColorValue(eventChanges: Map<String, Any?>): Int? =
        (((eventChanges["color"] as? Map<*, *>)?.get("requested") as? Map<*, *>)
            ?.get("color") as? Number)?.toInt()

    private data class StoredRecurrenceExceptionIdentity(
        val eventId: Long,
        val originalId: String,
        val originalInstanceTime: Long
    )

    /** All-series reset preserves surviving native rows, including their sync IDs. */
    private fun recurringExceptionResetOperations(
        contentResolver: ContentResolver,
        calendarId: String,
        masterEventId: String,
        originalRange: EventDateRangeValue,
        originalRule: String,
        resultingRange: EventDateRangeValue,
        resultingRule: String,
        masterChanges: ContentValues,
        eventChanges: Map<String, Any?>,
        ownerEmail: String?
    ): List<ContentProviderOperation> {
        val identities = recurrenceExceptionIdentities(contentResolver, calendarId, masterEventId)
        val operations = mutableListOf<ContentProviderOperation>()
        val memberQuery = recurrenceExceptionResetQuery(calendarId, masterEventId,
            queryMasterSyncId(contentResolver, masterEventId.toLong()))
        operations.add(ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
            .withSelection(memberQuery.selection, memberQuery.selectionArgs)
            .withExpectedCount(identities.size).build())
        if (identities.isEmpty()) return operations
        // Copy actual provider fields, not a partial UI event. Assertions make
        // a concurrent template/child change abort the entire batch.
        fun readGuarded(uri: Uri, columns: Array<String>, selection: String,
                        args: Array<String>): List<ContentValues> {
            val rows = mutableListOf<ContentValues>()
            val cursor = contentResolver.query(uri, arrayOf("_id", *columns), selection, args, null)
                ?: error("Provider reset source unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val values = ContentValues()
                    columns.forEachIndexed { index, column ->
                        if (it.isNull(index + 1)) values.putNull(column)
                        else values.put(column, it.getString(index + 1))
                    }
                    operations.add(calendarProviderRowGuard(uri, id, selection, args, values))
                    rows.add(values)
                }
            }
            operations.add(ContentProviderOperation.newAssertQuery(uri)
                .withSelection(selection, args).withExpectedCount(rows.size).build())
            return rows
        }
        val template = readGuarded(Events.CONTENT_URI, arrayOf(
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.CUSTOM_APP_URI,
            Events.AVAILABILITY, Events.STATUS, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
            Events.ORGANIZER, Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
            Events.ACCESS_LEVEL, Events.DTSTART, Events.RRULE, Events.DTEND,
            Events.DURATION, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.ALL_DAY
        ), "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.DELETED} != 1",
            arrayOf(masterEventId, calendarId)).single()
        // Date/definition read above must belong to the same source generation
        // as the recurrence mapping, even if only a colour/title was requested.
        val storedStart = template.getAsLong(Events.DTSTART)
        val storedEnd = template.getAsLong(Events.DTEND) ?:
            parseDurationMillis(template.getAsString(Events.DURATION))?.let { storedStart + it }
        val storedZone = template.getAsString(Events.EVENT_TIMEZONE) ?: TimeZone.getDefault().id
        if (storedStart != originalRange.startDate || storedEnd != originalRange.endDate ||
            storedZone != originalRange.startTimeZone ||
            (template.getAsString(Events.EVENT_END_TIMEZONE) ?: storedZone) != originalRange.endTimeZone ||
            (template.getAsInteger(Events.ALL_DAY) != 0) != originalRange.allDay ||
            template.getAsString(Events.RRULE) != originalRule) {
            throw android.content.OperationApplicationException("Series changed during reset planning")
        }
        template.putAll(masterChanges)
        val attendeeColumns = arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_TYPE,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.ATTENDEE_STATUS)
        var attendees = readGuarded(CalendarContract.Attendees.CONTENT_URI, attendeeColumns,
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(masterEventId))
        val reminderColumns = arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD)
        var reminders = readGuarded(CalendarContract.Reminders.CONTENT_URI, reminderColumns,
            "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(masterEventId))
        if (eventChanges.containsKey("attendees") || eventChanges.containsKey("resources")) {
            var people = attendees.map { row -> Attendee(
                row.getAsString(CalendarContract.Attendees.ATTENDEE_EMAIL) ?: "",
                row.getAsString(CalendarContract.Attendees.ATTENDEE_NAME),
                row.getAsInteger(CalendarContract.Attendees.ATTENDEE_TYPE) ?: 0,
                row.getAsInteger(CalendarContract.Attendees.ATTENDEE_STATUS),
                row.getAsInteger(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP) ==
                    CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
                row.getAsString(CalendarContract.Attendees.ATTENDEE_EMAIL).equals(ownerEmail, true)
            ) }
            (eventChanges["resources"] as? Map<*, *>)?.let {
                people = attendeesWithResources(people, parseEventResourceValues(it["requested"])!!)
            }
            (eventChanges["attendees"] as? Map<*, *>)?.let {
                people = attendeesWithPeople(people, parseEventAttendeeValues(it["requested"])!!,
                    ownerEmail, template.getAsString(Events.ORGANIZER))
            }
            attendees = people.map { attendee -> ContentValues().apply {
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, attendee.emailAddress)
                put(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, attendee.role)
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, recurrenceSplitAttendeeRelationship(attendee))
                put(CalendarContract.Attendees.ATTENDEE_STATUS, attendee.attendanceStatus)
            } }
        }
        (eventChanges["reminders"] as? Map<*, *>)?.let { change ->
            reminders = parseEventReminderValues(change["requested"])!!.map { reminder -> ContentValues().apply {
                put(CalendarContract.Reminders.MINUTES, reminder.minutes)
                put(CalendarContract.Reminders.METHOD, reminder.method)
            } }
        }
        fun replaceChildren(uri: Uri, eventColumn: String, eventId: Long, rows: List<ContentValues>) {
            operations.add(ContentProviderOperation.newDelete(uri)
                .withSelection("$eventColumn = ?", arrayOf(eventId.toString())).build())
            rows.forEach { row -> operations.add(ContentProviderOperation.newInsert(uri)
                .withValues(row).withValue(eventColumn, eventId).build()) }
        }
        val resets = recurrenceExceptionResets(identities.map { it.originalInstanceTime },
            originalRange, originalRule, resultingRange, resultingRule)
        identities.zip(resets).forEach { (identity, reset) ->
            val range = reset.range
            if (range == null) {
                operations.add(deleteRecurrenceExceptionOperation(calendarId, identity))
            } else {
                val values = ContentValues(template)
                recurrenceExceptionResetValues(range).forEach { (column, value) ->
                    when (value) {
                        null -> values.putNull(column)
                        is Long -> values.put(column, value)
                        is Int -> values.put(column, value)
                        is String -> values.put(column, value)
                    }
                }
                operations.add(ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection("${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND " +
                        "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} != 1",
                        arrayOf(identity.eventId.toString(), calendarId, identity.originalId,
                            identity.originalInstanceTime.toString()))
                    .withValues(values).withExpectedCount(1).build())
                replaceChildren(CalendarContract.Attendees.CONTENT_URI, CalendarContract.Attendees.EVENT_ID,
                    identity.eventId, attendees)
                replaceChildren(CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID,
                    identity.eventId, reminders)
            }
        }
        if (isTestRecTitle(template.getAsString(Events.TITLE))) {
            Log.i(TEST_REC_TRACE_TAG, "TEST_REC_STATE trigger=\"SAVE-WRITE-PLAN exception-reset-in-place\" " +
                "source=device_calendar updated=${resets.count { it.range != null }} " +
                "removedSlots=${resets.count { it.range == null }}")
        }
        return operations
    }

    /**
     * Removes detached rows whose original slots no longer exist after a
     * recurrence-only update. The deletes and RRULE update are submitted in
     * one provider batch, preventing a successfully shortened master from
     * leaving an independently visible orphan exception behind.
     */
    private fun recurringExceptionsOutsideRuleOperations(
        contentResolver: ContentResolver?,
        calendarId: String,
        masterEventId: String,
        masterStart: Long,
        masterTimeZone: String,
        requestedRule: String
    ): List<ContentProviderOperation> {
        if (contentResolver == null) return emptyList()
        val rule = try {
            Rrule(requestedRule)
        } catch (_: InvalidRecurrenceRuleException) {
            return emptyList()
        }
        return recurrenceExceptionIdentities(
            contentResolver = contentResolver,
            calendarId = calendarId,
            masterEventId = masterEventId
        ).filterNot { identity ->
            recurrenceRuleContainsOccurrenceStart(
                rule = rule,
                masterStart = masterStart,
                masterTimeZone = masterTimeZone,
                occurrenceStart = identity.originalInstanceTime
            )
        }.map { identity ->
            deleteRecurrenceExceptionOperation(calendarId, identity)
        }
    }

    private fun recurrenceExceptionIdentities(
        contentResolver: ContentResolver,
        calendarId: String,
        masterEventId: String
    ): List<StoredRecurrenceExceptionIdentity> {
        val resetQuery = recurrenceExceptionResetQuery(
            calendarId = calendarId,
            masterEventId = masterEventId,
            masterSyncId = masterEventId.toLongOrNull()?.let {
                queryMasterSyncId(contentResolver, it)
            }
        )
        val identities = mutableListOf<StoredRecurrenceExceptionIdentity>()
        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME),
            resetQuery.selection,
            resetQuery.selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                identities.add(
                    StoredRecurrenceExceptionIdentity(
                        eventId = cursor.getLong(0),
                        originalId = cursor.getString(1),
                        originalInstanceTime = cursor.getLong(2)
                    )
                )
            }
        }
        return identities
    }

    private fun deleteRecurrenceExceptionOperation(
        calendarId: String,
        identity: StoredRecurrenceExceptionIdentity
    ): ContentProviderOperation =
        ContentProviderOperation.newDelete(Events.CONTENT_URI)
            .withSelection(
                "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND " +
                    "${Events.ORIGINAL_ID} = ? AND " +
                    "${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} != 1",
                arrayOf(
                    identity.eventId.toString(),
                    calendarId,
                    identity.originalId,
                    identity.originalInstanceTime.toString()
                )
            )
            .withExpectedCount(1)
            .build()

    private data class TestRecProviderRow(
        val id: String,
        val originalId: String?,
        val originalInstanceTime: Long?,
        val title: String?,
        val location: String?,
        val start: Long?,
        val end: Long?,
        val duration: String?,
        val startTimeZone: String?,
        val endTimeZone: String?,
        val recurrenceRule: String?,
        val color: Int?,
        val colorKey: Int?,
        val status: Int?,
        val dirty: Boolean,
        val deleted: Boolean
    ) {
        val durationMillis: Long?
            get() = when {
                start != null && end != null -> end - start
                else -> parseDurationMillis(duration)
            }

        val resolvedEnd: Long?
            get() = end ?: if (start != null) {
                durationMillis?.let(start::plus)
            } else {
                null
            }
    }

    private fun isTestRecTitle(title: String?): Boolean =
        title?.trim()?.equals("test-rec", ignoreCase = true) == true

    private fun traceTestRecProviderState(
        trigger: String,
        calendarId: String,
        preferredMasterId: String? = null
    ) {
        val contentResolver = _context?.contentResolver ?: return
        val masters = mutableListOf<TestRecProviderRow>()
        val masterSelection = buildString {
            append("${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} IS NULL AND (")
            append("LOWER(TRIM(${Events.TITLE})) = ?")
            if (!preferredMasterId.isNullOrEmpty()) append(" OR ${Events._ID} = ?")
            append(")")
        }
        val masterArgs = mutableListOf(calendarId, "test-rec").apply {
            if (!preferredMasterId.isNullOrEmpty()) add(preferredMasterId)
        }
        queryTestRecProviderRows(contentResolver, masterSelection, masterArgs.toTypedArray())
            .filterTo(masters) { isTestRecTitle(it.title) }
        if (masters.isEmpty()) return

        for (master in masters.distinctBy(TestRecProviderRow::id)) {
            val exceptions = queryTestRecProviderRows(
                contentResolver,
                "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} = ?",
                arrayOf(calendarId, master.id)
            ).sortedWith(
                compareBy<TestRecProviderRow> { it.originalInstanceTime ?: Long.MIN_VALUE }
                    .thenBy(TestRecProviderRow::id)
            )
            Log.i(
                TEST_REC_TRACE_TAG,
                "TEST_REC_STATE trigger=\"$trigger\" source=provider MASTER " +
                    "calendar=$calendarId id=${master.id} recurrence=${master.recurrenceRule ?: "none"} " +
                    "start=${traceTestRecInstant(master.start)} " +
                    "end=${traceTestRecInstant(master.resolvedEnd)} " +
                    "durationRaw=${master.duration ?: "null"} " +
                    "durationMs=${master.durationMillis ?: "null"} " +
                    "timeZones=${master.startTimeZone ?: "null"}->${master.endTimeZone ?: "null"} " +
                    "color=${master.color ?: "default"}/${master.colorKey ?: "default"} " +
                    "dirty=${master.dirty} deleted=${master.deleted} " +
                    "exceptions=${exceptions.size}"
            )
            exceptions.forEachIndexed { index, exception ->
                val expectedStart = exception.originalInstanceTime
                val actualDuration = exception.durationMillis
                val diffs = mutableListOf<String>()
                if (expectedStart != null && exception.start != expectedStart) {
                    diffs.add("startDeltaMs=${exception.start?.minus(expectedStart)}")
                }
                if (actualDuration != master.durationMillis) {
                    diffs.add(
                        "duration=${master.durationMillis ?: "null"}->${actualDuration ?: "null"}"
                    )
                }
                if (exception.title != master.title) {
                    diffs.add("titleChanged=true")
                }
                if (exception.location != master.location) {
                    diffs.add("locationChanged=true")
                }
                if (exception.color != master.color || exception.colorKey != master.colorKey) {
                    diffs.add(
                        "color=${master.color}/${master.colorKey}->" +
                            "${exception.color}/${exception.colorKey}"
                    )
                }
                Log.i(
                    TEST_REC_TRACE_TAG,
                    "TEST_REC_STATE trigger=\"$trigger\" source=provider EXCEPTION " +
                        "index=${index + 1}/${exceptions.size} id=${exception.id} master=${master.id} " +
                        "slot=${traceTestRecInstant(exception.originalInstanceTime)} " +
                        "start=${traceTestRecInstant(exception.start)} " +
                        "end=${traceTestRecInstant(exception.resolvedEnd)} " +
                        "durationRaw=${exception.duration ?: "null"} " +
                        "status=${exception.status ?: "null"} dirty=${exception.dirty} " +
                        "deleted=${exception.deleted} diffs=${if (diffs.isEmpty()) "none" else diffs.joinToString(";")}"
                )
            }
        }
    }

    private fun queryTestRecProviderRows(
        contentResolver: ContentResolver,
        selection: String,
        selectionArgs: Array<String>
    ): List<TestRecProviderRow> {
        val rows = mutableListOf<TestRecProviderRow>()
        try {
            contentResolver.query(
                Events.CONTENT_URI,
                arrayOf(
                    Events._ID,
                    Events.ORIGINAL_ID,
                    Events.ORIGINAL_INSTANCE_TIME,
                    Events.TITLE,
                    Events.EVENT_LOCATION,
                    Events.DTSTART,
                    Events.DTEND,
                    Events.DURATION,
                    Events.EVENT_TIMEZONE,
                    Events.EVENT_END_TIMEZONE,
                    Events.RRULE,
                    Events.EVENT_COLOR,
                    Events.EVENT_COLOR_KEY,
                    Events.STATUS,
                    Events.DIRTY,
                    Events.DELETED
                ),
                selection,
                selectionArgs,
                "${Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        TestRecProviderRow(
                            id = cursor.getLong(0).toString(),
                            originalId = if (cursor.isNull(1)) null else cursor.getLong(1).toString(),
                            originalInstanceTime = if (cursor.isNull(2)) null else cursor.getLong(2),
                            title = if (cursor.isNull(3)) null else cursor.getString(3),
                            location = if (cursor.isNull(4)) null else cursor.getString(4),
                            start = if (cursor.isNull(5)) null else cursor.getLong(5),
                            end = if (cursor.isNull(6)) null else cursor.getLong(6),
                            duration = if (cursor.isNull(7)) null else cursor.getString(7),
                            startTimeZone = if (cursor.isNull(8)) null else cursor.getString(8),
                            endTimeZone = if (cursor.isNull(9)) null else cursor.getString(9),
                            recurrenceRule = if (cursor.isNull(10)) null else cursor.getString(10),
                            color = if (cursor.isNull(11)) null else cursor.getInt(11),
                            colorKey = if (cursor.isNull(12)) null else cursor.getInt(12),
                            status = if (cursor.isNull(13)) null else cursor.getInt(13),
                            dirty = !cursor.isNull(14) && cursor.getInt(14) == 1,
                            deleted = !cursor.isNull(15) && cursor.getInt(15) == 1
                        )
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TEST_REC_TRACE_TAG, "TEST_REC_STATE provider snapshot failed: $error")
        }
        return rows
    }

    private fun traceTestRecInstant(value: Long?): String =
        value?.let { "$it(${Date(it)})" } ?: "null"

    private fun queryRecurrenceExceptionEventId(
        contentResolver: ContentResolver,
        masterEventId: String,
        originalOccurrenceStart: Long
    ): Long? {
        return try {
            contentResolver.query(
                Events.CONTENT_URI,
                arrayOf(Events._ID),
                "${Events.ORIGINAL_ID} = ? AND " +
                    "${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} != 1",
                arrayOf(masterEventId, originalOccurrenceStart.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun requestedColorKey(eventChanges: Map<String, Any?>): Int? =
        (((eventChanges["color"] as? Map<*, *>)?.get("requested") as? Map<*, *>)
            ?.get("colorKey") as? Number)?.toInt()

    private fun requestedTitle(eventChanges: Map<String, Any?>): String? =
        ((eventChanges["title"] as? Map<*, *>)?.get("requested") as? String)

    private fun requestedLocation(eventChanges: Map<String, Any?>): String? =
        ((eventChanges["location"] as? Map<*, *>)?.get("requested") as? String)

    /**
     * Android's own Calendar application represents "all events -> does not
     * repeat" as deletion of the recurring master followed by insertion of a
     * standalone replacement. In-place RRULE removal can look correct in the
     * local provider while a Google sync adapter silently keeps the server
     * series. Keep the platform operation atomic and preserve the user-facing
     * event data on the replacement row.
     */
    private fun replaceRecurringSeriesWithStandalone(
        calendarId: String,
        masterEventId: String,
        currentMaster: StoredEventChangeValues,
        eventChanges: Map<String, Any?>,
        replacementRange: EventDateRangeValue,
        pendingChannelResult: MethodChannel.Result
    ) {
        val resolver = _context?.contentResolver
        val masterId = masterEventId.toLongOrNull()
        if (resolver == null || masterId == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The Calendar Provider is unavailable",
                pendingChannelResult
            )
            return
        }

        val colorChange = eventChanges["color"] as? Map<*, *>
        val titleChange = eventChanges["title"] as? Map<*, *>
        val locationChange = eventChanges["location"] as? Map<*, *>
        val dateRangeChange = eventChanges["dateRange"] as? Map<*, *>
        val remindersChange = eventChanges["reminders"] as? Map<*, *>
        val attendeesChange = eventChanges["attendees"] as? Map<*, *>
        val resourcesChange = eventChanges["resources"] as? Map<*, *>
        val recurrenceChange = eventChanges["recurrence"] as? Map<*, *>
        val expectedColor = colorChange?.get("expected") as? Map<*, *>
        val expectedColorValue = (expectedColor?.get("color") as? Number)?.toInt()
        val expectedColorKey = (expectedColor?.get("colorKey") as? Number)?.toInt()
        val expectedTitle = titleChange?.get("expected") as? String
        val expectedLocation = locationChange?.get("expected") as? String
        val expectedDateRange =
            parseEventDateRangeValue(dateRangeChange?.get("expected"))
        val requestedDateRange =
            parseEventDateRangeValue(dateRangeChange?.get("requested"))
        val expectedReminders =
            parseEventReminderValues(remindersChange?.get("expected"))
        val requestedReminders =
            parseEventReminderValues(remindersChange?.get("requested"))
        val expectedAttendees =
            parseEventAttendeeValues(attendeesChange?.get("expected"))
        val requestedAttendees =
            parseEventAttendeeValues(attendeesChange?.get("requested"))
        val expectedResources =
            parseEventResourceValues(resourcesChange?.get("expected"))
        val requestedResources =
            parseEventResourceValues(resourcesChange?.get("requested"))
        val expectedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["expected"])
        }
        val requestedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["requested"])
        }
        if ((dateRangeChange != null &&
                (expectedDateRange == null || requestedDateRange == null)) ||
            (remindersChange != null &&
                (expectedReminders == null || requestedReminders == null)) ||
            (attendeesChange != null &&
                (expectedAttendees == null || requestedAttendees == null)) ||
            (resourcesChange != null &&
                (expectedResources == null || requestedResources == null)) ||
            expectedRecurrence == null || requestedRecurrence == null ||
            requestedRecurrence.rawRule != null
        ) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid recurring series replacement",
                pendingChannelResult
            )
            return
        }

        val expectedStillMatches =
            (colorChange == null ||
                (currentMaster.color == expectedColorValue &&
                    currentMaster.colorKey == expectedColorKey)) &&
                (titleChange == null || currentMaster.title == expectedTitle) &&
                (locationChange == null || currentMaster.location == expectedLocation) &&
                (dateRangeChange == null || currentMaster.dateRange == expectedDateRange) &&
                (remindersChange == null || currentMaster.reminders == expectedReminders) &&
                (attendeesChange == null ||
                    sameAttendeeValues(currentMaster.attendees, expectedAttendees!!)) &&
                (resourcesChange == null ||
                    sameResourceValues(currentMaster.resources, expectedResources!!)) &&
                recurrenceRuleMap(currentMaster.recurrenceRule) == expectedRecurrence.rule
        if (!expectedStillMatches) {
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "standaloneReplacement.precondition",
                    currentMaster,
                    colorChange,
                    requestedColorValue(eventChanges),
                    requestedColorKey(eventChanges),
                    titleChange,
                    requestedTitle(eventChanges),
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation(eventChanges),
                    recurrenceChange = recurrenceChange,
                    requestedRecurrence = requestedRecurrence
                ),
                pendingChannelResult
            )
            return
        }

        val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            ?: return
        val sourceOwnership = queryRecurrenceMasterOwnership(resolver, masterId)
        val replacement = queryMasterEvent(resolver, calendarId, masterId)
        if (replacement == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The recurring master event $masterEventId could not be loaded",
                pendingChannelResult
            )
            return
        }
        replacement.attendees = retrieveAttendees(calendar, masterEventId, resolver)
        replacement.reminders = retrieveReminders(masterEventId, resolver)
        replacement.eventId = null
        replacement.syncId = null
        replacement.eventIsDirty = null
        replacement.eventIsDetached = false
        replacement.eventOriginalStartDate = null
        replacement.originalEventId = null
        replacement.recurrenceRule = null

        replacement.eventStartDate = replacementRange.startDate
        replacement.eventEndDate = replacementRange.endDate
        replacement.eventStartTimeZone = replacementRange.startTimeZone
        replacement.eventEndTimeZone = replacementRange.endTimeZone
        replacement.eventAllDay = replacementRange.allDay
        if (titleChange != null) replacement.eventTitle = requestedTitle(eventChanges)
        if (locationChange != null) {
            replacement.eventLocation = requestedLocation(eventChanges)
        }
        if (colorChange != null) {
            replacement.eventColor = requestedColorValue(eventChanges)
            replacement.eventColorKey = requestedColorKey(eventChanges)
        }
        if (remindersChange != null) {
            replacement.reminders = requestedReminders!!.map {
                Reminder(it.minutes, it.method)
            }.toMutableList()
        }
        if (resourcesChange != null) {
            replacement.attendees = attendeesWithResources(
                replacement.attendees,
                requestedResources!!
            )
        }
        if (attendeesChange != null) {
            replacement.attendees = attendeesWithPeople(
                replacement.attendees,
                requestedAttendees!!,
                calendar.ownerAccount,
                sourceOwnership.organizer
            )
        }

        val operations = ArrayList<ContentProviderOperation>()
        operations.add(
            ContentProviderOperation.newDelete(Events.CONTENT_URI)
                .withSelection(
                    "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND " +
                        "${Events.RRULE} = ? AND ${Events.DELETED} != 1",
                    arrayOf(masterEventId, calendarId, currentMaster.recurrenceRule)
                )
                .withExpectedCount(1)
                .build()
        )
        val replacementInsertIndex = operations.size
        operations.add(
            ContentProviderOperation.newInsert(Events.CONTENT_URI)
                .withValues(buildEventContentValues(replacement, calendarId))
                .build()
        )
        replacement.attendees.forEach { attendee ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                    .withValueBackReference(
                        CalendarContract.Attendees.EVENT_ID,
                        replacementInsertIndex
                    )
                    .withValue(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                        attendee.emailAddress
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        if (attendee.isOrganizer == true) {
                            CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                        } else {
                            CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                        }
                    )
                    .withValue(CalendarContract.Attendees.ATTENDEE_TYPE, attendee.role)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                        attendee.attendanceStatus
                    )
                    .build()
            )
        }
        replacement.reminders.forEach { reminder ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValueBackReference(
                        CalendarContract.Reminders.EVENT_ID,
                        replacementInsertIndex
                    )
                    .withValue(CalendarContract.Reminders.MINUTES, reminder.minutes)
                    .withValue(CalendarContract.Reminders.METHOD, reminder.method)
                    .build()
            )
        }

        val results = try {
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (exception: android.content.OperationApplicationException) {
            val latest = queryStoredEventChangeValues(
                resolver,
                calendarId,
                masterEventId
            ) ?: currentMaster
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "standaloneReplacement.atomicBatch",
                    latest,
                    colorChange,
                    requestedColorValue(eventChanges),
                    requestedColorKey(eventChanges),
                    titleChange,
                    requestedTitle(eventChanges),
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation(eventChanges),
                    recurrenceChange = recurrenceChange,
                    requestedRecurrence = requestedRecurrence,
                    batchFailure = exception
                ),
                pendingChannelResult
            )
            return
        } catch (exception: Exception) {
            finishWithError(
                EC.GENERIC_ERROR,
                exception.message ?: "The standalone replacement could not be created",
                pendingChannelResult
            )
            return
        }
        val newId = results[replacementInsertIndex].uri
            ?.lastPathSegment
            ?.toLongOrNull()
        if (newId == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The standalone replacement could not be created",
                pendingChannelResult
            )
            return
        }
        finishWithSuccess(
            eventChangeResult(
                "updated",
                emptyList(),
                requestedColorValue(eventChanges),
                requestedColorKey(eventChanges),
                requestedTitle(eventChanges),
                requestedDateRange,
                requestedReminders,
                attendees = requestedAttendees,
                resources = requestedResources,
                resultingEventId = newId.toString(),
                location = requestedLocation(eventChanges),
                recurrence = requestedRecurrence
            ),
            pendingChannelResult
        )
    }

    private fun applyEventChangesToThisAndFollowing(
        calendarId: String,
        masterEventId: String,
        selectedEventId: String,
        originalOccurrenceStart: Long,
        selectedOccurrenceWasDetached: Boolean,
        eventChanges: Map<String, Any?>,
        pendingChannelResult: MethodChannel.Result,
        attendeeStatusChange: AttendeeStatusChange? = null
    ) {
        val resolver = _context?.contentResolver
        val masterId = masterEventId.toLongOrNull()
        val currentMaster = queryStoredEventChangeValues(
            resolver,
            calendarId,
            masterEventId
        )
        val masterRange = currentMaster?.dateRange
        val rawRule = currentMaster?.recurrenceRule
        if (masterId == null || currentMaster == null || currentMaster.deleted ||
            masterRange == null || rawRule == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The recurring master event $masterEventId could not be found",
                pendingChannelResult
            )
            return
        }

        val boundaryExceptionEventId = if (!selectedOccurrenceWasDetached && resolver != null) {
            queryRecurrenceExceptionEventId(
                resolver,
                masterEventId,
                originalOccurrenceStart
            )?.toString()
        } else {
            null
        }
        val splitSourceEventId = recurrenceSplitSourceEventId(
            masterEventId = masterEventId,
            selectedEventId = selectedEventId,
            selectedOccurrenceWasDetached = selectedOccurrenceWasDetached,
            boundaryExceptionEventId = boundaryExceptionEventId
        )
        val splitSourceIsDetached = splitSourceEventId != masterEventId
        val selectedValues = if (splitSourceIsDetached) {
            queryStoredEventChangeValues(resolver, calendarId, splitSourceEventId)
        } else {
            currentMaster
        }
        val selectedRange = if (splitSourceIsDetached) {
            selectedValues?.dateRange
        } else {
            EventDateRangeValue(
                startDate = originalOccurrenceStart,
                startTimeZone = masterRange.startTimeZone,
                endDate = originalOccurrenceStart +
                    (masterRange.endDate - masterRange.startDate),
                endTimeZone = masterRange.endTimeZone,
                allDay = masterRange.allDay
            )
        }
        if (selectedValues == null || selectedValues.deleted || selectedRange == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The selected recurring occurrence could not be found",
                pendingChannelResult
            )
            return
        }
        val attendeeSourceEventId = splitSourceEventId
        val currentAttendeeStatus = attendeeStatusChange?.let {
            queryAttendeeStatus(resolver, attendeeSourceEventId, it.email)
        }
        if (attendeeStatusChange != null && currentAttendeeStatus == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee ${attendeeStatusChange.email} could not be found for event " +
                    attendeeSourceEventId,
                pendingChannelResult
            )
            return
        }

        val colorChange = eventChanges["color"] as? Map<*, *>
        val titleChange = eventChanges["title"] as? Map<*, *>
        val locationChange = eventChanges["location"] as? Map<*, *>
        val dateRangeChange = eventChanges["dateRange"] as? Map<*, *>
        val remindersChange = eventChanges["reminders"] as? Map<*, *>
        val attendeesChange = eventChanges["attendees"] as? Map<*, *>
        val resourcesChange = eventChanges["resources"] as? Map<*, *>
        val recurrenceChange = eventChanges["recurrence"] as? Map<*, *>
        val expectedColor = colorChange?.get("expected") as? Map<*, *>
        val expectedColorValue = (expectedColor?.get("color") as? Number)?.toInt()
        val expectedColorKey = (expectedColor?.get("colorKey") as? Number)?.toInt()
        val expectedTitle = titleChange?.get("expected") as? String
        val expectedLocation = locationChange?.get("expected") as? String
        val requestedLocation = requestedLocation(eventChanges)
        val expectedDateRange = parseEventDateRangeValue(dateRangeChange?.get("expected"))
        val requestedDateRange = parseEventDateRangeValue(dateRangeChange?.get("requested"))
        val expectedReminders =
            parseEventReminderValues(remindersChange?.get("expected"))
        val requestedReminders =
            parseEventReminderValues(remindersChange?.get("requested"))
        val expectedAttendees =
            parseEventAttendeeValues(attendeesChange?.get("expected"))
        val requestedAttendees =
            parseEventAttendeeValues(attendeesChange?.get("requested"))
        val expectedResources =
            parseEventResourceValues(resourcesChange?.get("expected"))
        val requestedResources =
            parseEventResourceValues(resourcesChange?.get("requested"))
        val expectedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["expected"])
        }
        val requestedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["requested"])
        }
        if (remindersChange != null &&
            (expectedReminders == null || requestedReminders == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid reminder changes",
                pendingChannelResult
            )
            return
        }
        if (resourcesChange != null &&
            (expectedResources == null || requestedResources == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid resource changes",
                pendingChannelResult
            )
            return
        }
        if (attendeesChange != null &&
            (expectedAttendees == null || requestedAttendees == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid attendee changes",
                pendingChannelResult
            )
            return
        }
        if (recurrenceChange != null &&
            (expectedRecurrence == null || requestedRecurrence == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid recurrence changes",
                pendingChannelResult
            )
            return
        }
        val expectedStillMatches =
            (colorChange == null ||
                (selectedValues.color == expectedColorValue &&
                    selectedValues.colorKey == expectedColorKey)) &&
                (titleChange == null || selectedValues.title == expectedTitle) &&
                (locationChange == null || selectedValues.location == expectedLocation) &&
                (dateRangeChange == null || selectedRange == expectedDateRange) &&
                (remindersChange == null || selectedValues.reminders == expectedReminders) &&
                (attendeesChange == null ||
                    sameAttendeeValues(selectedValues.attendees, expectedAttendees!!)) &&
                (resourcesChange == null || sameResourceValues(selectedValues.resources, expectedResources!!)) &&
                (recurrenceChange == null ||
                    recurrenceRuleMap(currentMaster.recurrenceRule) == expectedRecurrence!!.rule) &&
                (attendeeStatusChange == null ||
                    currentAttendeeStatus == attendeeStatusChange.expectedStatus)
        if (!expectedStillMatches) {
            if (attendeeStatusChange != null) {
                finishWithSuccess(
                    attendeeStatusResult(
                        if (currentAttendeeStatus == attendeeStatusChange.newStatus) {
                            "alreadyCurrent"
                        } else {
                            "conflict"
                        },
                        currentAttendeeStatus!!,
                        attendeeSourceEventId,
                        diagnostics = mapOf("stage" to "thisAndFollowing.rsvp.precondition")
                    ),
                    pendingChannelResult
                )
                return
            }
            val occurrenceValues = selectedValues.copy(
                rawStartDate = selectedRange.startDate,
                rawEndDate = selectedRange.endDate,
                duration = null,
                rawStartTimeZone = selectedRange.startTimeZone,
                rawEndTimeZone = selectedRange.endTimeZone,
                recurrenceRule = currentMaster.recurrenceRule
            )
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "thisAndFollowing.selectedOccurrence.precondition",
                    occurrenceValues,
                    colorChange,
                    requestedColorValue(eventChanges),
                    requestedColorKey(eventChanges),
                    titleChange,
                    requestedTitle(eventChanges),
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation,
                    recurrenceChange = recurrenceChange,
                    requestedRecurrence = requestedRecurrence
                ),
                pendingChannelResult
            )
            return
        }

        val originalRule = try {
            Rrule(rawRule)
        } catch (_: InvalidRecurrenceRuleException) {
            finishWithError(EC.INVALID_ARGUMENT, "Invalid recurring event rule", pendingChannelResult)
            return
        }
        val oldRule = Rrule(rawRule)
        val futureRule = Rrule(rawRule)
        val splitPosition = recurrenceSeriesSplitPosition(
            rule = originalRule,
            masterStart = masterRange.startDate,
            masterTimeZone = masterRange.startTimeZone,
            splitStart = originalOccurrenceStart
        )
        val occurrencesBefore = splitPosition.occurrencesBefore
        val previousOccurrenceStart = splitPosition.previousOccurrenceStart
        if (occurrencesBefore == 0 &&
            originalOccurrenceStart == masterRange.startDate) {
            if (attendeeStatusChange != null) {
                updateAttendeeStatusForEvent(
                    calendarId,
                    masterEventId,
                    attendeeStatusChange,
                    pendingChannelResult
                )
            } else {
                applyEventChangesToEntireSeries(
                    calendarId,
                    masterEventId,
                    selectedEventId,
                    originalOccurrenceStart,
                    selectedOccurrenceWasDetached,
                    eventChanges,
                    pendingChannelResult
                )
            }
            return
        }
        if (occurrencesBefore <= 0 || previousOccurrenceStart == null) {
            finishWithError(
                EC.NOT_ALLOWED,
                "The first occurrence cannot be split from an empty earlier series",
                pendingChannelResult
            )
            return
        }

        val originalCount = originalRule.count
        if (originalCount != null && originalCount > 0) {
            oldRule.count = occurrencesBefore
            futureRule.count = originalCount - occurrencesBefore
        } else {
            oldRule.until = DateTime(previousOccurrenceStart!!)
        }

        val futureSourceId = splitSourceEventId.toLongOrNull()
        val futureEvent = futureSourceId?.let {
            queryMasterEvent(resolver, calendarId, it)
        }
        if (futureEvent == null) {
            finishWithError(EC.NOT_FOUND, "The recurring master event could not be loaded", pendingChannelResult)
            return
        }
        val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
        if (calendar == null) return
        val sourceOwnership = queryRecurrenceMasterOwnership(resolver, masterId)
        val futureSourceEventId = futureSourceId.toString()
        futureEvent.attendees = retrieveAttendees(calendar, futureSourceEventId, resolver)
        futureEvent.reminders = retrieveReminders(futureSourceEventId, resolver)
        if (splitSourceIsDetached && futureEvent.attendees.isEmpty()) {
            futureEvent.attendees = retrieveAttendees(calendar, masterEventId, resolver)
        }
        if (splitSourceIsDetached && futureEvent.reminders.isEmpty()) {
            futureEvent.reminders = retrieveReminders(masterEventId, resolver)
        }
        val organizerEmail = sourceOwnership.organizer ?: calendar.ownerAccount
        futureEvent.attendees = recurrenceSplitAttendees(futureEvent.attendees).toMutableList()
        if (attendeeStatusChange != null) {
            var attendeeFound = false
            futureEvent.attendees = futureEvent.attendees.map { attendee ->
                if (attendee.emailAddress.equals(attendeeStatusChange.email, ignoreCase = true)) {
                    attendeeFound = true
                    Attendee(
                        attendee.emailAddress,
                        attendee.name,
                        attendee.role,
                        attendeeStatusChange.newStatus,
                        attendee.isOrganizer,
                        attendee.isCurrentUser
                    )
                } else {
                    attendee
                }
            }.toMutableList()
            if (!attendeeFound) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The attendee ${attendeeStatusChange.email} could not be found for event " +
                        attendeeSourceEventId,
                    pendingChannelResult
                )
                return
            }
        }

        val finalRange = requestedDateRange ?: selectedRange
        futureEvent.eventId = null
        futureEvent.syncId = null
        futureEvent.eventStartDate = finalRange.startDate
        futureEvent.eventEndDate = finalRange.endDate
        futureEvent.eventStartTimeZone = finalRange.startTimeZone
        futureEvent.eventEndTimeZone = finalRange.endTimeZone
        futureEvent.eventAllDay = finalRange.allDay
        futureEvent.recurrenceRule = if (recurrenceChange == null) {
            parseRecurrenceRuleString(futureRule.toString())
        } else {
            parseRecurrenceRuleString(requestedRecurrence!!.rawRule)
        }
        if (titleChange != null) futureEvent.eventTitle = requestedTitle(eventChanges)
        if (locationChange != null) futureEvent.eventLocation = requestedLocation
        if (colorChange != null) {
            futureEvent.eventColor = requestedColorValue(eventChanges)
            futureEvent.eventColorKey = requestedColorKey(eventChanges)
        }
        if (remindersChange != null) {
            futureEvent.reminders = requestedReminders!!.map {
                Reminder(it.minutes, it.method)
            }.toMutableList()
        }
        if (resourcesChange != null) {
            futureEvent.attendees = attendeesWithResources(
                futureEvent.attendees,
                requestedResources!!
            )
        }
        if (attendeesChange != null) {
            futureEvent.attendees = attendeesWithPeople(
                futureEvent.attendees,
                requestedAttendees!!,
                calendar.ownerAccount,
                sourceOwnership.organizer
            )
        }

        if (resolver == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The Calendar Provider is unavailable",
                pendingChannelResult
            )
            return
        }

        // Splitting a recurring series changes several provider tables. Keep
        // the old rule, the new master, its children, and stale exception
        // cleanup in one Calendar Provider transaction so a partial split can
        // never leave the calendar in an inconsistent state.
        val operations = ArrayList<ContentProviderOperation>()
        if (attendeeStatusChange != null) {
            operations.add(
                ContentProviderOperation.newAssertQuery(
                    CalendarContract.Attendees.CONTENT_URI
                )
                    .withSelection(
                        "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                            "${CalendarContract.Attendees.ATTENDEE_EMAIL} = ? AND " +
                            "${CalendarContract.Attendees.ATTENDEE_STATUS} = ?",
                        arrayOf(
                            attendeeSourceEventId,
                            attendeeStatusChange.email,
                            attendeeStatusChange.expectedStatus.toString()
                        )
                    )
                    .withExpectedCount(1)
                    .build()
            )
        }
        if (resourcesChange != null) {
            addResourceAssertions(
                operations,
                attendeeSourceEventId,
                expectedResources!!
            )
        }
        if (attendeesChange != null) {
            addAttendeeAssertions(
                operations,
                attendeeSourceEventId,
                expectedAttendees!!
            )
        }
        operations.add(
            ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                .withSelection(
                    "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND " +
                        "${Events.RRULE} = ? AND ${Events.DELETED} != 1",
                    arrayOf(masterEventId, calendarId, rawRule)
                )
                .withValue(Events.RRULE, oldRule.toString())
                .withExpectedCount(1)
                .build()
        )
        val futureEventInsertIndex = operations.size
        val futureEventValues = buildEventContentValues(futureEvent, calendarId).apply {
            val ownershipValues = recurrenceSplitOwnershipValues(organizerEmail)
            put(Events.HAS_ATTENDEE_DATA, ownershipValues[Events.HAS_ATTENDEE_DATA] as Int)
            (ownershipValues[Events.ORGANIZER] as? String)?.let {
                put(Events.ORGANIZER, it)
            }
        }
        operations.add(
            ContentProviderOperation.newInsert(Events.CONTENT_URI)
                .withValues(futureEventValues)
                .build()
        )
        futureEvent.attendees.forEach { attendee ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                    .withValueBackReference(
                        CalendarContract.Attendees.EVENT_ID,
                        futureEventInsertIndex
                    )
                    .withValue(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                        attendee.emailAddress
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        recurrenceSplitAttendeeRelationship(attendee)
                    )
                    .withValue(CalendarContract.Attendees.ATTENDEE_TYPE, attendee.role)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                        attendee.attendanceStatus
                    )
                    .build()
            )
        }
        futureEvent.reminders.forEach { reminder ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValueBackReference(
                        CalendarContract.Reminders.EVENT_ID,
                        futureEventInsertIndex
                    )
                    .withValue(CalendarContract.Reminders.MINUTES, reminder.minutes)
                    .withValue(CalendarContract.Reminders.METHOD, reminder.method)
                    .build()
            )
        }
        val originalIds = listOfNotNull(
            masterEventId,
            queryMasterSyncId(resolver, masterId)
        ).distinct()
        if (originalIds.isNotEmpty()) {
            val placeholders = originalIds.joinToString(",") { "?" }
            operations.add(
                ContentProviderOperation.newDelete(Events.CONTENT_URI)
                    .withSelection(
                        "${Events.CALENDAR_ID} = ? AND " +
                            "${Events.ORIGINAL_INSTANCE_TIME} >= ? AND " +
                            "${Events.ORIGINAL_ID} IN ($placeholders)",
                        (listOf(
                            calendarId,
                            originalOccurrenceStart.toString()
                        ) + originalIds).toTypedArray()
                    )
                    .build()
            )
        }

        val results = try {
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (exception: android.content.OperationApplicationException) {
            if (attendeeStatusChange != null) {
                val latestStatus = queryAttendeeStatus(
                    resolver,
                    attendeeSourceEventId,
                    attendeeStatusChange.email
                )
                if (latestStatus == null) {
                    finishWithError(
                        EC.NOT_FOUND,
                        "The attendee ${attendeeStatusChange.email} could not be found",
                        pendingChannelResult
                    )
                } else {
                    finishWithSuccess(
                        attendeeStatusResult(
                            if (latestStatus == attendeeStatusChange.newStatus) {
                                "alreadyCurrent"
                            } else {
                                "conflict"
                            },
                            latestStatus,
                            attendeeSourceEventId,
                            diagnostics = mapOf(
                                "stage" to "thisAndFollowing.atomicBatch",
                                "batchExceptionType" to exception.javaClass.name,
                                "batchExceptionMessage" to exception.message
                            )
                        ),
                        pendingChannelResult
                    )
                }
                return
            }
            val latestValues = queryStoredEventChangeValues(
                resolver,
                calendarId,
                if (selectedOccurrenceWasDetached) selectedEventId else masterEventId
            ) ?: currentMaster
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "thisAndFollowing.atomicBatch",
                    latestValues,
                    colorChange,
                    requestedColorValue(eventChanges),
                    requestedColorKey(eventChanges),
                    titleChange,
                    requestedTitle(eventChanges),
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation,
                    recurrenceChange = recurrenceChange,
                    requestedRecurrence = requestedRecurrence,
                    batchFailure = exception
                ),
                pendingChannelResult
            )
            return
        } catch (exception: Exception) {
            finishWithError(
                EC.GENERIC_ERROR,
                exception.message ?: "The future recurring series could not be created",
                pendingChannelResult
            )
            return
        }
        val newId = results[futureEventInsertIndex].uri?.lastPathSegment?.toLongOrNull()
        if (newId == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The future recurring series could not be created",
                pendingChannelResult
            )
            return
        }
        finishWithSuccess(
            if (attendeeStatusChange != null) {
                attendeeStatusResult(
                    "updated",
                    attendeeStatusChange.newStatus,
                    newId.toString()
                )
            } else {
                eventChangeResult(
                    "updated",
                    emptyList(),
                    requestedColorValue(eventChanges),
                    requestedColorKey(eventChanges),
                    requestedTitle(eventChanges),
                    requestedDateRange,
                    requestedReminders,
                    attendees = requestedAttendees,
                    resources = requestedResources,
                    resultingEventId = newId.toString(),
                    location = requestedLocation,
                    recurrence = requestedRecurrence
                )
            },
            pendingChannelResult
        )
    }

    private fun queryMasterEvent(
        resolver: ContentResolver?,
        calendarId: String,
        masterId: Long
    ): Event? {
        val cursor = resolver?.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, masterId),
            Cst.MASTER_EVENT_PROJECTION,
            null,
            null,
            null
        )
        return cursor.use {
            if (it?.moveToFirst() != true) return@use null
            parseMasterEvent(it)?.takeIf { event -> event.calendarId == calendarId }
        }
    }

    private fun queryMasterSyncId(resolver: ContentResolver?, masterId: Long): String? {
        val cursor = resolver?.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, masterId),
            arrayOf(Events._SYNC_ID),
            null,
            null,
            null
        )
        return cursor.use {
            if (it?.moveToFirst() == true && !it.isNull(0)) it.getString(0) else null
        }
    }

    private data class RecurrenceMasterOwnership(
        val organizer: String?,
        val hasAttendeeData: Boolean
    )

    private fun queryRecurrenceMasterOwnership(
        resolver: ContentResolver?,
        masterId: Long
    ): RecurrenceMasterOwnership {
        val cursor = resolver?.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, masterId),
            arrayOf(Events.ORGANIZER, Events.HAS_ATTENDEE_DATA),
            null,
            null,
            null
        )
        return cursor.use {
            if (it?.moveToFirst() != true) {
                RecurrenceMasterOwnership(null, false)
            } else {
                RecurrenceMasterOwnership(
                    organizer = if (it.isNull(0)) null else it.getString(0),
                    hasAttendeeData = !it.isNull(1) && it.getInt(1) != 0
                )
            }
        }
    }

    private fun clearFutureRecurringExceptions(
        resolver: ContentResolver?,
        calendarId: String,
        masterEventId: String,
        currentMasterSyncId: String?,
        fromOriginalStart: Long,
        excludedEventId: Long
    ) {
        val originalIds = listOfNotNull(masterEventId, currentMasterSyncId).distinct()
        if (originalIds.isEmpty()) return
        val placeholders = originalIds.joinToString(",") { "?" }
        resolver?.delete(
            Events.CONTENT_URI,
            "${Events.CALENDAR_ID} = ? AND ${Events._ID} != ? AND " +
                "${Events.ORIGINAL_INSTANCE_TIME} >= ? AND ${Events.ORIGINAL_ID} IN ($placeholders)",
            (listOf(calendarId, excludedEventId.toString(), fromOriginalStart.toString()) +
                originalIds).toTypedArray()
        )
    }

    private fun applyEventChangesToEvent(
        calendarId: String,
        eventId: String,
        eventChanges: Map<String, Any?>,
        pendingChannelResult: MethodChannel.Result,
        preEventOperations: List<ContentProviderOperation> = emptyList(),
        resetSeriesExceptions: Boolean = false
    ) {

        var rejectedBatch: android.content.OperationApplicationException? = null
        val eventIdNumber = eventId.toLongOrNull()
        val calendarIdNumber = calendarId.toLongOrNull()
        val colorChange = eventChanges["color"] as? Map<*, *>
        val titleChange = eventChanges["title"] as? Map<*, *>
        val locationChange = eventChanges["location"] as? Map<*, *>
        val dateRangeChange = eventChanges["dateRange"] as? Map<*, *>
        val remindersChange = eventChanges["reminders"] as? Map<*, *>
        val attendeesChange = eventChanges["attendees"] as? Map<*, *>
        val resourcesChange = eventChanges["resources"] as? Map<*, *>
        val recurrenceChange = eventChanges["recurrence"] as? Map<*, *>
        val expectedColor = colorChange?.get("expected") as? Map<*, *>
        val requestedColor = colorChange?.get("requested") as? Map<*, *>
        val expectedDateRange = parseEventDateRangeValue(dateRangeChange?.get("expected"))
        val requestedDateRange = parseEventDateRangeValue(dateRangeChange?.get("requested"))
        val expectedReminders = parseEventReminderValues(remindersChange?.get("expected"))
        val requestedReminders = parseEventReminderValues(remindersChange?.get("requested"))
        val expectedAttendees = parseEventAttendeeValues(attendeesChange?.get("expected"))
        val requestedAttendees = parseEventAttendeeValues(attendeesChange?.get("requested"))
        val expectedResources = parseEventResourceValues(resourcesChange?.get("expected"))
        val requestedResources = parseEventResourceValues(resourcesChange?.get("requested"))
        val expectedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["expected"])
        }
        val requestedRecurrence = recurrenceChange?.let {
            parseEventRecurrenceValue(it["requested"])
        }
        if (eventIdNumber == null || calendarIdNumber == null ||
            (colorChange == null && titleChange == null && locationChange == null && dateRangeChange == null &&
                remindersChange == null && attendeesChange == null && resourcesChange == null &&
                recurrenceChange == null) ||
            (colorChange != null && (expectedColor == null || requestedColor == null ||
                !expectedColor.containsKey("color") || !expectedColor.containsKey("colorKey") ||
                !requestedColor.containsKey("color") || !requestedColor.containsKey("colorKey"))) ||
            (titleChange != null && (!titleChange.containsKey("expected") ||
                (titleChange["expected"] != null && titleChange["expected"] !is String) ||
                titleChange["requested"] !is String)) ||
            (locationChange != null && (!locationChange.containsKey("expected") ||
                !locationChange.containsKey("requested") ||
                (locationChange["expected"] != null && locationChange["expected"] !is String) ||
                (locationChange["requested"] != null && locationChange["requested"] !is String))) ||
            (dateRangeChange != null &&
                (expectedDateRange == null || requestedDateRange == null)) ||
            (remindersChange != null &&
                (expectedReminders == null || requestedReminders == null)) ||
            (attendeesChange != null &&
                (expectedAttendees == null || requestedAttendees == null)) ||
            (resourcesChange != null &&
                (expectedResources == null || requestedResources == null)) ||
            (recurrenceChange != null &&
                (expectedRecurrence == null || requestedRecurrence == null))
        ) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid calendar, event, or event changes",
                pendingChannelResult
            )
            return
        }

        val expectedColorValue = (expectedColor?.get("color") as? Number)?.toInt()
        val expectedColorKey = (expectedColor?.get("colorKey") as? Number)?.toInt()
        val requestedColorValue = (requestedColor?.get("color") as? Number)?.toInt()
        val requestedColorKey = (requestedColor?.get("colorKey") as? Number)?.toInt()
        val expectedTitle = titleChange?.get("expected") as? String
        val requestedTitle = titleChange?.get("requested") as? String
        val expectedLocation = locationChange?.get("expected") as? String
        val requestedLocation = locationChange?.get("requested") as? String
        val contentResolver = _context?.contentResolver
        val currentValues = queryStoredEventChangeValues(
            contentResolver,
            calendarId,
            eventId
        )
        if (currentValues == null || currentValues.deleted) {
            finishWithError(
                EC.NOT_FOUND,
                "The event with the ID $eventId could not be found in calendar $calendarId",
                pendingChannelResult
            )
            return
        }

        val expectedValuesStillMatch =
            (colorChange == null ||
                (currentValues.color == expectedColorValue &&
                    currentValues.colorKey == expectedColorKey)) &&
                (titleChange == null || currentValues.title == expectedTitle) &&
                (locationChange == null || currentValues.location == expectedLocation) &&
                (dateRangeChange == null || currentValues.dateRange == expectedDateRange) &&
                (remindersChange == null || currentValues.reminders == expectedReminders) &&
                (attendeesChange == null ||
                    sameAttendeeValues(currentValues.attendees, expectedAttendees!!)) &&
                (resourcesChange == null || sameResourceValues(currentValues.resources, expectedResources!!)) &&
                (recurrenceChange == null ||
                    recurrenceRuleMap(currentValues.recurrenceRule) == expectedRecurrence!!.rule)
        if (!expectedValuesStillMatch) {
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "event.precondition",
                    currentValues,
                    colorChange,
                    requestedColorValue,
                    requestedColorKey,
                    titleChange,
                    requestedTitle,
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation,
                    recurrenceChange = recurrenceChange,
                    requestedRecurrence = requestedRecurrence
                ),
                pendingChannelResult
            )
            return
        }

        val resultingRecurrenceRule = if (recurrenceChange != null) {
            requestedRecurrence!!.rawRule
        } else {
            currentValues.recurrenceRule
        }
        if (dateRangeChange != null && resultingRecurrenceRule != null &&
            durationForDateRange(requestedDateRange!!) == null) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Recurring event ranges must use whole-second durations",
                pendingChannelResult
            )
            return
        }
        if (recurrenceChange != null && dateRangeChange == null &&
            requestedRecurrence!!.rawRule != null &&
            (currentValues.dateRange == null ||
                durationForDateRange(currentValues.dateRange!!) == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Recurring event ranges must use whole-second durations",
                pendingChannelResult
            )
            return
        }

        val selectionParts = mutableListOf(
            "${Events._ID} = ?",
            "${Events.CALENDAR_ID} = ?",
            "${Events.DELETED} != 1"
        )
        val selectionArgs = mutableListOf(eventId, calendarId)
        if (colorChange != null) {
            if (expectedColorValue == null) {
                selectionParts.add("(${Events.EVENT_COLOR} IS NULL OR ${Events.EVENT_COLOR} = 0)")
            } else {
                selectionParts.add("${Events.EVENT_COLOR} = ?")
                selectionArgs.add(expectedColorValue.toString())
            }
            if (expectedColorKey == null) {
                selectionParts.add("(${Events.EVENT_COLOR_KEY} IS NULL OR ${Events.EVENT_COLOR_KEY} = 0)")
            } else {
                selectionParts.add("${Events.EVENT_COLOR_KEY} = ?")
                selectionArgs.add(expectedColorKey.toString())
            }
        }
        if (titleChange != null) {
            if (expectedTitle == null) {
                selectionParts.add("${Events.TITLE} IS NULL")
            } else {
                selectionParts.add("${Events.TITLE} = ?")
                selectionArgs.add(expectedTitle)
            }
        }
        if (locationChange != null) {
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.EVENT_LOCATION,
                expectedLocation
            )
        }
        if (dateRangeChange != null) {
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.DTSTART,
                currentValues.rawStartDate
            )
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.EVENT_TIMEZONE,
                currentValues.rawStartTimeZone
            )
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.DTEND,
                currentValues.rawEndDate
            )
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.EVENT_END_TIMEZONE,
                currentValues.rawEndTimeZone
            )
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.DURATION,
                currentValues.duration
            )
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.RRULE,
                currentValues.recurrenceRule
            )
            selectionParts.add("${Events.ALL_DAY} = ?")
            selectionArgs.add(if (currentValues.allDay) "1" else "0")
        }
        if (recurrenceChange != null && dateRangeChange == null) {
            addNullableSelection(
                selectionParts,
                selectionArgs,
                Events.RRULE,
                currentValues.recurrenceRule
            )
        }

        val values = ContentValues().apply {
            if (colorChange != null) {
                if (requestedColorKey == null) {
                    putNull(Events.EVENT_COLOR_KEY)
                    putNull(Events.EVENT_COLOR)
                } else {
                    put(Events.EVENT_COLOR_KEY, requestedColorKey)
                }
            }
            if (titleChange != null) put(Events.TITLE, requestedTitle)
            if (locationChange != null) {
                if (requestedLocation == null) putNull(Events.EVENT_LOCATION)
                else put(Events.EVENT_LOCATION, requestedLocation)
            }
            if (dateRangeChange != null) {
                if (resultingRecurrenceRule == null) {
                    put(Events.DTSTART, requestedDateRange!!.startDate)
                    put(Events.EVENT_TIMEZONE, requestedDateRange.startTimeZone)
                    put(Events.ALL_DAY, if (requestedDateRange.allDay) 1 else 0)
                    put(Events.DTEND, requestedDateRange.endDate)
                    put(Events.EVENT_END_TIMEZONE, requestedDateRange.endTimeZone)
                    putNull(Events.DURATION)
                } else {
                    recurringEventDateStorageChanges(
                        rawRule = resultingRecurrenceRule,
                        startDate = requestedDateRange!!.startDate,
                        startTimeZone = requestedDateRange.startTimeZone,
                        endDate = requestedDateRange.endDate,
                        endTimeZone = requestedDateRange.endTimeZone,
                        allDay = requestedDateRange.allDay,
                        duration = durationForDateRange(requestedDateRange)
                    ).forEach { (column, value) ->
                        when (value) {
                            null -> putNull(column)
                            is String -> put(column, value)
                            is Long -> put(column, value)
                            is Int -> put(column, value)
                            else -> error("Unsupported recurring date value for $column")
                        }
                    }
                }
            }
            if (recurrenceChange != null) {
                val recurrenceRange = requestedDateRange ?: currentValues.dateRange!!
                recurrenceStorageChanges(
                    rawRule = requestedRecurrence!!.rawRule,
                    startDate = recurrenceRange.startDate,
                    startTimeZone = recurrenceRange.startTimeZone,
                    endDate = recurrenceRange.endDate,
                    endTimeZone = recurrenceRange.endTimeZone,
                    allDay = recurrenceRange.allDay,
                    duration = durationForDateRange(recurrenceRange)
                ).forEach { (column, value) ->
                    when (value) {
                        null -> putNull(column)
                        is String -> put(column, value)
                        is Long -> put(column, value)
                        is Int -> put(column, value)
                        else -> error("Unsupported recurrence value for $column")
                    }
                }
            }
        }
        if (contentResolver == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The Calendar Provider is unavailable",
                pendingChannelResult
            )
            return
        }

        val ownerAttendeeToInsert = if (attendeesChange != null) {
            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true) ?: return
            val existingProviderAttendees =
                retrieveAttendees(calendar, eventId, contentResolver)
            val ownership = queryRecurrenceMasterOwnership(contentResolver, eventIdNumber)
            attendeesWithPeople(
                existingProviderAttendees,
                requestedAttendees!!,
                calendar.ownerAccount,
                ownership.organizer
            ).firstOrNull { requested ->
                requested.emailAddress.equals(calendar.ownerAccount, ignoreCase = true) &&
                    existingProviderAttendees.none {
                        it.emailAddress.equals(calendar.ownerAccount, ignoreCase = true)
                    }
            }
        } else {
            null
        }

        val operations = ArrayList<ContentProviderOperation>()
        operations.addAll(preEventOperations)
        val eventSelection = selectionParts.joinToString(" AND ")
        val eventSelectionArgs = selectionArgs.toTypedArray()
        if (values.size() > 0) {
            operations.add(
                ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection(eventSelection, eventSelectionArgs)
                    .withValues(values)
                    .withExpectedCount(1)
                    .build()
            )
        } else {
            operations.add(
                ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                    .withSelection(eventSelection, eventSelectionArgs)
                    .withExpectedCount(1)
                    .build()
            )
        }

        if (remindersChange != null) {
            val reminderEventSelection = "${CalendarContract.Reminders.EVENT_ID} = ?"
            val reminderEventSelectionArgs = arrayOf(eventId)
            operations.add(
                ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
                    .withSelection(reminderEventSelection, reminderEventSelectionArgs)
                    .withExpectedCount(expectedReminders!!.size)
                    .build()
            )
            expectedReminders.groupingBy { it }.eachCount().forEach { (reminder, count) ->
                operations.add(
                    ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
                        .withSelection(
                            "$reminderEventSelection AND " +
                                "${CalendarContract.Reminders.MINUTES} = ? AND " +
                                "${CalendarContract.Reminders.METHOD} = ?",
                            arrayOf(
                                eventId,
                                reminder.minutes.toString(),
                                reminder.method.toString()
                            )
                        )
                        .withExpectedCount(count)
                        .build()
                )
            }
            operations.add(
                ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                    .withSelection(reminderEventSelection, reminderEventSelectionArgs)
                    .withExpectedCount(expectedReminders.size)
                    .build()
            )
            requestedReminders!!.forEach { reminder ->
                operations.add(
                    ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValue(CalendarContract.Reminders.EVENT_ID, eventIdNumber)
                        .withValue(CalendarContract.Reminders.MINUTES, reminder.minutes)
                        .withValue(CalendarContract.Reminders.METHOD, reminder.method)
                        .build()
                )
            }
        }

        if (resourcesChange != null) {
            val resourceSelection =
                "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                    "${CalendarContract.Attendees.ATTENDEE_TYPE} = ?"
            val resourceSelectionArgs = arrayOf(
                eventId,
                CalendarContract.Attendees.TYPE_RESOURCE.toString()
            )
            operations.add(
                ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                    .withSelection(resourceSelection, resourceSelectionArgs)
                    .withExpectedCount(expectedResources!!.size)
                    .build()
            )
            expectedResources.groupingBy(EventResourceValue::identity).eachCount()
                .forEach { (identity, count) ->
                    val resource = expectedResources.first { it.identity() == identity }
                    val parts = mutableListOf(resourceSelection)
                    val args = mutableListOf(*resourceSelectionArgs)
                    if (resource.email == null) {
                        parts.add("(${CalendarContract.Attendees.ATTENDEE_EMAIL} IS NULL OR ${CalendarContract.Attendees.ATTENDEE_EMAIL} = '')")
                    } else {
                        parts.add("LOWER(${CalendarContract.Attendees.ATTENDEE_EMAIL}) = ?")
                        args.add(resource.email.lowercase())
                    }
                    if (resource.name == null) {
                        parts.add("(${CalendarContract.Attendees.ATTENDEE_NAME} IS NULL OR ${CalendarContract.Attendees.ATTENDEE_NAME} = '')")
                    } else {
                        parts.add("${CalendarContract.Attendees.ATTENDEE_NAME} = ?")
                        args.add(resource.name)
                    }
                    operations.add(
                        ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                            .withSelection(parts.joinToString(" AND "), args.toTypedArray())
                            .withExpectedCount(count)
                            .build()
                    )
                }
            operations.add(
                ContentProviderOperation.newDelete(CalendarContract.Attendees.CONTENT_URI)
                    .withSelection(resourceSelection, resourceSelectionArgs)
                    .withExpectedCount(expectedResources.size)
                    .build()
            )
            requestedResources!!.forEach { resource ->
                operations.add(
                    ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                        .withValue(CalendarContract.Attendees.EVENT_ID, eventIdNumber)
                        .withValue(CalendarContract.Attendees.ATTENDEE_NAME, resource.name)
                        .withValue(CalendarContract.Attendees.ATTENDEE_EMAIL, resource.email ?: "")
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_TYPE,
                            CalendarContract.Attendees.TYPE_RESOURCE
                        )
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                            CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                        )
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_STATUS,
                            CalendarContract.Attendees.ATTENDEE_STATUS_NONE
                        )
                        .build()
                )
            }
        }

        if (attendeesChange != null) {
            addAttendeeChangeOperations(
                operations,
                eventId,
                eventIdNumber,
                expectedAttendees!!,
                requestedAttendees!!
            )
            ownerAttendeeToInsert?.let { owner ->
                operations.add(
                    ContentProviderOperation.newInsert(
                        CalendarContract.Attendees.CONTENT_URI
                    )
                        .withValue(CalendarContract.Attendees.EVENT_ID, eventIdNumber)
                        .withValue(CalendarContract.Attendees.ATTENDEE_NAME, owner.name)
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_EMAIL,
                            owner.emailAddress
                        )
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_TYPE,
                            owner.role
                        )
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                            CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                        )
                        .withValue(
                            CalendarContract.Attendees.ATTENDEE_STATUS,
                            owner.attendanceStatus
                        )
                        .build()
                )
            }
        }

        val resetCalendar = if (resetSeriesExceptions) {
            retrieveCalendar(calendarId, pendingChannelResult, true) ?: return
        } else null
        try {
            runAtomicCalendarWrite {
                if (resetSeriesExceptions) {
                    operations.addAll(0, recurringExceptionResetOperations(contentResolver,
                        calendarId, eventId, currentValues.dateRange!!, currentValues.recurrenceRule!!,
                        requestedDateRange ?: currentValues.dateRange!!, resultingRecurrenceRule!!,
                        values, eventChanges, resetCalendar?.ownerAccount))
                }
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            }
            finishWithSuccess(
                eventChangeResult(
                    "updated",
                    emptyList(),
                    requestedColorValue,
                    requestedColorKey,
                    requestedTitle,
                    requestedDateRange,
                    requestedReminders,
                    attendees = requestedAttendees,
                    resources = requestedResources,
                    location = requestedLocation,
                    recurrence = requestedRecurrence
                ),
                pendingChannelResult
            )
            return
        } catch (exception: CalendarAtomicWriteRejected) {
            finishWithError(exception.code,
                exception.message ?: "The atomic calendar write was rejected", pendingChannelResult)
            return
        } catch (exception: android.content.OperationApplicationException) {
            // One of the optimistic assertions failed. Re-read every field so
            // the caller can resolve the complete concurrent change.
            rejectedBatch = exception
        } catch (exception: Exception) {
            finishWithError(
                EC.GENERIC_ERROR,
                exception.message ?: "The event changes could not be applied",
                pendingChannelResult
            )
            return
        }

        val latestValues = queryStoredEventChangeValues(contentResolver, calendarId, eventId)
        if (latestValues == null || latestValues.deleted) {
            finishWithError(
                EC.NOT_FOUND,
                "The event with the ID $eventId could not be found in calendar $calendarId",
                pendingChannelResult
            )
            return
        }

        finishWithSuccess(
            eventChangeResultForCurrent(
                "event.atomicBatch",
                latestValues,
                colorChange,
                requestedColorValue,
                requestedColorKey,
                titleChange,
                requestedTitle,
                dateRangeChange,
                requestedDateRange,
                remindersChange,
                requestedReminders,
                attendeesChange = attendeesChange,
                requestedAttendees = requestedAttendees,
                resourcesChange = resourcesChange,
                requestedResources = requestedResources,
                locationChange = locationChange,
                requestedLocation = requestedLocation,
                recurrenceChange = recurrenceChange,
                requestedRecurrence = requestedRecurrence,
                batchFailure = rejectedBatch
            ),
            pendingChannelResult
        )
    }

    private fun applyEventChangesToOccurrence(
        calendarId: String,
        masterEventId: String,
        originalOccurrenceStart: Long,
        eventChanges: Map<String, Any?>,
        pendingChannelResult: MethodChannel.Result,
        attendeeStatusChange: AttendeeStatusChange? = null
    ) {
        if (eventChanges["recurrence"] != null) {
            finishWithError(
                EC.NOT_ALLOWED,
                "A recurrence rule cannot be changed for only one occurrence",
                pendingChannelResult
            )
            return
        }

        val contentResolver = _context?.contentResolver
        val masterId = masterEventId.toLongOrNull()
        val currentMaster = queryStoredEventChangeValues(
            contentResolver,
            calendarId,
            masterEventId
        )
        if (masterId == null || currentMaster == null || currentMaster.deleted ||
            currentMaster.recurrenceRule == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The recurring master event $masterEventId could not be found",
                pendingChannelResult
            )
            return
        }
        val currentAttendeeStatus = attendeeStatusChange?.let {
            queryAttendeeStatus(contentResolver, masterEventId, it.email)
        }
        if (attendeeStatusChange != null && currentAttendeeStatus == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee ${attendeeStatusChange.email} could not be found for event " +
                    masterEventId,
                pendingChannelResult
            )
            return
        }

        val colorChange = eventChanges["color"] as? Map<*, *>
        val titleChange = eventChanges["title"] as? Map<*, *>
        val locationChange = eventChanges["location"] as? Map<*, *>
        val dateRangeChange = eventChanges["dateRange"] as? Map<*, *>
        val remindersChange = eventChanges["reminders"] as? Map<*, *>
        val attendeesChange = eventChanges["attendees"] as? Map<*, *>
        val resourcesChange = eventChanges["resources"] as? Map<*, *>
        val expectedColor = colorChange?.get("expected") as? Map<*, *>
        val requestedColor = colorChange?.get("requested") as? Map<*, *>
        val expectedDateRange = parseEventDateRangeValue(dateRangeChange?.get("expected"))
        val requestedDateRange = parseEventDateRangeValue(dateRangeChange?.get("requested"))
        val expectedReminders = parseEventReminderValues(remindersChange?.get("expected"))
        val requestedReminders = parseEventReminderValues(remindersChange?.get("requested"))
        val expectedAttendees = parseEventAttendeeValues(attendeesChange?.get("expected"))
        val requestedAttendees = parseEventAttendeeValues(attendeesChange?.get("requested"))
        val expectedResources = parseEventResourceValues(resourcesChange?.get("expected"))
        val requestedResources = parseEventResourceValues(resourcesChange?.get("requested"))
        if (remindersChange != null &&
            (expectedReminders == null || requestedReminders == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid reminder changes",
                pendingChannelResult
            )
            return
        }
        if (resourcesChange != null &&
            (expectedResources == null || requestedResources == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid resource changes",
                pendingChannelResult
            )
            return
        }
        if (attendeesChange != null &&
            (expectedAttendees == null || requestedAttendees == null)) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid attendee changes",
                pendingChannelResult
            )
            return
        }
        val expectedTitle = titleChange?.get("expected") as? String
        val requestedTitle = titleChange?.get("requested") as? String
        val expectedLocation = locationChange?.get("expected") as? String
        val requestedLocation = locationChange?.get("requested") as? String
        val expectedColorValue = (expectedColor?.get("color") as? Number)?.toInt()
        val expectedColorKey = (expectedColor?.get("colorKey") as? Number)?.toInt()
        val requestedColorValue = (requestedColor?.get("color") as? Number)?.toInt()
        val requestedColorKey = (requestedColor?.get("colorKey") as? Number)?.toInt()
        val exceptionColorWritePlan = recurrenceExceptionColorWritePlan(
            colorChange != null,
            requestedColorKey
        )
        val occurrenceDuration = currentMaster.dateRange?.let {
            it.endDate - it.startDate
        } ?: 0L
        val occurrenceCurrentRange = currentMaster.dateRange?.let {
            EventDateRangeValue(
                startDate = originalOccurrenceStart,
                startTimeZone = it.startTimeZone,
                endDate = originalOccurrenceStart + occurrenceDuration,
                endTimeZone = it.endTimeZone,
                allDay = it.allDay
            )
        }
        val expectedStillMatches =
            (colorChange == null ||
                (currentMaster.color == expectedColorValue &&
                    currentMaster.colorKey == expectedColorKey)) &&
                (titleChange == null || currentMaster.title == expectedTitle) &&
                (locationChange == null || currentMaster.location == expectedLocation) &&
                (dateRangeChange == null || occurrenceCurrentRange == expectedDateRange) &&
                (remindersChange == null || currentMaster.reminders == expectedReminders) &&
                (attendeesChange == null ||
                    sameAttendeeValues(currentMaster.attendees, expectedAttendees!!)) &&
                (resourcesChange == null || sameResourceValues(currentMaster.resources, expectedResources!!)) &&
                (attendeeStatusChange == null ||
                    currentAttendeeStatus == attendeeStatusChange.expectedStatus)
        if (!expectedStillMatches) {
            if (attendeeStatusChange != null) {
                finishWithSuccess(
                    attendeeStatusResult(
                        if (currentAttendeeStatus == attendeeStatusChange.newStatus) {
                            "alreadyCurrent"
                        } else {
                            "conflict"
                        },
                        currentAttendeeStatus!!,
                        masterEventId,
                        diagnostics = mapOf("stage" to "occurrence.rsvp.precondition")
                    ),
                    pendingChannelResult
                )
                return
            }
            val occurrenceValues = currentMaster.copy(
                rawStartDate = occurrenceCurrentRange?.startDate,
                rawEndDate = occurrenceCurrentRange?.endDate,
                duration = null,
                rawStartTimeZone = occurrenceCurrentRange?.startTimeZone,
                rawEndTimeZone = occurrenceCurrentRange?.endTimeZone,
                allDay = occurrenceCurrentRange?.allDay ?: currentMaster.allDay,
                recurrenceRule = null
            )
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "occurrence.precondition",
                    occurrenceValues,
                    colorChange,
                    requestedColorValue,
                    requestedColorKey,
                    titleChange,
                    requestedTitle,
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation
                ),
                pendingChannelResult
            )
            return
        }

        val finalRange = requestedDateRange ?: occurrenceCurrentRange
        if (finalRange == null) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "The recurring occurrence has no valid date range",
                pendingChannelResult
            )
            return
        }
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, originalOccurrenceStart)
            recurrenceExceptionDateChanges(finalRange).forEach { (column, value) ->
                when (value) {
                    is Long -> put(column, value)
                    is Int -> put(column, value)
                    is String -> put(column, value)
                    null -> putNull(column)
                    else -> error("Unsupported recurrence exception value for $column")
                }
            }
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            put(Events.TITLE, requestedTitle ?: currentMaster.title)
            if (locationChange != null) {
                if (requestedLocation == null) putNull(Events.EVENT_LOCATION)
                else put(Events.EVENT_LOCATION, requestedLocation)
            } else if (currentMaster.location == null) {
                putNull(Events.EVENT_LOCATION)
            } else {
                put(Events.EVENT_LOCATION, currentMaster.location)
            }
            // Do not put EVENT_COLOR_KEY on CONTENT_EXCEPTION_URI. Samsung's
            // CalendarProvider reads the master cursor before moveToFirst()
            // whenever that column is present, crashing its Binder thread with
            // CursorIndexOutOfBoundsException. Apply the colour to the newly
            // created exception's normal Events URI below instead.
            exceptionColorWritePlan.exceptionInsertValues.forEach { (column, value) ->
                when (value) {
                    is Int -> put(column, value)
                    null -> putNull(column)
                    else -> error("Unsupported recurrence exception colour value for $column")
                }
            }
        }
        val exceptionUri = ContentUris.withAppendedId(
            Events.CONTENT_EXCEPTION_URI,
            masterId
        )
        if (contentResolver == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The Calendar Provider is unavailable",
                pendingChannelResult
            )
            return
        }
        val attendeeCalendar = if (
            attendeeStatusChange != null || attendeesChange != null || resourcesChange != null
        ) {
            retrieveCalendar(calendarId, pendingChannelResult, true) ?: return
        } else {
            null
        }
        var occurrenceAttendees = attendeeCalendar?.let {
            retrieveAttendees(it, masterEventId, contentResolver)
        } ?: mutableListOf()
        if (resourcesChange != null) {
            occurrenceAttendees = attendeesWithResources(
                occurrenceAttendees,
                requestedResources!!
            )
        }
        if (attendeesChange != null) {
            occurrenceAttendees = attendeesWithPeople(
                occurrenceAttendees,
                requestedAttendees!!,
                attendeeCalendar?.ownerAccount,
                queryRecurrenceMasterOwnership(contentResolver, masterId).organizer
            )
        }
        if (attendeeStatusChange != null && occurrenceAttendees.none {
                it.emailAddress.equals(attendeeStatusChange.email, ignoreCase = true)
            }) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee ${attendeeStatusChange.email} could not be found for event " +
                    masterEventId,
                pendingChannelResult
            )
            return
        }

        val operations = ArrayList<ContentProviderOperation>()
        val masterSelectionParts = mutableListOf(
            "${Events._ID} = ?",
            "${Events.CALENDAR_ID} = ?",
            "${Events.DELETED} != 1",
            "${Events.RRULE} = ?"
        )
        val masterSelectionArgs = mutableListOf(
            masterEventId,
            calendarId,
            currentMaster.recurrenceRule!!
        )
        if (colorChange != null) {
            if (expectedColorValue == null) {
                masterSelectionParts.add(
                    "(${Events.EVENT_COLOR} IS NULL OR ${Events.EVENT_COLOR} = 0)"
                )
            } else {
                masterSelectionParts.add("${Events.EVENT_COLOR} = ?")
                masterSelectionArgs.add(expectedColorValue.toString())
            }
            if (expectedColorKey == null) {
                masterSelectionParts.add(
                    "(${Events.EVENT_COLOR_KEY} IS NULL OR ${Events.EVENT_COLOR_KEY} = 0)"
                )
            } else {
                masterSelectionParts.add("${Events.EVENT_COLOR_KEY} = ?")
                masterSelectionArgs.add(expectedColorKey.toString())
            }
        }
        if (titleChange != null) {
            if (expectedTitle == null) {
                masterSelectionParts.add("${Events.TITLE} IS NULL")
            } else {
                masterSelectionParts.add("${Events.TITLE} = ?")
                masterSelectionArgs.add(expectedTitle)
            }
        }
        if (locationChange != null) {
            addNullableSelection(
                masterSelectionParts,
                masterSelectionArgs,
                Events.EVENT_LOCATION,
                expectedLocation
            )
        }
        if (dateRangeChange != null) {
            addNullableSelection(
                masterSelectionParts,
                masterSelectionArgs,
                Events.DTSTART,
                currentMaster.rawStartDate
            )
            addNullableSelection(
                masterSelectionParts,
                masterSelectionArgs,
                Events.EVENT_TIMEZONE,
                currentMaster.rawStartTimeZone
            )
            addNullableSelection(
                masterSelectionParts,
                masterSelectionArgs,
                Events.DURATION,
                currentMaster.duration
            )
            masterSelectionParts.add("${Events.ALL_DAY} = ?")
            masterSelectionArgs.add(if (currentMaster.allDay) "1" else "0")
        }
        operations.add(
            ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection(
                    masterSelectionParts.joinToString(" AND "),
                    masterSelectionArgs.toTypedArray()
                )
                .withExpectedCount(1)
                .build()
        )
        if (attendeeStatusChange != null) {
            operations.add(
                ContentProviderOperation.newAssertQuery(
                    CalendarContract.Attendees.CONTENT_URI
                )
                    .withSelection(
                        "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                            "${CalendarContract.Attendees.ATTENDEE_EMAIL} = ? AND " +
                            "${CalendarContract.Attendees.ATTENDEE_STATUS} = ?",
                        arrayOf(
                            masterEventId,
                            attendeeStatusChange.email,
                            attendeeStatusChange.expectedStatus.toString()
                        )
                    )
                    .withExpectedCount(1)
                    .build()
            )
        }
        if (remindersChange != null) {
            val reminderEventSelection = "${CalendarContract.Reminders.EVENT_ID} = ?"
            operations.add(
                ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
                    .withSelection(reminderEventSelection, arrayOf(masterEventId))
                    .withExpectedCount(expectedReminders!!.size)
                    .build()
            )
            expectedReminders.groupingBy { it }.eachCount().forEach { (reminder, count) ->
                operations.add(
                    ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI)
                        .withSelection(
                            "$reminderEventSelection AND " +
                                "${CalendarContract.Reminders.MINUTES} = ? AND " +
                                "${CalendarContract.Reminders.METHOD} = ?",
                            arrayOf(
                                masterEventId,
                                reminder.minutes.toString(),
                                reminder.method.toString()
                            )
                        )
                        .withExpectedCount(count)
                        .build()
                )
            }
        }
        if (resourcesChange != null) {
            val resourceSelection =
                "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                    "${CalendarContract.Attendees.ATTENDEE_TYPE} = ?"
            val resourceSelectionArgs = arrayOf(
                masterEventId,
                CalendarContract.Attendees.TYPE_RESOURCE.toString()
            )
            operations.add(
                ContentProviderOperation.newAssertQuery(
                    CalendarContract.Attendees.CONTENT_URI
                )
                    .withSelection(resourceSelection, resourceSelectionArgs)
                    .withExpectedCount(expectedResources!!.size)
                    .build()
            )
            expectedResources.groupingBy(EventResourceValue::identity).eachCount()
                .forEach { (identity, count) ->
                    val resource = expectedResources.first { it.identity() == identity }
                    val parts = mutableListOf(resourceSelection)
                    val args = mutableListOf(*resourceSelectionArgs)
                    if (resource.email == null) {
                        parts.add(
                            "(${CalendarContract.Attendees.ATTENDEE_EMAIL} IS NULL OR " +
                                "${CalendarContract.Attendees.ATTENDEE_EMAIL} = '')"
                        )
                    } else {
                        parts.add(
                            "LOWER(${CalendarContract.Attendees.ATTENDEE_EMAIL}) = ?"
                        )
                        args.add(resource.email.lowercase())
                    }
                    if (resource.name == null) {
                        parts.add(
                            "(${CalendarContract.Attendees.ATTENDEE_NAME} IS NULL OR " +
                                "${CalendarContract.Attendees.ATTENDEE_NAME} = '')"
                        )
                    } else {
                        parts.add("${CalendarContract.Attendees.ATTENDEE_NAME} = ?")
                        args.add(resource.name)
                    }
                    operations.add(
                        ContentProviderOperation.newAssertQuery(
                            CalendarContract.Attendees.CONTENT_URI
                        )
                            .withSelection(parts.joinToString(" AND "), args.toTypedArray())
                            .withExpectedCount(count)
                            .build()
                    )
                }
        }
        if (attendeesChange != null) {
            addAttendeeAssertions(
                operations,
                masterEventId,
                expectedAttendees!!
            )
        }
        val exceptionInsertIndex = operations.size
        operations.add(
            ContentProviderOperation.newInsert(exceptionUri)
                .withValues(values)
                .build()
        )
        if (exceptionColorWritePlan.eventUpdateValues.isNotEmpty()) {
            val colorValues = ContentValues().apply {
                exceptionColorWritePlan.eventUpdateValues.forEach { (column, value) ->
                    when (value) {
                        is Int -> put(column, value)
                        null -> putNull(column)
                        else -> error(
                            "Unsupported recurrence exception colour value for $column"
                        )
                    }
                }
            }
            operations.add(
                ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection("${Events._ID} = ?", arrayOf("0"))
                    .withSelectionBackReference(0, exceptionInsertIndex)
                    .withValues(colorValues)
                    .withExpectedCount(1)
                    .build()
            )
        }
        val attendeePlan = recurrenceExceptionAttendeePlan(
            hasAttendeeStatusChange = attendeeStatusChange != null,
            hasAttendeesChange = attendeesChange != null,
            hasResourcesChange = resourcesChange != null
        )
        if (attendeePlan.replaceInheritedRows) {
            // CONTENT_EXCEPTION_URI inherits the master's attendee rows on
            // Android/Samsung. Replace that inherited set before inserting
            // the requested occurrence participants; appending here creates
            // duplicate owner/guest rows and can leave two conflicting RSVP
            // values for the current user.
            operations.add(
                ContentProviderOperation.newDelete(
                    CalendarContract.Attendees.CONTENT_URI
                )
                    .withSelection(
                        "${CalendarContract.Attendees.EVENT_ID} = ?",
                        arrayOf("0")
                    )
                    .withSelectionBackReference(0, exceptionInsertIndex)
                    .build()
            )
        }
        occurrenceAttendees.forEach { attendee ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                    .withValueBackReference(
                        CalendarContract.Attendees.EVENT_ID,
                        exceptionInsertIndex
                    )
                    .withValue(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                        attendee.emailAddress
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        recurrenceSplitAttendeeRelationship(attendee)
                    )
                    .withValue(CalendarContract.Attendees.ATTENDEE_TYPE, attendee.role)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                        if (attendeeStatusChange != null &&
                            attendee.emailAddress.equals(
                                attendeeStatusChange.email,
                                ignoreCase = true
                            )
                        ) {
                            attendeeStatusChange.newStatus
                        } else {
                            attendee.attendanceStatus
                        }
                    )
                    .build()
            )
        }
        val reminderPlan = recurrenceExceptionReminderPlan(
            if (remindersChange == null) null else requestedReminders
        )
        if (reminderPlan.deleteInherited) {
            operations.add(
                ContentProviderOperation.newDelete(
                    CalendarContract.Reminders.CONTENT_URI
                )
                    .withSelection(
                        "${CalendarContract.Reminders.EVENT_ID} = ?",
                        arrayOf("0")
                    )
                    .withSelectionBackReference(0, exceptionInsertIndex)
                    .build()
            )
        }
        reminderPlan.remindersToInsert.forEach { reminder ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValueBackReference(
                        CalendarContract.Reminders.EVENT_ID,
                        exceptionInsertIndex
                    )
                    .withValue(CalendarContract.Reminders.MINUTES, reminder.minutes)
                    .withValue(CalendarContract.Reminders.METHOD, reminder.method)
                    .build()
            )
        }

        var results = try {
            contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (exception: android.content.OperationApplicationException) {
            if (attendeeStatusChange != null) {
                val latestStatus = queryAttendeeStatus(
                    contentResolver,
                    masterEventId,
                    attendeeStatusChange.email
                )
                if (latestStatus == null) {
                    finishWithError(
                        EC.NOT_FOUND,
                        "The attendee ${attendeeStatusChange.email} could not be found",
                        pendingChannelResult
                    )
                } else {
                    finishWithSuccess(
                        attendeeStatusResult(
                            if (latestStatus == attendeeStatusChange.newStatus) {
                                "alreadyCurrent"
                            } else {
                                "conflict"
                            },
                            latestStatus,
                            masterEventId,
                            diagnostics = mapOf(
                                "stage" to "occurrence.atomicBatch",
                                "batchExceptionType" to exception.javaClass.name,
                                "batchExceptionMessage" to exception.message
                            )
                        ),
                        pendingChannelResult
                    )
                }
                return
            }
            val latestMaster = queryStoredEventChangeValues(
                contentResolver,
                calendarId,
                masterEventId
            ) ?: currentMaster
            val latestMasterRange = latestMaster.dateRange
            val latestOccurrenceRange = latestMasterRange?.let {
                EventDateRangeValue(
                    startDate = originalOccurrenceStart,
                    startTimeZone = it.startTimeZone,
                    endDate = originalOccurrenceStart + (it.endDate - it.startDate),
                    endTimeZone = it.endTimeZone,
                    allDay = it.allDay
                )
            }
            val latestOccurrenceValues = latestMaster.copy(
                rawStartDate = latestOccurrenceRange?.startDate,
                rawEndDate = latestOccurrenceRange?.endDate,
                duration = null,
                rawStartTimeZone = latestOccurrenceRange?.startTimeZone,
                rawEndTimeZone = latestOccurrenceRange?.endTimeZone,
                allDay = latestOccurrenceRange?.allDay ?: latestMaster.allDay,
                recurrenceRule = null
            )
            finishWithSuccess(
                eventChangeResultForCurrent(
                    "occurrence.atomicBatch",
                    latestOccurrenceValues,
                    colorChange,
                    requestedColorValue,
                    requestedColorKey,
                    titleChange,
                    requestedTitle,
                    dateRangeChange,
                    requestedDateRange,
                    remindersChange,
                    requestedReminders,
                    attendeesChange = attendeesChange,
                    requestedAttendees = requestedAttendees,
                    resourcesChange = resourcesChange,
                    requestedResources = requestedResources,
                    locationChange = locationChange,
                    requestedLocation = requestedLocation,
                    batchFailure = exception
                ),
                pendingChannelResult
            )
            return
        } catch (exception: Exception) {
            finishWithError(
                EC.GENERIC_ERROR,
                exception.message ?: "The recurring occurrence could not be updated",
                pendingChannelResult
            )
            return
        }
        // Samsung's provider can exceptionally return an empty result array
        // for a batch which it did not apply (the binder reports a failed
        // transaction but applyBatch itself does not throw). Never index that
        // malformed response. A single retry is safe after verifying that no
        // exception row was created for this stable occurrence identity.
        var insertedEventId = results.getOrNull(exceptionInsertIndex)
            ?.uri?.lastPathSegment?.toLongOrNull()
        if (insertedEventId == null &&
            results.size <= exceptionInsertIndex &&
            queryRecurrenceExceptionEventId(
                contentResolver,
                masterEventId,
                originalOccurrenceStart
            ) == null) {
            results = try {
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            } catch (_: Exception) {
                emptyArray()
            }
            insertedEventId = results.getOrNull(exceptionInsertIndex)
                ?.uri?.lastPathSegment?.toLongOrNull()
        }
        insertedEventId = insertedEventId ?: queryRecurrenceExceptionEventId(
            contentResolver,
            masterEventId,
            originalOccurrenceStart
        )
        if (insertedEventId == null) {
            finishWithError(
                EC.GENERIC_ERROR,
                "The recurring occurrence could not be updated",
                pendingChannelResult
            )
            return
        }
        finishWithSuccess(
            if (attendeeStatusChange != null) {
                attendeeStatusResult(
                    "updated",
                    attendeeStatusChange.newStatus,
                    insertedEventId.toString()
                )
            } else {
                eventChangeResult(
                    "updated",
                    emptyList(),
                    requestedColorValue,
                    requestedColorKey,
                    requestedTitle,
                    requestedDateRange,
                    requestedReminders,
                    attendees = requestedAttendees,
                    resources = requestedResources,
                    resultingEventId = insertedEventId.toString(),
                    location = if (locationChange != null) {
                        requestedLocation
                    } else {
                        currentMaster.location
                    }
                )
            },
            pendingChannelResult
        )
    }

    private fun eventChangeResult(
        outcome: String,
        conflictingFields: List<String>,
        color: Int?,
        colorKey: Int?,
        title: String?,
        dateRange: EventDateRangeValue?,
        reminders: List<EventReminderValue>? = null,
        attendees: List<EventAttendeeValue>? = null,
        resources: List<EventResourceValue>? = null,
        resultingEventId: String? = null,
        location: String? = null,
        recurrence: EventRecurrenceValue? = null
    ): Map<String, Any?> {
        return mapOf(
            "outcome" to outcome,
            "conflictingFields" to conflictingFields,
            "resultingEventId" to resultingEventId,
            "currentValues" to mapOf(
                "color" to mapOf("color" to color, "colorKey" to colorKey),
                "title" to title,
                "location" to location,
                "dateRange" to dateRange?.toMap(),
                "reminders" to reminders?.map(EventReminderValue::toMap),
                "attendees" to attendees?.map(EventAttendeeValue::toMap),
                "resources" to resources?.map(EventResourceValue::toMap),
                "recurrence" to recurrence?.toMap()
            )
        )
    }

    private data class StoredEventChangeValues(
        val deleted: Boolean,
        val color: Int?,
        val colorKey: Int?,
        val title: String?,
        val location: String?,
        val rawStartDate: Long?,
        val rawEndDate: Long?,
        val duration: String?,
        val rawStartTimeZone: String?,
        val rawEndTimeZone: String?,
        val allDay: Boolean,
        val recurrenceRule: String?,
        val reminders: List<EventReminderValue>,
        val attendees: List<EventAttendeeValue>,
        val resources: List<EventResourceValue>
    ) {
        val dateRange: EventDateRangeValue?
            get() {
                val start = rawStartDate ?: return null
                val end = rawEndDate ?: parseDurationMillis(duration)?.let(start::plus)
                    ?: return null
                val startZone = rawStartTimeZone ?: TimeZone.getDefault().id
                return EventDateRangeValue(
                    startDate = start,
                    startTimeZone = startZone,
                    endDate = end,
                    endTimeZone = rawEndTimeZone ?: startZone,
                    allDay = allDay
                )
            }
    }

    private fun queryStoredEventChangeValues(
        contentResolver: ContentResolver?,
        calendarId: String,
        eventId: String
    ): StoredEventChangeValues? {
        val cursor = contentResolver?.query(
            Events.CONTENT_URI,
            arrayOf(
                Events.DELETED,
                Events.EVENT_COLOR,
                Events.EVENT_COLOR_KEY,
                Events.TITLE,
                Events.EVENT_LOCATION,
                Events.DTSTART,
                Events.DTEND,
                Events.DURATION,
                Events.EVENT_TIMEZONE,
                Events.EVENT_END_TIMEZONE,
                Events.ALL_DAY,
                Events.RRULE
            ),
            "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ?",
            arrayOf(eventId, calendarId),
            null
        )
        return cursor.use {
            if (it?.moveToFirst() != true) return@use null
            StoredEventChangeValues(
                deleted = it.getInt(0) == 1,
                color = if (it.isNull(1) || it.getInt(1) == 0) null else it.getInt(1),
                colorKey = if (it.isNull(2) || it.getInt(2) == 0) null else it.getInt(2),
                title = if (it.isNull(3)) null else it.getString(3),
                location = if (it.isNull(4)) null else it.getString(4),
                rawStartDate = if (it.isNull(5)) null else it.getLong(5),
                rawEndDate = if (it.isNull(6)) null else it.getLong(6),
                duration = if (it.isNull(7)) null else it.getString(7),
                rawStartTimeZone = if (it.isNull(8)) null else it.getString(8),
                rawEndTimeZone = if (it.isNull(9)) null else it.getString(9),
                allDay = it.getInt(10) == 1,
                recurrenceRule = if (it.isNull(11)) null else it.getString(11),
                reminders = retrieveReminders(eventId, contentResolver)
                    .map { reminder ->
                        EventReminderValue(reminder.minutes, reminder.method)
                    }
                    .sortedWith(
                        compareBy(EventReminderValue::minutes, EventReminderValue::method)
                    ),
                attendees = retrieveAttendeeValues(eventId, contentResolver),
                resources = retrieveResourceValues(eventId, contentResolver)
            )
        }
    }

    private fun addNullableSelection(
        selectionParts: MutableList<String>,
        selectionArgs: MutableList<String>,
        column: String,
        value: Any?
    ) {
        if (value == null) {
            selectionParts.add("$column IS NULL")
        } else {
            selectionParts.add("$column = ?")
            selectionArgs.add(value.toString())
        }
    }

    private fun eventChangeResultForCurrent(
        rejectionStage: String,
        current: StoredEventChangeValues,
        colorChange: Map<*, *>?,
        requestedColor: Int?,
        requestedColorKey: Int?,
        titleChange: Map<*, *>?,
        requestedTitle: String?,
        dateRangeChange: Map<*, *>?,
        requestedDateRange: EventDateRangeValue?,
        remindersChange: Map<*, *>? = null,
        requestedReminders: List<EventReminderValue>? = null,
        attendeesChange: Map<*, *>? = null,
        requestedAttendees: List<EventAttendeeValue>? = null,
        resourcesChange: Map<*, *>? = null,
        requestedResources: List<EventResourceValue>? = null,
        locationChange: Map<*, *>? = null,
        requestedLocation: String? = null,
        recurrenceChange: Map<*, *>? = null,
        requestedRecurrence: EventRecurrenceValue? = null,
        batchFailure: Exception? = null,
        diagnosticContext: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val conflictingFields = mutableListOf<String>()
        if (colorChange != null &&
            (current.color != requestedColor || current.colorKey != requestedColorKey)) {
            conflictingFields.add("color")
        }
        if (titleChange != null && current.title != requestedTitle) {
            conflictingFields.add("title")
        }
        if (dateRangeChange != null && current.dateRange != requestedDateRange) {
            conflictingFields.add("dateRange")
        }
        if (remindersChange != null && current.reminders != requestedReminders) {
            conflictingFields.add("reminders")
        }
        if (attendeesChange != null && !sameAttendeeValues(current.attendees, requestedAttendees!!)) {
            conflictingFields.add("attendees")
        }
        if (resourcesChange != null && !sameResourceValues(current.resources, requestedResources!!)) {
            conflictingFields.add("resources")
        }
        if (locationChange != null && current.location != requestedLocation) {
            conflictingFields.add("location")
        }
        val currentRecurrence = EventRecurrenceValue(
            recurrenceRuleMap(current.recurrenceRule),
            current.recurrenceRule
        )
        if (recurrenceChange != null && currentRecurrence.rule != requestedRecurrence?.rule) {
            conflictingFields.add("recurrence")
        }
        val response = eventChangeResult(
            if (conflictingFields.isEmpty()) "alreadyCurrent" else "conflict",
            conflictingFields,
            current.color,
            current.colorKey,
            current.title,
            current.dateRange,
            current.reminders,
            attendees = current.attendees,
            resources = current.resources,
            location = current.location,
            recurrence = currentRecurrence
        )
        val diagnostics = calendarRejectionDiagnostics(
            enabled = ((_context?.applicationInfo?.flags ?: 0) and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            titles = listOf(current.title, titleChange?.get("expected") as? String,
                requestedTitle),
            stage = rejectionStage,
            batchFailure = batchFailure
        ) {
            val changes = mapOf(
                "color" to colorChange, "title" to titleChange,
                "location" to locationChange, "dateRange" to dateRangeChange,
                "reminders" to remindersChange, "attendees" to attendeesChange,
                "resources" to resourcesChange, "recurrence" to recurrenceChange
            ).filterValues { it != null }
            val expectedColor = colorChange?.get("expected") as? Map<*, *>
            // Use the same typed comparisons as the native preflight guards.
            // After a batch failure these describe the RE-READ, not necessarily
            // the values at the instant Android rejected an assertion.
            val matches = mapOf(
                "color" to (current.color == (expectedColor?.get("color") as? Number)?.toInt() &&
                    current.colorKey == (expectedColor?.get("colorKey") as? Number)?.toInt()),
                "title" to (current.title == titleChange?.get("expected")),
                "location" to (current.location == locationChange?.get("expected")),
                "dateRange" to (current.dateRange == parseEventDateRangeValue(dateRangeChange?.get("expected"))),
                "reminders" to (current.reminders == parseEventReminderValues(remindersChange?.get("expected"))),
                "attendees" to sameAttendeeValues(current.attendees,
                    parseEventAttendeeValues(attendeesChange?.get("expected")) ?: emptyList()),
                "resources" to sameResourceValues(current.resources,
                    parseEventResourceValues(resourcesChange?.get("expected")) ?: emptyList()),
                "recurrence" to (currentRecurrence.rule ==
                    parseEventRecurrenceValue(recurrenceChange?.get("expected"))?.rule)
            ).filterKeys { it in changes }
            mapOf(
                "nativeChanges" to changes,
                "expectedMatchesAtRead" to matches,
                "expectedMismatchesAtRead" to matches.filterValues { !it }.keys.toList(),
                "comparisonRead" to if (batchFailure == null) "preflight" else "afterBatchFailure",
                "comparisonValues" to response["currentValues"],
                // Some callers synthesize an occurrence view from its master.
                // Do not describe these fields as an independent raw-row read.
                "comparisonStorage" to mapOf(
                    "deleted" to current.deleted, "dtstart" to current.rawStartDate,
                    "dtend" to current.rawEndDate, "duration" to current.duration,
                    "startTimeZone" to current.rawStartTimeZone,
                    "endTimeZone" to current.rawEndTimeZone,
                    "allDay" to current.allDay, "rrule" to current.recurrenceRule
                ),
                "context" to diagnosticContext
            )
        }
        return if (diagnostics == null) response else response + ("diagnostics" to diagnostics)
    }

    private fun retrieveAttendeeValues(
        eventId: String,
        contentResolver: ContentResolver?
    ): List<EventAttendeeValue> {
        val cursor = contentResolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL,
                CalendarContract.Attendees.ATTENDEE_TYPE
            ),
            "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                "${CalendarContract.Attendees.ATTENDEE_TYPE} != ? AND " +
                "(${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} IS NULL OR " +
                "${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} != ?)",
            arrayOf(
                eventId,
                CalendarContract.Attendees.TYPE_RESOURCE.toString(),
                CalendarContract.Attendees.RELATIONSHIP_ORGANIZER.toString()
            ),
            null
        )
        return cursor.use {
            val attendees = mutableListOf<EventAttendeeValue>()
            while (it?.moveToNext() == true) {
                val name = if (it.isNull(0)) null else it.getString(0)?.trim()?.ifEmpty { null }
                val email = if (it.isNull(1)) null else it.getString(1)?.trim()?.ifEmpty { null }
                val role = if (it.isNull(2)) {
                    CalendarContract.Attendees.TYPE_REQUIRED
                } else {
                    it.getInt(2)
                }
                if (email != null) attendees.add(EventAttendeeValue(name, email, role))
            }
            attendees.distinctBy(EventAttendeeValue::identity)
                .sortedBy(EventAttendeeValue::identity)
        }
    }

    private fun sameAttendeeValues(
        first: List<EventAttendeeValue>,
        second: List<EventAttendeeValue>
    ): Boolean = first.map(EventAttendeeValue::identity).toSet() ==
        second.map(EventAttendeeValue::identity).toSet()

    private fun addAttendeeChangeOperations(
        operations: MutableList<ContentProviderOperation>,
        eventId: String,
        eventIdNumber: Long,
        expected: List<EventAttendeeValue>,
        requested: List<EventAttendeeValue>
    ) {
        addAttendeeAssertions(operations, eventId, expected)
        val attendeeSelection =
            "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                "${CalendarContract.Attendees.ATTENDEE_TYPE} != ? AND " +
                "(${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} IS NULL OR " +
                "${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} != ?)"
        val attendeeSelectionArgs = arrayOf(
            eventId,
            CalendarContract.Attendees.TYPE_RESOURCE.toString(),
            CalendarContract.Attendees.RELATIONSHIP_ORGANIZER.toString()
        )

        val expectedByIdentity = expected.associateBy(EventAttendeeValue::identity)
        val requestedByIdentity = requested.associateBy(EventAttendeeValue::identity)
        expectedByIdentity.keys.minus(requestedByIdentity.keys).forEach { identity ->
            val attendee = expectedByIdentity.getValue(identity)
            operations.add(
                ContentProviderOperation.newDelete(CalendarContract.Attendees.CONTENT_URI)
                    .withSelection(
                        "$attendeeSelection AND " +
                            "LOWER(${CalendarContract.Attendees.ATTENDEE_EMAIL}) = ? AND " +
                            "${CalendarContract.Attendees.ATTENDEE_TYPE} = ?",
                        arrayOf(
                            *attendeeSelectionArgs,
                            attendee.email.lowercase(),
                            attendee.role.toString()
                        )
                    )
                    .withExpectedCount(1)
                    .build()
            )
        }
        requestedByIdentity.keys.minus(expectedByIdentity.keys).forEach { identity ->
            val attendee = requestedByIdentity.getValue(identity)
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                    .withValue(CalendarContract.Attendees.EVENT_ID, eventIdNumber)
                    .withValue(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
                    .withValue(CalendarContract.Attendees.ATTENDEE_EMAIL, attendee.email)
                    .withValue(CalendarContract.Attendees.ATTENDEE_TYPE, attendee.role)
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                    )
                    .build()
            )
        }
    }

    private fun addAttendeeAssertions(
        operations: MutableList<ContentProviderOperation>,
        eventId: String,
        expected: List<EventAttendeeValue>
    ) {
        val attendeeSelection =
            "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                "${CalendarContract.Attendees.ATTENDEE_TYPE} != ? AND " +
                "(${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} IS NULL OR " +
                "${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} != ?)"
        val attendeeSelectionArgs = arrayOf(
            eventId,
            CalendarContract.Attendees.TYPE_RESOURCE.toString(),
            CalendarContract.Attendees.RELATIONSHIP_ORGANIZER.toString()
        )
        operations.add(
            ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                .withSelection(attendeeSelection, attendeeSelectionArgs)
                .withExpectedCount(expected.size)
                .build()
        )
        expected.forEach { attendee ->
            operations.add(
                ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                    .withSelection(
                        "$attendeeSelection AND " +
                            "LOWER(${CalendarContract.Attendees.ATTENDEE_EMAIL}) = ? AND " +
                            "${CalendarContract.Attendees.ATTENDEE_TYPE} = ?",
                        arrayOf(
                            *attendeeSelectionArgs,
                            attendee.email.lowercase(),
                            attendee.role.toString()
                        )
                    )
                    .withExpectedCount(1)
                    .build()
            )
        }

    }

    private fun attendeesWithPeople(
        attendees: List<Attendee>,
        people: List<EventAttendeeValue>,
        ownerEmail: String?,
        organizerEmail: String?
    ): MutableList<Attendee> {
        val preserved = attendees.filter {
            it.role == CalendarContract.Attendees.TYPE_RESOURCE || it.isOrganizer == true
        }.toMutableList()
        preserved.addAll(
            people.map { person ->
                val existing = attendees.firstOrNull {
                    it.isOrganizer != true &&
                        it.role != CalendarContract.Attendees.TYPE_RESOURCE &&
                        it.emailAddress.equals(person.email, ignoreCase = true)
                }
                Attendee(
                    person.email,
                    person.name,
                    person.role,
                    existing?.attendanceStatus
                        ?: CalendarContract.Attendees.ATTENDEE_STATUS_INVITED,
                    false,
                    existing?.isCurrentUser ?: false
                )
            }
        )
        return attendeesForOwnedEventWrite(
            preserved,
            ownerEmail,
            eventIsOwnedByCalendarOwner(organizerEmail, ownerEmail)
        ).toMutableList()
    }

    private fun retrieveResourceValues(
        eventId: String,
        contentResolver: ContentResolver?
    ): List<EventResourceValue> {
        val cursor = contentResolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL
            ),
            "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                "${CalendarContract.Attendees.ATTENDEE_TYPE} = ?",
            arrayOf(eventId, CalendarContract.Attendees.TYPE_RESOURCE.toString()),
            null
        )
        return cursor.use {
            val resources = mutableListOf<EventResourceValue>()
            while (it?.moveToNext() == true) {
                val name = if (it.isNull(0)) null else it.getString(0)?.trim()?.ifEmpty { null }
                val email = if (it.isNull(1)) null else it.getString(1)?.trim()?.ifEmpty { null }
                if (name != null || email != null) resources.add(EventResourceValue(name, email))
            }
            resources.distinctBy(EventResourceValue::identity)
                .sortedBy(EventResourceValue::identity)
        }
    }

    private fun sameResourceValues(
        first: List<EventResourceValue>,
        second: List<EventResourceValue>
    ): Boolean = first.map(EventResourceValue::identity).toSet() ==
        second.map(EventResourceValue::identity).toSet()

    private fun attendeesWithResources(
        attendees: List<Attendee>,
        resources: List<EventResourceValue>
    ): MutableList<Attendee> {
        val result = attendees.filter {
            it.role != CalendarContract.Attendees.TYPE_RESOURCE
        }.toMutableList()
        result.addAll(
            resources.map { resource ->
                Attendee(
                    resource.email ?: "",
                    resource.name,
                    CalendarContract.Attendees.TYPE_RESOURCE,
                    CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
                    false,
                    false
                )
            }
        )
        return result
    }

    private fun addResourceAssertions(
        operations: MutableList<ContentProviderOperation>,
        eventId: String,
        expectedResources: List<EventResourceValue>
    ) {
        val resourceSelection =
            "${CalendarContract.Attendees.EVENT_ID} = ? AND " +
                "${CalendarContract.Attendees.ATTENDEE_TYPE} = ?"
        val resourceSelectionArgs = arrayOf(
            eventId,
            CalendarContract.Attendees.TYPE_RESOURCE.toString()
        )
        operations.add(
            ContentProviderOperation.newAssertQuery(CalendarContract.Attendees.CONTENT_URI)
                .withSelection(resourceSelection, resourceSelectionArgs)
                .withExpectedCount(expectedResources.size)
                .build()
        )
        expectedResources.groupingBy(EventResourceValue::identity).eachCount()
            .forEach { (identity, count) ->
                val resource = expectedResources.first { it.identity() == identity }
                val parts = mutableListOf(resourceSelection)
                val args = mutableListOf(*resourceSelectionArgs)
                if (resource.email == null) {
                    parts.add(
                        "(${CalendarContract.Attendees.ATTENDEE_EMAIL} IS NULL OR " +
                            "${CalendarContract.Attendees.ATTENDEE_EMAIL} = '')"
                    )
                } else {
                    parts.add("LOWER(${CalendarContract.Attendees.ATTENDEE_EMAIL}) = ?")
                    args.add(resource.email.lowercase())
                }
                if (resource.name == null) {
                    parts.add(
                        "(${CalendarContract.Attendees.ATTENDEE_NAME} IS NULL OR " +
                            "${CalendarContract.Attendees.ATTENDEE_NAME} = '')"
                    )
                } else {
                    parts.add("${CalendarContract.Attendees.ATTENDEE_NAME} = ?")
                    args.add(resource.name)
                }
                operations.add(
                    ContentProviderOperation.newAssertQuery(
                        CalendarContract.Attendees.CONTENT_URI
                    )
                        .withSelection(parts.joinToString(" AND "), args.toTypedArray())
                        .withExpectedCount(count)
                        .build()
                )
            }
    }

    fun createOrUpdateEvent(
        calendarId: String,
        event: Event?,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (arePermissionsGranted()) {
            if (event == null) {
                finishWithError(
                    EC.GENERIC_ERROR,
                    EM.CREATE_EVENT_ARGUMENTS_NOT_VALID_MESSAGE,
                    pendingChannelResult
                )
                return
            }

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "Couldn't retrieve the Calendar with ID $calendarId",
                    pendingChannelResult
                )
                return
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val values = buildEventContentValues(event, calendarId)
            val existingEventId = event.eventId?.toLongOrNull()
            val eventIsOwnedByCurrentUser = existingEventId == null ||
                eventIsOwnedByCalendarOwner(
                    queryRecurrenceMasterOwnership(contentResolver, existingEventId).organizer,
                    calendar.ownerAccount
                )

            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                uiThreadHandler.post {
                    finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                }
            }

            val job: Job
            var eventId: Long? = existingEventId
            if (eventId == null) {
                val uri = contentResolver?.insert(Events.CONTENT_URI, values)
                // get the event ID that is the last element in the Uri
                eventId = java.lang.Long.parseLong(uri?.lastPathSegment!!)
                job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                    insertAttendees(
                        attendeesForOwnedEventWrite(
                            event.attendees,
                            calendar.ownerAccount,
                            eventIsOwnedByCurrentUser
                        ),
                        eventId,
                        contentResolver
                    )
                    insertReminders(event.reminders, eventId, contentResolver)
                }
            } else {
                job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                    contentResolver?.update(
                        ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                        values,
                        null,
                        null
                    )
                    val existingAttendees =
                        retrieveAttendees(calendar, eventId.toString(), contentResolver)
                    val existingOwner = existingAttendees.firstOrNull {
                        it.emailAddress.equals(calendar.ownerAccount, ignoreCase = true)
                    }
                    val requestedAttendees = if (event.attendees.isNotEmpty() &&
                        existingOwner != null && event.attendees.none {
                            it.emailAddress.equals(calendar.ownerAccount, ignoreCase = true)
                        }
                    ) {
                        event.attendees + existingOwner
                    } else {
                        event.attendees
                    }
                    val attendeesForWrite = attendeesForOwnedEventWrite(
                        requestedAttendees,
                        calendar.ownerAccount,
                        eventIsOwnedByCurrentUser
                    )
                    val attendeesToDelete =
                        if (attendeesForWrite.isNotEmpty()) existingAttendees.filter { existingAttendee -> attendeesForWrite.all { !it.emailAddress.equals(existingAttendee.emailAddress, ignoreCase = true) } } else existingAttendees
                    for (attendeeToDelete in attendeesToDelete) {
                        deleteAttendee(eventId, attendeeToDelete, contentResolver)
                    }

                    val attendeesToInsert =
                        attendeesForWrite.filter { requested -> existingAttendees.all { existing -> !existing.emailAddress.equals(requested.emailAddress, ignoreCase = true) } }
                    insertAttendees(attendeesToInsert, eventId, contentResolver)
                    deleteExistingReminders(contentResolver, eventId)
                    insertReminders(event.reminders, eventId, contentResolver!!)

                    val existingSelfAttendee = existingAttendees.firstOrNull {
                        it.emailAddress.equals(calendar.ownerAccount, ignoreCase = true)
                    }
                    val newSelfAttendee = attendeesForWrite.firstOrNull {
                        it.emailAddress.equals(calendar.ownerAccount, ignoreCase = true)
                    }
                    if (existingSelfAttendee != null && newSelfAttendee != null &&
                        newSelfAttendee.attendanceStatus != null &&
                        existingSelfAttendee.attendanceStatus != newSelfAttendee.attendanceStatus
                    ) {
                        updateAttendeeStatusRow(eventId, newSelfAttendee, contentResolver)
                    }
                }
            }
            job.invokeOnCompletion { cause ->
                if (cause == null) {
                    uiThreadHandler.post {
                        finishWithSuccess(eventId.toString(), pendingChannelResult)
                    }
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                CREATE_OR_UPDATE_EVENT_REQUEST_CODE,
                calendarId
            )
            parameters.event = event
            requestPermissions(parameters)
        }
    }

    private fun deleteExistingReminders(contentResolver: ContentResolver?, eventId: Long) {
        val cursor = CalendarContract.Reminders.query(
            contentResolver, eventId, arrayOf(
                CalendarContract.Reminders._ID
            )
        )
        while (cursor != null && cursor.moveToNext()) {
            var reminderUri: Uri? = null
            val reminderId = cursor.getLong(0)
            if (reminderId > 0) {
                reminderUri =
                    ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI, reminderId)
            }
            if (reminderUri != null) {
                contentResolver?.delete(reminderUri, null, null)
            }
        }
        cursor?.close()
    }

    @SuppressLint("MissingPermission")
    private fun insertReminders(
        reminders: List<Reminder>,
        eventId: Long?,
        contentResolver: ContentResolver
    ) {
        if (reminders.isEmpty()) {
            return
        }
        val remindersContentValues = reminders.map {
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, it.minutes)
                put(CalendarContract.Reminders.METHOD, it.method)
            }
        }.toTypedArray()
        contentResolver.bulkInsert(CalendarContract.Reminders.CONTENT_URI, remindersContentValues)
    }

    private fun buildEventContentValues(event: Event, calendarId: String): ContentValues {
        val values = ContentValues()

        values.put(Events.ALL_DAY, if (event.eventAllDay) 1 else 0)
        values.put(Events.DTSTART, event.eventStartDate!!)
        values.put(Events.EVENT_TIMEZONE, getTimeZone(event.eventStartTimeZone).id)
        values.put(Events.TITLE, event.eventTitle)
        values.put(Events.DESCRIPTION, event.eventDescription)
        values.put(Events.EVENT_LOCATION, event.eventLocation)
        values.put(Events.CUSTOM_APP_URI, event.eventURL)
        values.put(Events.CALENDAR_ID, calendarId)
        values.put(Events.AVAILABILITY, getAvailability(event.availability))
        var status: Int? = getEventStatus(event.eventStatus)
        if (status != null) {
            values.put(Events.STATUS, status)
        }

        var duration: String? = null
        var end: Long? = null
        var endTimeZone: String? = null

        if (event.recurrenceRule != null) {
            val recurrenceRuleParams = buildRecurrenceRuleParams(event.recurrenceRule!!)
            values.put(Events.RRULE, recurrenceRuleParams)
            val difference = event.eventEndDate!!.minus(event.eventStartDate!!)
            val rawDuration = difference.toDuration(DurationUnit.MILLISECONDS)
            rawDuration.toComponents { days, hours, minutes, seconds, _ ->
                if (days > 0 || hours > 0 || minutes > 0 || seconds > 0) duration = "P"
                if (days > 0) duration = duration.plus("${days}D")
                if (hours > 0 || minutes > 0 || seconds > 0) duration = duration.plus("T")
                if (hours > 0) duration = duration.plus("${hours}H")
                if (minutes > 0) duration = duration.plus("${minutes}M")
                if (seconds > 0) duration = duration.plus("${seconds}S")
            }
        } else {
            end = event.eventEndDate!!
            endTimeZone = getTimeZone(event.eventEndTimeZone).id
        }
        values.put(Events.DTEND, end)
        values.put(Events.EVENT_END_TIMEZONE, endTimeZone)
        values.put(Events.DURATION, duration)
        values.put(Events.EVENT_COLOR_KEY, event.eventColorKey)
        values.put(Events.EVENT_COLOR, event.eventColor)
        return values
    }

    private fun getTimeZone(timeZoneString: String?): TimeZone {
        val deviceTimeZone: TimeZone = java.util.Calendar.getInstance().timeZone
        var timeZone = TimeZone.getTimeZone(timeZoneString ?: deviceTimeZone.id)

        // Invalid time zone names defaults to GMT so update that to be device's time zone
        if (timeZone.id == "GMT" && timeZoneString != "GMT") {
            timeZone = TimeZone.getTimeZone(deviceTimeZone.id)
        }

        return timeZone
    }

    private fun getAvailability(availability: Availability?): Int? = when (availability) {
        Availability.BUSY -> Events.AVAILABILITY_BUSY
        Availability.FREE -> Events.AVAILABILITY_FREE
        Availability.TENTATIVE -> Events.AVAILABILITY_TENTATIVE
        else -> null
    }

    private fun getEventStatus(eventStatus: EventStatus?): Int? = when (eventStatus) {
        EventStatus.CONFIRMED -> Events.STATUS_CONFIRMED
        EventStatus.TENTATIVE -> Events.STATUS_TENTATIVE
        EventStatus.CANCELED -> Events.STATUS_CANCELED
        else -> null
    }

    @SuppressLint("MissingPermission")
    private fun insertAttendees(
        attendees: List<Attendee>,
        eventId: Long?,
        contentResolver: ContentResolver?
    ) {
        if (attendees.isEmpty()) {
            return
        }

        val attendeesValues = attendees.map {
            ContentValues().apply {
                put(CalendarContract.Attendees.ATTENDEE_NAME, it.name)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, it.emailAddress)
                put(
                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                    if (it.isOrganizer == true) {
                        CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                    } else {
                        CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                    }
                )
                put(CalendarContract.Attendees.ATTENDEE_TYPE, it.role)
                put(
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                    it.attendanceStatus
                )
                put(CalendarContract.Attendees.EVENT_ID, eventId)
            }
        }.toTypedArray()

        contentResolver?.bulkInsert(CalendarContract.Attendees.CONTENT_URI, attendeesValues)
    }

    @SuppressLint("MissingPermission")
    private fun deleteAttendee(
        eventId: Long,
        attendee: Attendee,
        contentResolver: ContentResolver?
    ) {
        val selection =
            "(" + CalendarContract.Attendees.EVENT_ID + " = ?) AND (" + CalendarContract.Attendees.ATTENDEE_EMAIL + " = ?)"
        val selectionArgs = arrayOf(eventId.toString() + "", attendee.emailAddress)
        contentResolver?.delete(CalendarContract.Attendees.CONTENT_URI, selection, selectionArgs)

    }

    private fun updateAttendeeStatusRow(
        eventId: Long,
        attendee: Attendee,
        contentResolver: ContentResolver?
    ) {
        val selection =
            "(" + CalendarContract.Attendees.EVENT_ID + " = ?) AND (" + CalendarContract.Attendees.ATTENDEE_EMAIL + " = ?)"
        val selectionArgs = arrayOf(eventId.toString() + "", attendee.emailAddress)
        val values = ContentValues()
        values.put(CalendarContract.Attendees.ATTENDEE_STATUS, attendee.attendanceStatus)
        contentResolver?.update(
            CalendarContract.Attendees.CONTENT_URI,
            values,
            selection,
            selectionArgs
        )
    }

    fun deleteEvent(
        calendarId: String,
        eventId: String,
        pendingChannelResult: MethodChannel.Result,
        startDate: Long? = null,
        endDate: Long? = null,
        followingInstances: Boolean? = null
    ) {
        if (arePermissionsGranted()) {
            val existingCal = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (existingCal == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The calendar with the ID $calendarId could not be found",
                    pendingChannelResult
                )
                return
            }

            if (existingCal.isReadOnly) {
                finishWithError(
                    EC.NOT_ALLOWED,
                    "Calendar with ID $calendarId is read-only",
                    pendingChannelResult
                )
                return
            }

            val eventIdNumber = eventId.toLongOrNull()
            if (eventIdNumber == null) {
                finishWithError(
                    EC.INVALID_ARGUMENT,
                    EM.EVENT_ID_CANNOT_BE_NULL_ON_DELETION_MESSAGE,
                    pendingChannelResult
                )
                return
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            if (startDate == null && endDate == null && followingInstances == null) { // Delete all instances
                val eventsUriWithId = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                val deleteSucceeded = contentResolver?.delete(eventsUriWithId, null, null) ?: 0
                finishWithSuccess(deleteSucceeded > 0, pendingChannelResult)
            } else {
                if (!followingInstances!!) { // Only this instance
                    val exceptionUriWithId =
                        ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventIdNumber)
                    val values = ContentValues()
                    val instanceCursor = CalendarContract.Instances.query(
                        contentResolver,
                        Cst.EVENT_INSTANCE_DELETION,
                        startDate!!,
                        endDate!!
                    )

                    while (instanceCursor.moveToNext()) {
                        val foundEventID =
                            instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)

                        if (eventIdNumber == foundEventID) {
                            values.put(
                                Events.ORIGINAL_INSTANCE_TIME,
                                instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_BEGIN_INDEX)
                            )
                            values.put(Events.STATUS, Events.STATUS_CANCELED)
                        }
                    }

                    val deleteSucceeded = contentResolver?.insert(exceptionUriWithId, values)
                    instanceCursor.close()
                    finishWithSuccess(deleteSucceeded != null, pendingChannelResult)
                } else { // This and following instances
                    val eventsUriWithId =
                        ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                    val values = ContentValues()
                    val instanceCursor = CalendarContract.Instances.query(
                        contentResolver,
                        Cst.EVENT_INSTANCE_DELETION,
                        startDate!!,
                        endDate!!
                    )

                    while (instanceCursor.moveToNext()) {
                        val foundEventID =
                            instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)

                        if (eventIdNumber == foundEventID) {
                            val newRule =
                                Rrule(instanceCursor.getString(Cst.EVENT_INSTANCE_DELETION_RRULE_INDEX))
                            val lastDate =
                                instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_LAST_DATE_INDEX)

                            if (lastDate > 0 && newRule.count != null && newRule.count > 0) { // Update occurrence rule
                                val cursor = CalendarContract.Instances.query(
                                    contentResolver,
                                    Cst.EVENT_INSTANCE_DELETION,
                                    startDate,
                                    lastDate
                                )
                                while (cursor.moveToNext()) {
                                    if (eventIdNumber == cursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)) {
                                        newRule.count--
                                    }
                                }
                                cursor.close()
                            } else { // Indefinite and specified date rule
                                val cursor = CalendarContract.Instances.query(
                                    contentResolver,
                                    Cst.EVENT_INSTANCE_DELETION,
                                    startDate - DateUtils.YEAR_IN_MILLIS,
                                    startDate - 1
                                )
                                var lastRecurrenceDate: Long? = null

                                while (cursor.moveToNext()) {
                                    if (eventIdNumber == cursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)) {
                                        lastRecurrenceDate =
                                            cursor.getLong(Cst.EVENT_INSTANCE_DELETION_END_INDEX)
                                    }
                                }

                                if (lastRecurrenceDate != null) {
                                    newRule.until = DateTime(lastRecurrenceDate)
                                } else {
                                    newRule.until = DateTime(startDate - 1)
                                }
                                cursor.close()
                            }

                            values.put(Events.RRULE, newRule.toString())
                            contentResolver?.update(eventsUriWithId, values, null, null)
                            finishWithSuccess(true, pendingChannelResult)
                        }
                    }
                    instanceCursor.close()
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                DELETE_EVENT_REQUEST_CODE,
                calendarId
            )
            parameters.eventId = eventId
            requestPermissions(parameters)
        }
    }

    private fun arePermissionsGranted(): Boolean {
        if (atLeastAPI(23) && _binding != null) {
            val writeCalendarPermissionGranted = _binding!!.activity.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val readCalendarPermissionGranted = _binding!!.activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            return writeCalendarPermissionGranted && readCalendarPermissionGranted
        }

        return true
    }

    private fun requestPermissions(parameters: CalendarMethodsParametersCacheModel) {
        val requestCode: Int = generateUniqueRequestCodeAndCacheParameters(parameters)
        requestPermissions(requestCode)
    }

    private fun requestPermissions(requestCode: Int) {
        if (atLeastAPI(23)) {
            _binding!!.activity.requestPermissions(
                arrayOf(
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CALENDAR
                ), requestCode
            )
        }
    }

    private fun parseCalendarRow(cursor: Cursor?): Calendar? {
        if (cursor == null) {
            return null
        }

        val calId = cursor.getLong(Cst.CALENDAR_PROJECTION_ID_INDEX)
        val displayName = cursor.getString(Cst.CALENDAR_PROJECTION_DISPLAY_NAME_INDEX)
        val accessLevel = cursor.getInt(Cst.CALENDAR_PROJECTION_ACCESS_LEVEL_INDEX)
        val calendarColor = cursor.getInt(Cst.CALENDAR_PROJECTION_COLOR_INDEX)
        val accountName = cursor.getString(Cst.CALENDAR_PROJECTION_ACCOUNT_NAME_INDEX)
        val accountType = cursor.getString(Cst.CALENDAR_PROJECTION_ACCOUNT_TYPE_INDEX)
        val ownerAccount = cursor.getString(Cst.CALENDAR_PROJECTION_OWNER_ACCOUNT_INDEX)

        val calendar = Calendar(
            calId.toString(),
            displayName,
            calendarColor,
            accountName,
            accountType,
            ownerAccount
        )

        calendar.isReadOnly = isCalendarReadOnly(accessLevel)
        calendar.accessLevel = accessLevel
        calendar.maxReminders = cursor.nullableInt(Cst.CALENDAR_PROJECTION_MAX_REMINDERS_INDEX)
        calendar.allowedReminderMethods = parseCalendarCapabilityValues(
            cursor.nullableString(Cst.CALENDAR_PROJECTION_ALLOWED_REMINDERS_INDEX)
        )
        calendar.allowedAvailabilities = parseCalendarAvailabilityValues(
            cursor.nullableString(Cst.CALENDAR_PROJECTION_ALLOWED_AVAILABILITY_INDEX)
        )
        calendar.allowedAttendeeTypes = parseCalendarCapabilityValues(
            cursor.nullableString(Cst.CALENDAR_PROJECTION_ALLOWED_ATTENDEE_TYPES_INDEX)
        )
        calendar.canModifyTimeZone =
            cursor.nullableBoolean(Cst.CALENDAR_PROJECTION_CAN_MODIFY_TIME_ZONE_INDEX)
        calendar.canOrganizerRespond =
            cursor.nullableBoolean(Cst.CALENDAR_PROJECTION_CAN_ORGANIZER_RESPOND_INDEX)
        calendar.isVisible = cursor.nullableBoolean(Cst.CALENDAR_PROJECTION_VISIBLE_INDEX)
        calendar.isSyncEnabled = cursor.nullableBoolean(Cst.CALENDAR_PROJECTION_SYNC_EVENTS_INDEX)
        calendar.timeZone = cursor.nullableString(Cst.CALENDAR_PROJECTION_TIME_ZONE_INDEX)
        calendar.location = cursor.nullableString(Cst.CALENDAR_PROJECTION_LOCATION_INDEX)
        calendar.colorKey = cursor.nullableString(Cst.CALENDAR_PROJECTION_COLOR_KEY_INDEX)
        if (atLeastAPI(17)) {
            val isPrimary = cursor.getString(Cst.CALENDAR_PROJECTION_IS_PRIMARY_INDEX)
            calendar.isDefault = isPrimary == "1"
        } else {
            calendar.isDefault = false
        }
        return calendar
    }

    private fun Cursor.nullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.nullableBoolean(index: Int): Boolean? =
        nullableInt(index)?.let { it != 0 }

    private fun Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun parseEvent(calendarId: String, cursor: Cursor?): Event? {
        if (cursor == null) {
            return null
        }
        val eventId = cursor.getLong(Cst.EVENT_PROJECTION_ID_INDEX)
        val title = cursor.getString(Cst.EVENT_PROJECTION_TITLE_INDEX)
        val description = cursor.getString(Cst.EVENT_PROJECTION_DESCRIPTION_INDEX)
        val begin = cursor.getLong(Cst.EVENT_PROJECTION_BEGIN_INDEX)
        val end = resolveInstanceEndMillis(
            start = begin,
            rawEnd = cursor.getLong(Cst.EVENT_PROJECTION_END_INDEX),
            duration = cursor.getString(Cst.EVENT_PROJECTION_DURATION_INDEX)
        )
        val recurringRule = cursor.getString(Cst.EVENT_PROJECTION_RECURRING_RULE_INDEX)
        val allDay = cursor.getInt(Cst.EVENT_PROJECTION_ALL_DAY_INDEX) > 0
        val location = cursor.getString(Cst.EVENT_PROJECTION_EVENT_LOCATION_INDEX)
        val url = cursor.getString(Cst.EVENT_PROJECTION_CUSTOM_APP_URI_INDEX)
        val startTimeZone = cursor.getString(Cst.EVENT_PROJECTION_START_TIMEZONE_INDEX)
        val endTimeZone = cursor.getString(Cst.EVENT_PROJECTION_END_TIMEZONE_INDEX)
        val availability = parseAvailability(cursor.getInt(Cst.EVENT_PROJECTION_AVAILABILITY_INDEX))
        val eventStatus = parseEventStatus(cursor.getInt(Cst.EVENT_PROJECTION_STATUS_INDEX))
        val eventColor = cursor.getInt(Cst.EVENT_PROJECTION_EVENT_COLOR_INDEX)
        val eventColorKey = cursor.getInt(Cst.EVENT_PROJECTION_EVENT_COLOR_KEY_INDEX)
        val originalEventId = if (cursor.isNull(Cst.EVENT_PROJECTION_ORIGINAL_ID_INDEX)) {
            null
        } else {
            cursor.getString(Cst.EVENT_PROJECTION_ORIGINAL_ID_INDEX)
        }
        val originalStartDate =
            if (cursor.isNull(Cst.EVENT_PROJECTION_ORIGINAL_INSTANCE_TIME_INDEX)) {
                null
            } else {
                cursor.getLong(Cst.EVENT_PROJECTION_ORIGINAL_INSTANCE_TIME_INDEX)
            }
        val syncId = if (cursor.isNull(Cst.EVENT_PROJECTION_SYNC_ID_INDEX)) {
            null
        } else {
            cursor.getString(Cst.EVENT_PROJECTION_SYNC_ID_INDEX)
        }
        val event = Event()
        event.eventTitle = title ?: "New Event"
        event.eventId = eventId.toString()
        event.syncId = syncId
        event.uid2445 = cursor.nullableString(Cst.EVENT_PROJECTION_UID_2445_INDEX)
        event.originalSyncId =
            cursor.nullableString(Cst.EVENT_PROJECTION_ORIGINAL_SYNC_ID_INDEX)
        event.eventIsDeleted =
            cursor.nullableBoolean(Cst.EVENT_PROJECTION_DELETED_INDEX)
        event.selfAttendeeStatus =
            cursor.nullableInt(Cst.EVENT_PROJECTION_SELF_ATTENDEE_STATUS_INDEX)
        event.calendarId = calendarId
        event.eventIsDetached = originalEventId != null || originalStartDate != null
        event.eventOriginalStartDate = originalStartDate
        event.originalEventId = originalEventId
        event.eventDescription = description
        event.eventStartDate = begin
        event.eventEndDate = end
        event.eventAllDay = allDay
        event.eventLocation = location
        event.eventURL = url
        event.recurrenceRule = parseRecurrenceRuleString(recurringRule)
        event.eventStartTimeZone = startTimeZone
        event.eventEndTimeZone = endTimeZone
        event.availability = availability
        event.eventStatus = eventStatus
        event.eventColor = if (eventColor == 0) null else eventColor
        event.eventColorKey = if (eventColorKey == 0) null else eventColorKey

        return event
    }

    private fun parseMasterEvent(cursor: Cursor?): Event? {
        if (cursor == null) return null

        val eventId = cursor.getLong(Cst.MASTER_EVENT_PROJECTION_ID_INDEX)
        val masterCalendarId =
            cursor.getLong(Cst.MASTER_EVENT_PROJECTION_CALENDAR_ID_INDEX).toString()
        val start = cursor.getLong(Cst.MASTER_EVENT_PROJECTION_START_INDEX)
        val end = if (cursor.isNull(Cst.MASTER_EVENT_PROJECTION_END_INDEX)) {
            start + (parseDurationMillis(
                cursor.getString(Cst.MASTER_EVENT_PROJECTION_DURATION_INDEX)
            ) ?: 0L)
        } else {
            cursor.getLong(Cst.MASTER_EVENT_PROJECTION_END_INDEX)
        }

        val event = Event()
        event.eventId = eventId.toString()
        event.calendarId = masterCalendarId
        event.eventTitle =
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_TITLE_INDEX) ?: "New Event"
        event.eventDescription =
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_DESCRIPTION_INDEX)
        event.eventStartDate = start
        event.eventEndDate = end
        event.eventAllDay =
            cursor.getInt(Cst.MASTER_EVENT_PROJECTION_ALL_DAY_INDEX) > 0
        event.eventLocation =
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_EVENT_LOCATION_INDEX)
        event.eventURL =
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_CUSTOM_APP_URI_INDEX)
        event.eventStartTimeZone =
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_START_TIMEZONE_INDEX)
        event.eventEndTimeZone =
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_END_TIMEZONE_INDEX)
        event.recurrenceRule = parseRecurrenceRuleString(
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_RECURRING_RULE_INDEX)
        )
        event.availability = parseAvailability(
            cursor.getInt(Cst.MASTER_EVENT_PROJECTION_AVAILABILITY_INDEX)
        )
        event.eventStatus = parseEventStatus(
            cursor.getInt(Cst.MASTER_EVENT_PROJECTION_STATUS_INDEX)
        )
        val eventColor = cursor.getInt(Cst.MASTER_EVENT_PROJECTION_EVENT_COLOR_INDEX)
        val eventColorKey = cursor.getInt(Cst.MASTER_EVENT_PROJECTION_EVENT_COLOR_KEY_INDEX)
        val syncId = if (cursor.isNull(Cst.MASTER_EVENT_PROJECTION_SYNC_ID_INDEX)) {
            null
        } else {
            cursor.getString(Cst.MASTER_EVENT_PROJECTION_SYNC_ID_INDEX)
        }
        val originalEventId = if (
            cursor.isNull(Cst.MASTER_EVENT_PROJECTION_ORIGINAL_ID_INDEX)
        ) null else cursor.getString(Cst.MASTER_EVENT_PROJECTION_ORIGINAL_ID_INDEX)
        val originalStartDate = if (
            cursor.isNull(Cst.MASTER_EVENT_PROJECTION_ORIGINAL_INSTANCE_TIME_INDEX)
        ) null else cursor.getLong(Cst.MASTER_EVENT_PROJECTION_ORIGINAL_INSTANCE_TIME_INDEX)
        event.eventColor = if (eventColor == 0) null else eventColor
        event.eventColorKey = if (eventColorKey == 0) null else eventColorKey
        event.syncId = syncId
        event.eventIsDirty =
            cursor.getInt(Cst.MASTER_EVENT_PROJECTION_DIRTY_INDEX) != 0
        event.uid2445 =
            cursor.nullableString(Cst.MASTER_EVENT_PROJECTION_UID_2445_INDEX)
        event.originalSyncId =
            cursor.nullableString(Cst.MASTER_EVENT_PROJECTION_ORIGINAL_SYNC_ID_INDEX)
        event.eventIsDeleted =
            cursor.nullableBoolean(Cst.MASTER_EVENT_PROJECTION_DELETED_INDEX)
        event.selfAttendeeStatus =
            cursor.nullableInt(Cst.MASTER_EVENT_PROJECTION_SELF_ATTENDEE_STATUS_INDEX)
        event.eventIsDetached = originalEventId != null || originalStartDate != null
        event.originalEventId = originalEventId
        event.eventOriginalStartDate = originalStartDate

        return event
    }

    private fun parseRecurrenceRuleString(recurrenceRuleString: String?): RecurrenceRule? {
        if (recurrenceRuleString == null) {
            return null
        }
        val rfcRecurrenceRule = try {
            Rrule(recurrenceRuleString)
        } catch (e: InvalidRecurrenceRuleException) {
            // Malformed RRULE (e.g. missing FREQ part) — skip recurrence rather than crash
            return null
        }
        val frequency = when (rfcRecurrenceRule.freq) {
            RruleFreq.YEARLY -> RruleFreq.YEARLY
            RruleFreq.MONTHLY -> RruleFreq.MONTHLY
            RruleFreq.WEEKLY -> RruleFreq.WEEKLY
            RruleFreq.DAILY -> RruleFreq.DAILY
            else -> null
        } ?: return null
        //Avoid handling HOURLY/MINUTELY/SECONDLY frequencies for now

        val recurrenceRule = RecurrenceRule(frequency)

        recurrenceRule.count = rfcRecurrenceRule.count
        recurrenceRule.interval = rfcRecurrenceRule.interval

        val until = rfcRecurrenceRule.until
        if (until != null) {
            recurrenceRule.until = formatDateTime(dateTime = until)
        }

        recurrenceRule.sourceRruleString = recurrenceRuleString

        //TODO: Force set to Monday (atm RRULE package only seem to support Monday)
        recurrenceRule.wkst = /*rfcRecurrenceRule.weekStart.name*/Weekday.MO.name
        recurrenceRule.byday = rfcRecurrenceRule.byDayPart?.mapNotNull {
            it.toString()
        }?.toMutableList()
        recurrenceRule.bymonthday = rfcRecurrenceRule.getByPart(Rrule.Part.BYMONTHDAY)
        recurrenceRule.byyearday = rfcRecurrenceRule.getByPart(Rrule.Part.BYYEARDAY)
        recurrenceRule.byweekno = rfcRecurrenceRule.getByPart(Rrule.Part.BYWEEKNO)

        // Below adjustment of byMonth ints is necessary as the library somehow gives a wrong int
        // See also [buildRecurrenceRuleParams] where 1 is subtracted.
        val oldByMonth = rfcRecurrenceRule.getByPart(Rrule.Part.BYMONTH)
        if (oldByMonth != null) {
            val newByMonth = mutableListOf<Int>()
            for (month in oldByMonth) {
                newByMonth.add(month + 1)
            }
            recurrenceRule.bymonth = newByMonth
        } else {
            recurrenceRule.bymonth = rfcRecurrenceRule.getByPart(Rrule.Part.BYMONTH)
        }

        recurrenceRule.bysetpos = rfcRecurrenceRule.getByPart(Rrule.Part.BYSETPOS)

        return recurrenceRule
    }

    private data class EventRecurrenceValue(
        val rule: Map<String, Any?>?,
        val rawRule: String?
    ) {
        fun toMap(): Map<String, Any?> = mapOf("rule" to rule)
    }

    private fun parseEventRecurrenceValue(value: Any?): EventRecurrenceValue? {
        val wrapper = value as? Map<*, *> ?: return null
        if (!wrapper.containsKey("rule")) return null
        val ruleValue = wrapper["rule"] ?: return EventRecurrenceValue(null, null)
        val rule = ruleValue as? Map<*, *> ?: return null
        val rawRule = recurrenceRuleStringFromMap(rule) ?: return null
        val canonicalRule = recurrenceRuleMap(rawRule) ?: return null
        return EventRecurrenceValue(canonicalRule, rawRule)
    }

    private fun recurrenceRuleStringFromMap(rule: Map<*, *>): String? {
        return try {
            val frequencyName = rule["freq"] as? String ?: return null
            val recurrence = Rrule(RruleFreq.valueOf(frequencyName.uppercase()))
            (rule["interval"] as? Number)?.toInt()?.let { recurrence.interval = it }
            (rule["count"] as? Number)?.toInt()?.let { recurrence.count = it }
            (rule["until"] as? String)?.let { recurrence.until = parseDateTime(it) }
            (rule["wkst"] as? String)?.let {
                recurrence.weekStart = Weekday.valueOf(it.uppercase())
            }
            recurrence.byDayPart = recurrenceStringList(rule["byday"])?.map {
                WeekdayNum.valueOf(it.uppercase())
            }?.toMutableList()
            setRecurrenceIntPart(recurrence, Rrule.Part.BYSECOND, rule["bysecond"])
            setRecurrenceIntPart(recurrence, Rrule.Part.BYMINUTE, rule["byminute"])
            setRecurrenceIntPart(recurrence, Rrule.Part.BYHOUR, rule["byhour"])
            setRecurrenceIntPart(recurrence, Rrule.Part.BYMONTHDAY, rule["bymonthday"])
            setRecurrenceIntPart(recurrence, Rrule.Part.BYYEARDAY, rule["byyearday"])
            setRecurrenceIntPart(recurrence, Rrule.Part.BYWEEKNO, rule["byweekno"])
            setRecurrenceIntPart(
                recurrence,
                Rrule.Part.BYMONTH,
                recurrenceIntList(rule["bymonth"])?.map { it - 1 }
            )
            setRecurrenceIntPart(recurrence, Rrule.Part.BYSETPOS, rule["bysetpos"])
            recurrence.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun recurrenceRuleMap(rawRule: String?): Map<String, Any?>? {
        if (rawRule == null) return null
        return try {
            val recurrence = Rrule(rawRule)
            buildMap {
                put("freq", recurrence.freq.name)
                recurrence.until?.let { put("until", formatDateTime(it)) }
                recurrence.count?.let { put("count", it) }
                recurrence.interval?.let { put("interval", it) }
                putRecurrencePart(this, "bysecond", recurrence, Rrule.Part.BYSECOND)
                putRecurrencePart(this, "byminute", recurrence, Rrule.Part.BYMINUTE)
                putRecurrencePart(this, "byhour", recurrence, Rrule.Part.BYHOUR)
                recurrence.byDayPart?.takeIf { it.isNotEmpty() }?.let {
                    put("byday", it.map(Any::toString))
                }
                putRecurrencePart(this, "bymonthday", recurrence, Rrule.Part.BYMONTHDAY)
                putRecurrencePart(this, "byyearday", recurrence, Rrule.Part.BYYEARDAY)
                putRecurrencePart(this, "byweekno", recurrence, Rrule.Part.BYWEEKNO)
                recurrence.getByPart(Rrule.Part.BYMONTH)?.takeIf { it.isNotEmpty() }?.let {
                    put("bymonth", it.map { month -> month + 1 })
                }
                putRecurrencePart(this, "bysetpos", recurrence, Rrule.Part.BYSETPOS)
                recurrence.weekStart?.let { put("wkst", it.name) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun recurrenceIntList(value: Any?): List<Int>? {
        if (value == null) return null
        val values = if (value is List<*>) value else listOf(value)
        return values.map { (it as? Number)?.toInt() ?: return null }
    }

    private fun recurrenceStringList(value: Any?): List<String>? {
        if (value == null) return null
        val values = if (value is List<*>) value else listOf(value)
        return values.map { it as? String ?: return null }
    }

    private fun setRecurrenceIntPart(
        recurrence: Rrule,
        part: Rrule.Part,
        value: Any?
    ) {
        setRecurrenceIntPart(recurrence, part, recurrenceIntList(value))
    }

    private fun setRecurrenceIntPart(
        recurrence: Rrule,
        part: Rrule.Part,
        values: List<Int>?
    ) {
        if (!values.isNullOrEmpty()) recurrence.setByPart(part, values.toMutableList())
    }

    private fun putRecurrencePart(
        target: MutableMap<String, Any?>,
        key: String,
        recurrence: Rrule,
        part: Rrule.Part
    ) {
        recurrence.getByPart(part)?.takeIf { it.isNotEmpty() }?.let { target[key] = it }
    }


    private fun formatDateTime(dateTime: DateTime): String {
        assert(dateTime.year in 0..9999)

        fun twoDigits(n: Int): String {
            return if (n < 10) "0$n" else "$n"
        }

        fun fourDigits(n: Int): String {
            val absolute = n.absoluteValue
            val sign = if (n < 0) "-" else ""
            if (absolute >= 1000) return "$n"
            if (absolute >= 100) return "${sign}0$absolute"
            if (absolute >= 10) return "${sign}00$absolute"
            return "${sign}000$absolute"
        }

        val year = fourDigits(dateTime.year)
        val month = twoDigits(dateTime.month.plus(1))
        val day = twoDigits(dateTime.dayOfMonth)
        val hour = twoDigits(dateTime.hours)
        val minute = twoDigits(dateTime.minutes)
        val second = twoDigits(dateTime.seconds)
        val utcSuffix = if (dateTime.timeZone == UTC) 'Z' else ""
        return "$year-$month-${day}T$hour:$minute:$second$utcSuffix"
    }

    private fun parseAttendeeRow(calendar: Calendar, cursor: Cursor?): Attendee? {
        if (cursor == null) {
            return null
        }

        val emailAddress = cursor.getString(Cst.ATTENDEE_EMAIL_INDEX)

        return Attendee(
            emailAddress,
            cursor.getString(Cst.ATTENDEE_NAME_INDEX),
            cursor.getInt(Cst.ATTENDEE_TYPE_INDEX),
            cursor.getInt(Cst.ATTENDEE_STATUS_INDEX),
            cursor.getInt(Cst.ATTENDEE_RELATIONSHIP_INDEX) == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
            emailAddress == calendar.ownerAccount
        )
    }

    private fun parseReminderRow(cursor: Cursor?): Reminder? {
        if (cursor == null) {
            return null
        }

        return Reminder(
            cursor.getInt(Cst.REMINDER_MINUTES_INDEX),
            cursor.getInt(Cst.REMINDER_METHOD_INDEX)
        )
    }

    private fun isCalendarReadOnly(accessLevel: Int): Boolean {
        return when (accessLevel) {
            Events.CAL_ACCESS_CONTRIBUTOR,
            Events.CAL_ACCESS_ROOT,
            Events.CAL_ACCESS_OWNER,
            Events.CAL_ACCESS_EDITOR
            -> false
            else -> true
        }
    }

    @SuppressLint("MissingPermission")
    private fun retrieveAttendees(
        calendar: Calendar,
        eventId: String,
        contentResolver: ContentResolver?
    ): MutableList<Attendee> {
        val attendees: MutableList<Attendee> = mutableListOf()
        val attendeesQuery = "(${CalendarContract.Attendees.EVENT_ID} = ${eventId})"
        val attendeesCursor = contentResolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            Cst.ATTENDEE_PROJECTION,
            attendeesQuery,
            null,
            null
        )
        attendeesCursor.use { cursor ->
            if (cursor?.moveToFirst() == true) {
                do {
                    val attendee = parseAttendeeRow(calendar, attendeesCursor) ?: continue
                    attendees.add(attendee)
                } while (cursor.moveToNext())
            }
        }

        return attendees
    }

    @SuppressLint("MissingPermission")
    private fun retrieveReminders(
        eventId: String,
        contentResolver: ContentResolver?
    ): MutableList<Reminder> {
        val reminders: MutableList<Reminder> = mutableListOf()
        val remindersQuery = "(${CalendarContract.Reminders.EVENT_ID} = ${eventId})"
        val remindersCursor = contentResolver?.query(
            CalendarContract.Reminders.CONTENT_URI,
            Cst.REMINDER_PROJECTION,
            remindersQuery,
            null,
            null
        )
        remindersCursor.use { cursor ->
            if (cursor?.moveToFirst() == true) {
                do {
                    val reminder = parseReminderRow(remindersCursor) ?: continue
                    reminders.add(reminder)
                } while (cursor.moveToNext())
            }
        }
        return reminders
    }

    /**
     * load available event colors for the given account name
     * unable to find official documentation, so logic is based on https://android.googlesource.com/platform/packages/apps/Calendar.git/+/refs/heads/pie-release/src/com/android/calendar/EventInfoFragment.java
     **/
    private fun retrieveColors(accountName: String, colorType: Int): List<Pair<Int, Int>> {
        val contentResolver: ContentResolver? = _context?.contentResolver
        val uri: Uri = Colors.CONTENT_URI
        val colors = mutableListOf<Int>()
        val displayColorKeyMap = SparseArrayCompat<Int>()

        val projection = arrayOf(
            Colors.COLOR,
            Colors.COLOR_KEY,
        )

        // load only event colors for the given account name
        val selection = "${Colors.COLOR_TYPE} = ? AND ${Colors.ACCOUNT_NAME} = ?"
        val selectionArgs = arrayOf(colorType.toString(), accountName)


        val cursor: Cursor? = contentResolver?.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            while (it.moveToNext()) {
                val color = it.getInt(it.getColumnIndexOrThrow(Colors.COLOR))
                val colorKey = it.getInt(it.getColumnIndexOrThrow(Colors.COLOR_KEY))
                displayColorKeyMap.put(color, colorKey);
                colors.add(color)
            }
            cursor.close();
            // sort colors by colorValue, since they are loaded unordered
            colors.sortWith(HsvColorComparator())
        }
        return colors.map { Pair(it, displayColorKeyMap[it]!! ) }.toList()
    }

    fun retrieveEventColors(accountName: String): List<Pair<Int, Int>> {
        return  retrieveColors(accountName, Colors.TYPE_EVENT)
    }
    fun retrieveCalendarColors(accountName: String): List<Pair<Int, Int>> {
        return  retrieveColors(accountName, Colors.TYPE_CALENDAR)
    }

    fun updateCalendarColor(calendarId: Long, newColorKey: Int?, newColor: Int?): Boolean {
        val contentResolver: ContentResolver? = _context?.contentResolver
        val uri: Uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_COLOR_KEY, newColorKey)
            put(CalendarContract.Calendars.CALENDAR_COLOR, newColor)
        }
        val rows = contentResolver?.update(uri, values, null, null)
        return (rows ?: 0) > 0
    }

    /**
     * Compares colors based on their hue values in the HSV color space.
     * https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-compose-integration-release/android-34/com/android/colorpicker/HsvColorComparator.java
     */
    private class HsvColorComparator : Comparator<Int> {
        override fun compare(color1: Int, color2: Int): Int {
            val hsv1 = FloatArray(3)
            val hsv2 = FloatArray(3)
            Color.colorToHSV(color1, hsv1)
            Color.colorToHSV(color2, hsv2)
            return hsv1[0].compareTo(hsv2[0])
        }
    }

    @Synchronized
    private fun generateUniqueRequestCodeAndCacheParameters(parameters: CalendarMethodsParametersCacheModel): Int {
        // TODO we can ran out of Int's at some point so this probably should re-use some of the freed ones
        val uniqueRequestCode: Int = (_cachedParametersMap.keys.maxOrNull() ?: 0) + 1
        parameters.ownCacheKey = uniqueRequestCode
        _cachedParametersMap[uniqueRequestCode] = parameters

        return uniqueRequestCode
    }

    private fun <T> finishWithSuccess(result: T, pendingChannelResult: MethodChannel.Result) {
        pendingChannelResult.success(result)
        clearCachedParameters(pendingChannelResult)
    }

    private fun finishWithError(
        errorCode: String,
        errorMessage: String?,
        pendingChannelResult: MethodChannel.Result
    ) {
        pendingChannelResult.error(errorCode, errorMessage, null)
        clearCachedParameters(pendingChannelResult)
    }

    private fun clearCachedParameters(pendingChannelResult: MethodChannel.Result) {
        val cachedParameters =
            _cachedParametersMap.values.filter { it.pendingChannelResult == pendingChannelResult }
                .toList()
        for (cachedParameter in cachedParameters) {
            if (_cachedParametersMap.containsKey(cachedParameter.ownCacheKey)) {
                _cachedParametersMap.remove(cachedParameter.ownCacheKey)
            }
        }
    }

    private fun atLeastAPI(api: Int): Boolean {
        return api <= Build.VERSION.SDK_INT
    }

    private fun buildRecurrenceRuleParams(recurrenceRule: RecurrenceRule): String? {
        val frequencyParam = when (recurrenceRule.freq) {
            RruleFreq.DAILY -> RruleFreq.DAILY
            RruleFreq.WEEKLY -> RruleFreq.WEEKLY
            RruleFreq.MONTHLY -> RruleFreq.MONTHLY
            RruleFreq.YEARLY -> RruleFreq.YEARLY
            else -> null
        } ?: return null

        val rr = Rrule(frequencyParam)
        if (recurrenceRule.interval != null) {
            rr.interval = recurrenceRule.interval!!
        }

        if (recurrenceRule.count != null) {
            rr.count = recurrenceRule.count!!
        } else if (recurrenceRule.until != null) {
            var untilString: String = recurrenceRule.until!!
            if (!untilString.endsWith("Z")) {
                untilString += "Z"
            }
            rr.until = parseDateTime(untilString)
        }

        if (recurrenceRule.wkst != null) {
            rr.weekStart = Weekday.valueOf(recurrenceRule.wkst!!)
        }

        if (recurrenceRule.byday != null) {
            rr.byDayPart = recurrenceRule.byday?.mapNotNull {
                WeekdayNum.valueOf(it)
            }?.toMutableList()
        }

        if (recurrenceRule.bymonthday != null) {
            rr.setByPart(Rrule.Part.BYMONTHDAY, recurrenceRule.bymonthday!!)
        }

        if (recurrenceRule.byyearday != null) {
            rr.setByPart(Rrule.Part.BYYEARDAY, recurrenceRule.byyearday!!)
        }

        if (recurrenceRule.byweekno != null) {
            rr.setByPart(Rrule.Part.BYWEEKNO, recurrenceRule.byweekno!!)
        }
        // Below adjustment of byMonth ints is necessary as the library somehow gives a wrong int
        // See also [parseRecurrenceRuleString] where +1 is added.
        if (recurrenceRule.bymonth != null) {
            val byMonth = recurrenceRule.bymonth!!
            val newMonth = mutableListOf<Int>()
            byMonth.forEach {
                newMonth.add(it - 1)
            }
            rr.setByPart(Rrule.Part.BYMONTH, newMonth)
        }

        if (recurrenceRule.bysetpos != null) {
            rr.setByPart(Rrule.Part.BYSETPOS, recurrenceRule.bysetpos!!)
        }
        return rr.toString()
    }

    private fun parseDateTime(string: String): DateTime {
        val year = Regex("""(?<year>\d{4})""").pattern
        val month = Regex("""(?<month>\d{2})""").pattern
        val day = Regex("""(?<day>\d{2})""").pattern
        val hour = Regex("""(?<hour>\d{2})""").pattern
        val minute = Regex("""(?<minute>\d{2})""").pattern
        val second = Regex("""(?<second>\d{2})""").pattern

        val regEx = Regex("^$year-$month-${day}T$hour:$minute:${second}Z?\$")

        val match = regEx.matchEntire(string)

        return DateTime(
            UTC,
            match?.groups?.get(1)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(2)?.value?.toIntOrNull()?.minus(1) ?: 0,
            match?.groups?.get(3)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(4)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(5)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(6)?.value?.toIntOrNull() ?: 0
        )
    }

    private fun parseAvailability(availability: Int): Availability? = when (availability) {
        Events.AVAILABILITY_BUSY -> Availability.BUSY
        Events.AVAILABILITY_FREE -> Availability.FREE
        Events.AVAILABILITY_TENTATIVE -> Availability.TENTATIVE
        else -> null
    }

    private fun parseEventStatus(status: Int): EventStatus? = when(status) {
        Events.STATUS_CONFIRMED -> EventStatus.CONFIRMED
        Events.STATUS_CANCELED -> EventStatus.CANCELED
        Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
        else -> null
    }
}
