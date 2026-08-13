package com.builttoroam.devicecalendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
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
                        cachedValues.pendingChannelResult
                    )
                }
                APPLY_EVENT_CHANGES_REQUEST_CODE -> {
                    applyEventChanges(
                        cachedValues.calendarId,
                        cachedValues.eventId,
                        cachedValues.eventChanges,
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
                    val attendees = retrieveAttendees(calendar, event.eventId!!, contentResolver)
                    event.organizer =
                        attendees.firstOrNull { it.isOrganizer != null && it.isOrganizer }
                    event.attendees = attendees
                    event.reminders = retrieveReminders(event.eventId!!, contentResolver)
                }
            }.invokeOnCompletion { cause ->
                eventsCursor?.close()
                if (cause == null) {
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
    ) {
        if (!arePermissionsGranted()) {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_MASTER_EVENT_REQUEST_CODE,
                calendarId,
                eventId = originalEventId
            )
            requestPermissions(parameters)
            return
        }

        val originalEventIdNumber = originalEventId.toLongOrNull()
        if (originalEventIdNumber == null) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "The original event ID must be a number",
                pendingChannelResult
            )
            return
        }

        val contentResolver = _context?.contentResolver
        val eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, originalEventIdNumber)
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
                    "The master event with the ID $originalEventId could not be found",
                    pendingChannelResult
                )
                return
            }

            val event = parseMasterEvent(cursor)
            if (event == null || event.calendarId != calendarId) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The master event with the ID $originalEventId could not be found in calendar $calendarId",
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

            event.attendees = retrieveAttendees(calendar, originalEventId, contentResolver)
            event.organizer = event.attendees.firstOrNull {
                it.isOrganizer != null && it.isOrganizer
            }
            event.reminders = retrieveReminders(originalEventId, contentResolver)
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
                newAttendeeStatus = newStatus
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
            attendeeEmail,
            expectedStatus.toString()
        )
        val values = ContentValues().apply {
            put(CalendarContract.Attendees.ATTENDEE_STATUS, newStatus)
        }
        val updatedRows = contentResolver?.update(
            CalendarContract.Attendees.CONTENT_URI,
            values,
            selection,
            selectionArgs
        ) ?: 0
        if (updatedRows > 0) {
            finishWithSuccess(
                mapOf("outcome" to "updated", "currentStatus" to newStatus),
                pendingChannelResult
            )
            return
        }

        val currentStatusCursor = contentResolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS),
            "(${CalendarContract.Attendees.EVENT_ID} = ?) AND " +
                "(${CalendarContract.Attendees.ATTENDEE_EMAIL} = ?)",
            arrayOf(eventId, attendeeEmail),
            null
        )
        val currentStatus = currentStatusCursor.use { cursor ->
            if (cursor?.moveToFirst() == true) cursor.getInt(0) else null
        }
        if (currentStatus == null) {
            finishWithError(
                EC.NOT_FOUND,
                "The attendee $attendeeEmail could not be found for event $eventId",
                pendingChannelResult
            )
            return
        }

        finishWithSuccess(
            mapOf(
                "outcome" to if (currentStatus == newStatus) "alreadyCurrent" else "conflict",
                "currentStatus" to currentStatus
            ),
            pendingChannelResult
        )
    }

    fun applyEventChanges(
        calendarId: String,
        eventId: String,
        eventChanges: Map<String, Any?>,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (!arePermissionsGranted()) {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                APPLY_EVENT_CHANGES_REQUEST_CODE,
                calendarId,
                eventId = eventId,
                eventChanges = eventChanges
            )
            requestPermissions(parameters)
            return
        }

        val eventIdNumber = eventId.toLongOrNull()
        val calendarIdNumber = calendarId.toLongOrNull()
        val colorChange = eventChanges["color"] as? Map<*, *>
        val expectedColor = colorChange?.get("expected") as? Map<*, *>
        val requestedColor = colorChange?.get("requested") as? Map<*, *>
        if (eventIdNumber == null || calendarIdNumber == null ||
            colorChange == null || expectedColor == null || requestedColor == null ||
            !expectedColor.containsKey("color") || !expectedColor.containsKey("colorKey") ||
            !requestedColor.containsKey("color") || !requestedColor.containsKey("colorKey")
        ) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                "Invalid calendar, event, or event changes",
                pendingChannelResult
            )
            return
        }

        val expectedColorValue = (expectedColor["color"] as? Number)?.toInt()
        val expectedColorKey = (expectedColor["colorKey"] as? Number)?.toInt()
        val requestedColorValue = (requestedColor["color"] as? Number)?.toInt()
        val requestedColorKey = (requestedColor["colorKey"] as? Number)?.toInt()

        val selectionParts = mutableListOf(
            "${Events._ID} = ?",
            "${Events.CALENDAR_ID} = ?",
            "${Events.DELETED} != 1"
        )
        val selectionArgs = mutableListOf(eventId, calendarId)
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

        val values = ContentValues().apply {
            if (requestedColorValue == null) {
                putNull(Events.EVENT_COLOR)
            } else {
                put(Events.EVENT_COLOR, requestedColorValue)
            }
            if (requestedColorKey == null) {
                putNull(Events.EVENT_COLOR_KEY)
            } else {
                put(Events.EVENT_COLOR_KEY, requestedColorKey)
            }
        }
        val contentResolver = _context?.contentResolver
        val updatedRows = contentResolver?.update(
            Events.CONTENT_URI,
            values,
            selectionParts.joinToString(" AND "),
            selectionArgs.toTypedArray()
        ) ?: 0
        if (updatedRows > 0) {
            finishWithSuccess(
                eventChangeResult(
                    "updated",
                    emptyList(),
                    requestedColorValue,
                    requestedColorKey
                ),
                pendingChannelResult
            )
            return
        }

        val cursor = contentResolver?.query(
            Events.CONTENT_URI,
            arrayOf(Events.DELETED, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY),
            "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ?",
            arrayOf(eventId, calendarId),
            null
        )
        var deleted = false
        var currentColorValue: Int? = null
        var currentColorKey: Int? = null
        val eventExists = cursor.use {
            if (it?.moveToFirst() != true) {
                false
            } else {
                deleted = it.getInt(0) == 1
                currentColorValue =
                    if (it.isNull(1) || it.getInt(1) == 0) null else it.getInt(1)
                currentColorKey =
                    if (it.isNull(2) || it.getInt(2) == 0) null else it.getInt(2)
                !deleted
            }
        }
        if (!eventExists) {
            finishWithError(
                EC.NOT_FOUND,
                "The event with the ID $eventId could not be found in calendar $calendarId",
                pendingChannelResult
            )
            return
        }

        val alreadyCurrent = currentColorValue == requestedColorValue &&
            currentColorKey == requestedColorKey
        finishWithSuccess(
            eventChangeResult(
                if (alreadyCurrent) "alreadyCurrent" else "conflict",
                if (alreadyCurrent) emptyList() else listOf("color"),
                currentColorValue,
                currentColorKey
            ),
            pendingChannelResult
        )
    }

    private fun eventChangeResult(
        outcome: String,
        conflictingFields: List<String>,
        color: Int?,
        colorKey: Int?
    ): Map<String, Any?> {
        return mapOf(
            "outcome" to outcome,
            "conflictingFields" to conflictingFields,
            "currentValues" to mapOf(
                "color" to mapOf("color" to color, "colorKey" to colorKey)
            )
        )
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

            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                uiThreadHandler.post {
                    finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                }
            }

            val job: Job
            var eventId: Long? = event.eventId?.toLongOrNull()
            if (eventId == null) {
                val uri = contentResolver?.insert(Events.CONTENT_URI, values)
                // get the event ID that is the last element in the Uri
                eventId = java.lang.Long.parseLong(uri?.lastPathSegment!!)
                job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                    insertAttendees(event.attendees, eventId, contentResolver)
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
                    val attendeesToDelete =
                        if (event.attendees.isNotEmpty()) existingAttendees.filter { existingAttendee -> event.attendees.all { it.emailAddress != existingAttendee.emailAddress } } else existingAttendees
                    for (attendeeToDelete in attendeesToDelete) {
                        deleteAttendee(eventId, attendeeToDelete, contentResolver)
                    }

                    val attendeesToInsert =
                        event.attendees.filter { existingAttendees.all { existingAttendee -> existingAttendee.emailAddress != it.emailAddress } }
                    insertAttendees(attendeesToInsert, eventId, contentResolver)
                    deleteExistingReminders(contentResolver, eventId)
                    insertReminders(event.reminders, eventId, contentResolver!!)

                    val existingSelfAttendee = existingAttendees.firstOrNull {
                        it.emailAddress == calendar.ownerAccount
                    }
                    val newSelfAttendee = event.attendees.firstOrNull {
                        it.emailAddress == calendar.ownerAccount
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
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
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
                    CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
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
        if (atLeastAPI(17)) {
            val isPrimary = cursor.getString(Cst.CALENDAR_PROJECTION_IS_PRIMARY_INDEX)
            calendar.isDefault = isPrimary == "1"
        } else {
            calendar.isDefault = false
        }
        return calendar
    }

    private fun parseEvent(calendarId: String, cursor: Cursor?): Event? {
        if (cursor == null) {
            return null
        }
        val eventId = cursor.getLong(Cst.EVENT_PROJECTION_ID_INDEX)
        val title = cursor.getString(Cst.EVENT_PROJECTION_TITLE_INDEX)
        val description = cursor.getString(Cst.EVENT_PROJECTION_DESCRIPTION_INDEX)
        val begin = cursor.getLong(Cst.EVENT_PROJECTION_BEGIN_INDEX)
        val end = cursor.getLong(Cst.EVENT_PROJECTION_END_INDEX)
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
        val event = Event()
        event.eventTitle = title ?: "New Event"
        event.eventId = eventId.toString()
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
        event.eventColor = if (eventColor == 0) null else eventColor
        event.eventColorKey = if (eventColorKey == 0) null else eventColorKey

        return event
    }

    private fun parseDurationMillis(duration: String?): Long? {
        if (duration == null) return null
        val match = Regex(
            "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$"
        ).matchEntire(duration) ?: return null
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

        return Reminder(cursor.getInt(Cst.REMINDER_MINUTES_INDEX))
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
