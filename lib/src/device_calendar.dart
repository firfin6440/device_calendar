import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:device_calendar/device_calendar.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:timezone/data/latest.dart' as tz;

import 'common/channel_constants.dart';
import 'common/error_codes.dart';
import 'common/error_messages.dart';

/// Provides functionality for working with device calendar(s)
class DeviceCalendarPlugin {
  static const MethodChannel channel =
      MethodChannel(ChannelConstants.channelName);
  static const EventChannel _calendarChangesChannel =
      EventChannel(ChannelConstants.calendarChangesChannelName);

  static Stream<void>? _calendarChanges;

  /// Emits whenever the platform calendar store reports a change.
  ///
  /// Notifications are intentionally coarse: consumers should debounce them
  /// and reload only the ranges they currently need.
  Stream<void> get calendarChanges => _calendarChanges ??=
      _calendarChangesChannel.receiveBroadcastStream().map<void>((_) {});

  static final DeviceCalendarPlugin _instance = DeviceCalendarPlugin.private();

  factory DeviceCalendarPlugin({bool shouldInitTimezone = true}) {
    if (shouldInitTimezone) {
      tz.initializeTimeZones();
    }
    return _instance;
  }

  @visibleForTesting
  DeviceCalendarPlugin.private();

  /// Requests permissions to modify the calendars on the device
  ///
  /// Returns a [Result] indicating if calendar READ and WRITE permissions
  /// have (true) or have not (false) been granted
  Future<Result<bool>> requestPermissions() async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameRequestPermissions,
    );
  }

  /// Checks if permissions for modifying the device calendars have been granted
  ///
  /// Returns a [Result] indicating if calendar READ and WRITE permissions
  /// have (true) or have not (false) been granted
  Future<Result<bool>> hasPermissions() async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameHasPermissions,
    );
  }

  /// Retrieves all of the device defined calendars
  ///
  /// Returns a [Result] containing a list of device [Calendar]
  Future<Result<UnmodifiableListView<Calendar>>> retrieveCalendars() async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameRetrieveCalendars,
      evaluateResponse: (rawData) => UnmodifiableListView(
        json.decode(rawData).map<Calendar>(
              (decodedCalendar) => Calendar.fromJson(decodedCalendar),
            ),
      ),
    );
  }

  /// Retrieves the events from the specified calendar
  ///
  /// The `calendarId` paramter is the id of the calendar that plugin will return events for
  /// The `retrieveEventsParams` parameter combines multiple properties that
  /// specifies conditions of the events retrieval. For instance, defining [RetrieveEventsParams.startDate]
  /// and [RetrieveEventsParams.endDate] will return events only happening in that time range
  ///
  /// Returns a [Result] containing a list [Event], that fall
  /// into the specified parameters
  Future<Result<UnmodifiableListView<Event>>> retrieveEvents(
    String? calendarId,
    RetrieveEventsParams? retrieveEventsParams,
  ) async {
    return _invokeChannelMethod(ChannelConstants.methodNameRetrieveEvents,
        assertParameters: (result) {
          _validateCalendarIdParameter(
            result,
            calendarId,
          );

          _assertParameter(
            result,
            !((retrieveEventsParams?.eventIds?.isEmpty ?? true) &&
                ((retrieveEventsParams?.startDate == null ||
                        retrieveEventsParams?.endDate == null) ||
                    (retrieveEventsParams?.startDate != null &&
                        retrieveEventsParams?.endDate != null &&
                        (retrieveEventsParams != null &&
                            retrieveEventsParams.startDate!
                                .isAfter(retrieveEventsParams.endDate!))))),
            ErrorCodes.invalidArguments,
            ErrorMessages.invalidRetrieveEventsParams,
          );
        },
        arguments: () => <String, Object?>{
              ChannelConstants.parameterNameCalendarId: calendarId,
              ChannelConstants.parameterNameStartDate:
                  retrieveEventsParams?.startDate?.millisecondsSinceEpoch,
              ChannelConstants.parameterNameEndDate:
                  retrieveEventsParams?.endDate?.millisecondsSinceEpoch,
              ChannelConstants.parameterNameEventIds:
                  retrieveEventsParams?.eventIds,
            },
        /*evaluateResponse: (rawData) => UnmodifiableListView(
        json
            .decode(rawData)
            .map<Event>((decodedEvent) => Event.fromJson(decodedEvent)),
      ),*/
        evaluateResponse: (rawData) => UnmodifiableListView(
              json.decode(rawData).map<Event>((decodedEvent) {
                // debugPrint(
                //     "JSON_RRULE: ${decodedEvent['recurrenceRule']}, ${(decodedEvent['recurrenceRule']['byday'])}");
                return Event.fromJson(decodedEvent);
              }),
            ));
  }

  /// Retrieves the master event of a detached recurring-event occurrence.
  ///
  /// Returns an unsuccessful [Result] when [detachedEvent] is not detached or
  /// does not contain the identifiers required by the current platform.
  Future<Result<Event>> retrieveMasterEvent(Event? detachedEvent) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameRetrieveMasterEvent,
      assertParameters: (result) {
        _validateCalendarIdParameter(result, detachedEvent?.calendarId);
        _assertParameter(
          result,
          detachedEvent?.isDetached == true &&
              (detachedEvent?.eventId?.isNotEmpty ?? false) &&
              (!Platform.isAndroid ||
                  (detachedEvent?.originalEventId?.isNotEmpty ?? false)),
          ErrorCodes.invalidArguments,
          'A detached event with valid event identifiers is required.',
        );
      },
      arguments: () => <String, Object?>{
        ChannelConstants.parameterNameCalendarId: detachedEvent?.calendarId,
        ChannelConstants.parameterNameEventId: detachedEvent?.eventId,
        ChannelConstants.parameterNameOriginalEventId:
            detachedEvent?.originalEventId,
      },
      evaluateResponse: (rawData) => Event.fromJson(json.decode(rawData)),
    );
  }

  /// Updates one attendee's RSVP status without rewriting the event.
  ///
  /// Android only. The update succeeds only when the stored status still
  /// matches [expectedStatus]. This prevents overwriting a concurrent change.
  Future<Result<AttendeeStatusUpdateResult>> updateAttendeeStatus({
    required String? calendarId,
    required String? eventId,
    required String? attendeeEmail,
    required AndroidAttendanceStatus? expectedStatus,
    required AndroidAttendanceStatus? newStatus,
  }) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameUpdateAttendeeStatus,
      assertParameters: (result) {
        _validateCalendarIdParameter(result, calendarId);
        _assertParameter(
          result,
          (eventId?.isNotEmpty ?? false) &&
              (attendeeEmail?.isNotEmpty ?? false) &&
              expectedStatus != null &&
              newStatus != null,
          ErrorCodes.invalidArguments,
          'Event ID, attendee email, expected status and new status are required.',
        );
      },
      arguments: () => <String, Object?>{
        ChannelConstants.parameterNameCalendarId: calendarId,
        ChannelConstants.parameterNameEventId: eventId,
        ChannelConstants.parameterNameAttendeeEmail: attendeeEmail,
        ChannelConstants.parameterNameExpectedAttendeeStatus:
            expectedStatus?.index,
        ChannelConstants.parameterNameNewAttendeeStatus: newStatus?.index,
      },
      evaluateResponse: (rawData) {
        final Map<Object?, Object?> response =
            Map<Object?, Object?>.from(rawData as Map);
        final Object? currentStatusIndex = response['currentStatus'];
        if (currentStatusIndex is! int ||
            currentStatusIndex < 0 ||
            currentStatusIndex >= AndroidAttendanceStatus.values.length) {
          throw FormatException(
            'Invalid attendee status update response: $rawData',
          );
        }

        final AttendeeStatusUpdateOutcome outcome;
        switch (response['outcome']) {
          case 'updated':
            outcome = AttendeeStatusUpdateOutcome.updated;
            break;
          case 'alreadyCurrent':
            outcome = AttendeeStatusUpdateOutcome.alreadyCurrent;
            break;
          case 'conflict':
            outcome = AttendeeStatusUpdateOutcome.conflict;
            break;
          default:
            throw FormatException(
              'Unknown attendee status update result: $rawData',
            );
        }

        return AttendeeStatusUpdateResult(
          outcome: outcome,
          currentStatus: AndroidAttendanceStatus.values[currentStatusIndex],
        );
      },
    );
  }

  /// Atomically applies an optimistic set of changes to an existing event.
  ///
  /// Android only. No field is updated unless every expected value in
  /// [changes] still matches the value stored by the Calendar Provider.
  Future<Result<EventChangeResult>> applyEventChanges({
    required String? calendarId,
    required String? eventId,
    required EventChangeSet changes,
    EventRecurrenceChangeTarget? recurrenceTarget,
  }) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameApplyEventChanges,
      assertParameters: (result) {
        _validateCalendarIdParameter(result, calendarId);
        _assertParameter(
          result,
          (eventId?.isNotEmpty ?? false) && !changes.isEmpty,
          ErrorCodes.invalidArguments,
          'Event ID and at least one event change are required.',
        );
        _assertParameter(
          result,
          changes.hasValidTitleLength,
          ErrorCodes.invalidArguments,
          'Event titles cannot exceed ${changes.titleMaxLength} characters.',
        );
        _assertParameter(
          result,
          changes.hasValidDateRange,
          ErrorCodes.invalidArguments,
          'Event end dates cannot be before their start dates.',
        );
        _assertParameter(
          result,
          changes.hasValidReminders,
          ErrorCodes.invalidArguments,
          'Reminder minutes and methods must be non-negative.',
        );
      },
      arguments: () => <String, Object?>{
        ChannelConstants.parameterNameCalendarId: calendarId,
        ChannelConstants.parameterNameEventId: eventId,
        ChannelConstants.parameterNameEventChanges: changes.toJson(),
        if (recurrenceTarget != null)
          ChannelConstants.parameterNameRecurrenceChangeTarget:
              recurrenceTarget.toJson(),
      },
      evaluateResponse: (rawData) {
        final Map<Object?, Object?> response =
            Map<Object?, Object?>.from(rawData as Map);
        final EventChangeOutcome outcome;
        switch (response['outcome']) {
          case 'updated':
            outcome = EventChangeOutcome.updated;
            break;
          case 'alreadyCurrent':
            outcome = EventChangeOutcome.alreadyCurrent;
            break;
          case 'conflict':
            outcome = EventChangeOutcome.conflict;
            break;
          default:
            throw FormatException('Unknown event change result: $rawData');
        }

        final Set<EventChangeField> conflictingFields =
            ((response['conflictingFields'] as List?) ?? const <Object?>[])
                .map((Object? field) {
          switch (field) {
            case 'color':
              return EventChangeField.color;
            case 'title':
              return EventChangeField.title;
            case 'dateRange':
              return EventChangeField.dateRange;
            case 'reminders':
              return EventChangeField.reminders;
            default:
              throw FormatException('Unknown conflicting field: $field');
          }
        }).toSet();
        final Map<Object?, Object?> currentValues =
            Map<Object?, Object?>.from(response['currentValues'] as Map);
        final Object? rawCurrentColor = currentValues['color'];
        final Object? rawCurrentTitle = currentValues['title'];
        final Object? rawCurrentDateRange = currentValues['dateRange'];
        final Object? rawCurrentReminders = currentValues['reminders'];
        final Object? rawResultingEventId = response['resultingEventId'];

        return EventChangeResult(
          outcome: outcome,
          conflictingFields: conflictingFields,
          currentColor: rawCurrentColor is Map
              ? EventColorValue.fromJson(
                  Map<Object?, Object?>.from(rawCurrentColor),
                )
              : null,
          currentTitle: rawCurrentTitle is String ? rawCurrentTitle : null,
          currentDateRange: rawCurrentDateRange is Map
              ? EventDateRangeValue.fromJson(
                  Map<Object?, Object?>.from(rawCurrentDateRange),
                )
              : null,
          currentReminders: rawCurrentReminders is List
              ? rawCurrentReminders
                  .map((Object? reminder) => EventReminderValue.fromJson(
                        Map<Object?, Object?>.from(reminder as Map),
                      ))
                  .toList(growable: false)
              : null,
          resultingEventId: rawResultingEventId?.toString(),
        );
      },
    );
  }

  /// Deletes an event from a calendar. For a recurring event, this will delete all instances of it.\
  /// To delete individual instance of a recurring event, please use [deleteEventInstance()]
  ///
  /// The `calendarId` parameter is the id of the calendar that plugin will try to delete the event from\
  /// The `eventId` parameter is the id of the event that plugin will try to delete
  ///
  /// Returns a [Result] indicating if the event has (true) or has not (false) been deleted from the calendar
  Future<Result<bool>> deleteEvent(
    String? calendarId,
    String? eventId,
  ) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameDeleteEvent,
      assertParameters: (result) {
        _validateCalendarIdParameter(
          result,
          calendarId,
        );

        _assertParameter(
          result,
          eventId?.isNotEmpty ?? false,
          ErrorCodes.invalidArguments,
          ErrorMessages.deleteEventInvalidArgumentsMessage,
        );
      },
      arguments: () => <String, Object?>{
        ChannelConstants.parameterNameCalendarId: calendarId,
        ChannelConstants.parameterNameEventId: eventId,
      },
    );
  }

  /// Deletes an instance of a recurring event from a calendar. This should be used for a recurring event only.\
  /// If `startDate`, `endDate` or `deleteFollowingInstances` is not valid or null, then all instances of the event will be deleted.
  ///
  /// The `calendarId` parameter is the id of the calendar that plugin will try to delete the event from\
  /// The `eventId` parameter is the id of the event that plugin will try to delete\
  /// The `startDate` parameter is the start date of the instance to delete\
  /// The `endDate` parameter is the end date of the instance to delete\
  /// The `deleteFollowingInstances` parameter will also delete the following instances if set to true
  ///
  /// Returns a [Result] indicating if the instance of the event has (true) or has not (false) been deleted from the calendar
  Future<Result<bool>> deleteEventInstance(
    String? calendarId,
    String? eventId,
    int? startDate,
    int? endDate,
    bool deleteFollowingInstances,
  ) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameDeleteEventInstance,
      assertParameters: (result) {
        _validateCalendarIdParameter(
          result,
          calendarId,
        );

        _assertParameter(
          result,
          eventId?.isNotEmpty ?? false,
          ErrorCodes.invalidArguments,
          ErrorMessages.deleteEventInvalidArgumentsMessage,
        );
      },
      arguments: () => <String, Object?>{
        ChannelConstants.parameterNameCalendarId: calendarId,
        ChannelConstants.parameterNameEventId: eventId,
        ChannelConstants.parameterNameEventStartDate: startDate,
        ChannelConstants.parameterNameEventEndDate: endDate,
        ChannelConstants.parameterNameFollowingInstances:
            deleteFollowingInstances,
      },
    );
  }

  /// Creates or updates an event
  ///
  /// The `event` paramter specifies how event data should be saved into the calendar
  /// Always specify the [Event.calendarId], to inform the plugin in which calendar
  /// it should create or update the event.
  ///
  /// Returns a [Result] with the newly created or updated [Event.eventId]
  Future<Result<String>?> createOrUpdateEvent(Event? event) async {
    if (event == null) return null;
    return _invokeChannelMethod(
      ChannelConstants.methodNameCreateOrUpdateEvent,
      assertParameters: (result) {
        // Setting time to 0 for all day events
        if (event.allDay == true) {
          if (event.start != null) {
            var dateStart = DateTime(event.start!.year, event.start!.month,
                event.start!.day, 0, 0, 0);
            // allDay events on Android need to be at midnight UTC
            event.start = Platform.isAndroid
                ? TZDateTime.utc(event.start!.year, event.start!.month,
                    event.start!.day, 0, 0, 0)
                : TZDateTime.from(dateStart,
                    timeZoneDatabase.locations[event.start!.location.name]!);
          }
          if (event.end != null) {
            var dateEnd = DateTime(
                event.end!.year, event.end!.month, event.end!.day, 0, 0, 0);
            // allDay events on Android need to be at midnight UTC on the
            // day after the last day. For example, a 2-day allDay event on
            // Jan 1 and 2, should be from Jan 1 00:00:00 to Jan 3 00:00:00
            event.end = Platform.isAndroid
                ? TZDateTime.utc(event.end!.year, event.end!.month,
                        event.end!.day, 0, 0, 0)
                    .add(const Duration(days: 1))
                : TZDateTime.from(dateEnd,
                    timeZoneDatabase.locations[event.end!.location.name]!);
          }
        }

        _assertParameter(
          result,
          !(event.allDay == true && (event.calendarId?.isEmpty ?? true) ||
              event.start == null ||
              event.end == null),
          ErrorCodes.invalidArguments,
          ErrorMessages.createOrUpdateEventInvalidArgumentsMessageAllDay,
        );

        _assertParameter(
          result,
          !(event.allDay != true &&
              ((event.calendarId?.isEmpty ?? true) ||
                  event.start == null ||
                  event.end == null ||
                  (event.start != null &&
                      event.end != null &&
                      event.start!.isAfter(event.end!)))),
          ErrorCodes.invalidArguments,
          ErrorMessages.createOrUpdateEventInvalidArgumentsMessage,
        );
      },
      arguments: () => event.toJson(),
    );
  }

  /// Creates a new local calendar for the current device.
  ///
  /// The `calendarName` parameter is the name of the new calendar\
  /// The `calendarColor` parameter is the color of the calendar. If null,
  /// a default color (red) will be used\
  /// The `localAccountName` parameter is the name of the local account:
  /// - [Android] Required. If `localAccountName` parameter is null or empty, it will default to 'Device Calendar'.
  /// If the account name already exists in the device, it will add another calendar under the account,
  /// otherwise a new local account and a new calendar will be created.
  /// - [iOS] Not used. A local account will be picked up automatically, if not found, an error will be thrown.
  ///
  /// Returns a [Result] with the newly created [Calendar.id]
  Future<Result<String>> createCalendar(
    String? calendarName, {
    Color? calendarColor,
    String? localAccountName,
  }) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameCreateCalendar,
      assertParameters: (result) {
        calendarColor ??= Colors.red;

        _assertParameter(
          result,
          calendarName?.isNotEmpty == true,
          ErrorCodes.invalidArguments,
          ErrorMessages.createCalendarInvalidCalendarNameMessage,
        );
      },
      arguments: () => <String, Object?>{
        ChannelConstants.parameterNameCalendarName: calendarName,
        ChannelConstants.parameterNameCalendarColor:
            '0x${calendarColor?.value.toRadixString(16)}',
        ChannelConstants.parameterNameLocalAccountName:
            localAccountName?.isEmpty ?? true
                ? 'Device Calendar'
                : localAccountName
      },
    );
  }

  /// Deletes a calendar.
  /// The `calendarId` parameter is the id of the calendar that plugin will try to delete the event from\///
  /// Returns a [Result] indicating if the instance of the calendar has (true) or has not (false) been deleted
  Future<Result<bool>> deleteCalendar(
    String calendarId,
  ) async {
    return _invokeChannelMethod(
      ChannelConstants.methodNameDeleteCalendar,
      assertParameters: (result) {
        _validateCalendarIdParameter(
          result,
          calendarId,
        );
      },
      arguments: () => <String, Object>{
        ChannelConstants.parameterNameCalendarId: calendarId,
      },
    );
  }

  /// Displays a native iOS view [EKEventViewController]
  /// https://developer.apple.com/documentation/eventkitui/ekeventviewcontroller
  ///
  /// Allows to change the event's attendance status
  /// Works only on iOS
  /// Returns after dismissing EKEventViewController's dialog
  Future<Result<void>> showiOSEventModal(
    String eventId,
  ) {
    return _invokeChannelMethod(
      ChannelConstants.methodNameShowiOSEventModal,
      arguments: () => <String, String>{
        ChannelConstants.parameterNameEventId: eventId,
      },
    );
  }

  Future<List<EventColor>?> retrieveEventColors(Calendar calendar) async {
    if (!Platform.isAndroid) {
      return null;
    }
    final accountName = calendar.accountName;
    if (accountName == null) {
      return [];
    }
    final dynamic colors = await _invokeChannelMethod(
      ChannelConstants.methodNameRetrieveEventColors,
      arguments: () => <String, String>{
        ChannelConstants.parameterAccountName: accountName,
      },
    );
    return (colors.data as List)
        .cast<List>()
        .map((color) => EventColor(color[0], color[1]))
        .toList();
  }

  /// Retrieves available colors for Google Calendars.
  ///
  /// For non-Google calendars, an empty list is returned. Use the `color` parameter in [updateCalendarColor] for these.
  ///
  /// [calendar] The calendar to retrieve colors for.
  ///
  /// Returns a List with available colors for Google Calendars or an empty list for others.
  Future<List<CalendarColor>> retrieveCalendarColors(Calendar calendar) async {
    if (!Platform.isAndroid) {
      return [];
    }
    final accountName = calendar.accountName;
    if (accountName == null) {
      return [];
    }
    final dynamic colors = await _invokeChannelMethod(
      ChannelConstants.methodNameRetrieveCalendarColors,
      arguments: () => <String, String>{
        ChannelConstants.parameterAccountName: accountName,
      },
    );
    return (colors.data as List)
        .cast<List>()
        .map((color) => CalendarColor(color[0], color[1]))
        .toList();
  }

  /// Updates the color of a calendar using Google Calendar colors or platform-specific colors.
  ///  [calendar] The calendar to update. Must have a non-null `id`.
  ///  [calendarColor] Required for Google Calendars where [retrieveCalendarColors] is not empty.
  ///  [color] Required for locale or iOS Calendars where [retrieveCalendarColors] is empty.
  ///
  /// Returns `true` if the update was successful, otherwise `false`.
  Future<bool> updateCalendarColor(Calendar calendar,
      {CalendarColor? calendarColor, Color? color}) async {
    final calendarId = calendar.id;
    if (calendarId == null || color == null && calendarColor == null) {
      return false;
    }
    final result = await _invokeChannelMethod(
      ChannelConstants.methodNameUpdateCalendarColor,
      arguments: () => <String, dynamic>{
        ChannelConstants.parameterNameCalendarId:
            Platform.isAndroid ? int.tryParse(calendarId) : calendarId,
        ChannelConstants.parameterNameCalendarColorKey: calendarColor?.colorKey,
        ChannelConstants.parameterNameCalendarColor: color?.value,
      },
    );
    final success = (result.data as bool?) ?? false;
    if (success) {
      calendar.color = color?.value ?? calendarColor?.color;
    }
    return success;
  }

  Future<Result<T>> _invokeChannelMethod<T>(
    String channelMethodName, {
    Function(Result<T>)? assertParameters,
    Map<String, Object?> Function()? arguments,
    T Function(dynamic)? evaluateResponse,
  }) async {
    final result = Result<T>();

    try {
      if (assertParameters != null) {
        assertParameters(result);
        if (result.hasErrors) {
          return result;
        }
      }

      var rawData = await channel.invokeMethod(
        channelMethodName,
        arguments != null ? arguments() : null,
      );

      if (evaluateResponse != null) {
        result.data = evaluateResponse(rawData);
      } else {
        result.data = rawData;
      }
    } catch (e, s) {
      if (e is ArgumentError) {
        debugPrint(
            "INVOKE_CHANNEL_METHOD_ERROR! Name: ${e.name}, InvalidValue: ${e.invalidValue}, Message: ${e.message}, ${e.toString()}");
      } else if (e is PlatformException) {
        debugPrint('INVOKE_CHANNEL_METHOD_ERROR: $e\n$s');
      } else {
        _parsePlatformExceptionAndUpdateResult<T>(e as Exception?, result);
      }
    }

    return result;
  }

  void _parsePlatformExceptionAndUpdateResult<T>(
      Exception? exception, Result<T> result) {
    if (exception == null) {
      result.errors.add(
        const ResultError(
          ErrorCodes.unknown,
          ErrorMessages.unknownDeviceIssue,
        ),
      );
      return;
    }

    debugPrint('$exception');

    if (exception is PlatformException) {
      result.errors.add(
        ResultError(
          ErrorCodes.platformSpecific,
          '${ErrorMessages.unknownDeviceExceptionTemplate}, Code: ${exception.code}, Exception: ${exception.message}',
        ),
      );
    } else {
      result.errors.add(
        ResultError(
          ErrorCodes.generic,
          '${ErrorMessages.unknownDeviceGenericExceptionTemplate} ${exception.toString}',
        ),
      );
    }
  }

  void _assertParameter<T>(
    Result<T> result,
    bool predicate,
    int errorCode,
    String errorMessage,
  ) {
    if (result.data != null) {
      debugPrint("RESULT of _assertParameter: ${result.data}");
    }
    if (!predicate) {
      result.errors.add(
        ResultError(errorCode, errorMessage),
      );
    }
  }

  void _validateCalendarIdParameter<T>(
    Result<T> result,
    String? calendarId,
  ) {
    _assertParameter(
      result,
      calendarId?.isNotEmpty ?? false,
      ErrorCodes.invalidArguments,
      ErrorMessages.invalidMissingCalendarId,
    );
  }
}
