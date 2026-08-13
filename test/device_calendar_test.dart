import 'dart:convert';

import 'package:device_calendar/device_calendar.dart';
import 'package:device_calendar/src/common/error_codes.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('plugins.builttoroam.com/device_calendar');
  var deviceCalendarPlugin = DeviceCalendarPlugin();

  final log = <MethodCall>[];

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      print('Calling channel method ${methodCall.method}');
      log.add(methodCall);

      return null;
    });

    log.clear();
  });

  test('HasPermissions_Returns_Successfully', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return true;
    });

    final result = await deviceCalendarPlugin.hasPermissions();
    expect(result.isSuccess, true);
    expect(result.errors, isEmpty);
    expect(result.data, true);
  });

  test('RequestPermissions_Returns_Successfully', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return true;
    });

    final result = await deviceCalendarPlugin.requestPermissions();
    expect(result.isSuccess, true);
    expect(result.errors, isEmpty);
    expect(result.data, true);
  });

  test('CalendarChanges_Emits_Platform_Notifications', () async {
    const EventChannel changesChannel = EventChannel(
      'plugins.builttoroam.com/device_calendar/calendar_changes',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      changesChannel,
      MockStreamHandler.inline(
        onListen: (_, MockStreamHandlerEventSink events) {
          events.success(123);
          events.endOfStream();
        },
      ),
    );

    await deviceCalendarPlugin.calendarChanges.first;
  });

  test('RetrieveCalendars_Returns_Successfully', () async {
    const fakeCalendarName = 'fakeCalendarName';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return '[{"id":"1","isReadOnly":false,"name":"$fakeCalendarName"}]';
    });

    final result = await deviceCalendarPlugin.retrieveCalendars();
    expect(result.isSuccess, true);
    expect(result.errors, isEmpty);
    expect(result.data, isNotNull);
    expect(result.data, isNotEmpty);
    expect(result.data?[0].name, fakeCalendarName);
  });

  test('RetrieveEvents_CalendarId_IsRequired', () async {
    const String? calendarId = null;
    const params = RetrieveEventsParams();

    final result = await deviceCalendarPlugin.retrieveEvents(
      calendarId,
      params,
    );
    expect(result.isSuccess, false);
    expect(result.errors.length, greaterThan(0));
    expect(result.errors[0].errorCode, equals(ErrorCodes.invalidArguments));
  });

  test('RetrieveMasterEvent_Returns_Successfully', () async {
    final masterEvent = Event(
      'fakeCalendarId',
      eventId: 'masterEventId',
      title: 'Master Event',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return jsonEncode(masterEvent.toJson());
    });
    final detachedEvent = Event(
      'fakeCalendarId',
      eventId: 'detachedEventId',
      isDetached: true,
      originalEventId: 'masterEventId',
    );

    final result =
        await deviceCalendarPlugin.retrieveMasterEvent(detachedEvent);

    expect(result.isSuccess, true);
    expect(result.data?.eventId, masterEvent.eventId);
    expect(result.data?.title, masterEvent.title);
    expect(log, <Matcher>[
      isMethodCall(
        'retrieveMasterEvent',
        arguments: <String, dynamic>{
          'calendarId': detachedEvent.calendarId,
          'eventId': detachedEvent.eventId,
          'originalEventId': detachedEvent.originalEventId,
        },
      ),
    ]);
  });

  test('RetrieveMasterEvent_RequiresDetachedEvent', () async {
    final result = await deviceCalendarPlugin.retrieveMasterEvent(
      Event('fakeCalendarId', eventId: 'eventId'),
    );

    expect(result.isSuccess, false);
    expect(result.errors.first.errorCode, ErrorCodes.invalidArguments);
  });

  test('UpdateAttendeeStatus_Returns_Successfully', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'currentStatus': AndroidAttendanceStatus.Accepted.index,
      };
    });

    final result = await deviceCalendarPlugin.updateAttendeeStatus(
      calendarId: 'calendarId',
      eventId: 'eventId',
      attendeeEmail: 'user@example.com',
      expectedStatus: AndroidAttendanceStatus.Invited,
      newStatus: AndroidAttendanceStatus.Accepted,
    );

    expect(result.isSuccess, true);
    expect(result.data?.outcome, AttendeeStatusUpdateOutcome.updated);
    expect(
      result.data?.currentStatus,
      AndroidAttendanceStatus.Accepted,
    );
    expect(log, <Matcher>[
      isMethodCall(
        'updateAttendeeStatus',
        arguments: <String, dynamic>{
          'calendarId': 'calendarId',
          'eventId': 'eventId',
          'attendeeEmail': 'user@example.com',
          'expectedAttendeeStatus': AndroidAttendanceStatus.Invited.index,
          'newAttendeeStatus': AndroidAttendanceStatus.Accepted.index,
        },
      ),
    ]);
  });

  test('UpdateAttendeeStatus_ReturnsCurrentStatusOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'currentStatus': AndroidAttendanceStatus.Tentative.index,
      };
    });

    final result = await deviceCalendarPlugin.updateAttendeeStatus(
      calendarId: 'calendarId',
      eventId: 'eventId',
      attendeeEmail: 'user@example.com',
      expectedStatus: AndroidAttendanceStatus.Invited,
      newStatus: AndroidAttendanceStatus.Accepted,
    );

    expect(result.isSuccess, true);
    expect(result.data?.outcome, AttendeeStatusUpdateOutcome.conflict);
    expect(
      result.data?.currentStatus,
      AndroidAttendanceStatus.Tentative,
    );
  });

  test('UpdateAttendeeStatus_RequiresExpectedStatus', () async {
    final result = await deviceCalendarPlugin.updateAttendeeStatus(
      calendarId: 'calendarId',
      eventId: 'eventId',
      attendeeEmail: 'user@example.com',
      expectedStatus: null,
      newStatus: AndroidAttendanceStatus.Accepted,
    );

    expect(result.isSuccess, false);
    expect(result.errors.first.errorCode, ErrorCodes.invalidArguments);
  });

  test('ApplyEventChanges_UpdatesColorSuccessfully', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'conflictingFields': <String>[],
        'currentValues': <String, Object>{
          'color': <String, Object>{'color': 0xff445566, 'colorKey': 7},
        },
      };
    });

    const EventChangeSet changes = EventChangeSet(
      color: EventFieldChange<EventColorValue>(
        expected: EventColorValue(color: 0xff112233, colorKey: 3),
        requested: EventColorValue(color: 0xff445566, colorKey: 7),
      ),
    );
    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: changes,
    );

    expect(result.isSuccess, true);
    expect(result.data?.outcome, EventChangeOutcome.updated);
    expect(result.data?.conflictingFields, isEmpty);
    expect(result.data?.currentColor?.color, 0xff445566);
    expect(result.data?.currentColor?.colorKey, 7);
    expect(log, <Matcher>[
      isMethodCall(
        'applyEventChanges',
        arguments: <String, dynamic>{
          'calendarId': 'calendarId',
          'eventId': 'eventId',
          'eventChanges': <String, Object?>{
            'color': <String, Object?>{
              'expected': <String, Object?>{
                'color': 0xff112233,
                'colorKey': 3,
              },
              'requested': <String, Object?>{
                'color': 0xff445566,
                'colorKey': 7,
              },
            },
          },
        },
      ),
    ]);
  });

  test('ApplyEventChanges_ReturnsCurrentColorOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['color'],
        'currentValues': <String, Object>{
          'color': <String, Object>{'color': 0xff778899, 'colorKey': 9},
        },
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        color: EventFieldChange<EventColorValue>(
          expected: EventColorValue(color: 0xff112233, colorKey: 3),
          requested: EventColorValue(color: 0xff445566, colorKey: 7),
        ),
      ),
    );

    expect(result.data?.outcome, EventChangeOutcome.conflict);
    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.color},
    );
    expect(result.data?.currentColor?.color, 0xff778899);
    expect(result.data?.currentColor?.colorKey, 9);
  });

  test('ApplyEventChanges_RequiresAChange', () async {
    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(),
    );

    expect(result.isSuccess, false);
    expect(result.errors.first.errorCode, ErrorCodes.invalidArguments);
  });

  test('DeleteEvent_CalendarId_IsRequired', () async {
    const String? calendarId = null;
    const eventId = 'fakeEventId';

    final result = await deviceCalendarPlugin.deleteEvent(calendarId, eventId);
    expect(result.isSuccess, false);
    expect(result.errors.length, greaterThan(0));
    expect(result.errors[0].errorCode, equals(ErrorCodes.invalidArguments));
  });

  test('DeleteEvent_EventId_IsRequired', () async {
    const calendarId = 'fakeCalendarId';
    const String? eventId = null;

    final result = await deviceCalendarPlugin.deleteEvent(calendarId, eventId);
    expect(result.isSuccess, false);
    expect(result.errors.length, greaterThan(0));
    expect(result.errors[0].errorCode, equals(ErrorCodes.invalidArguments));
  });

  test('DeleteEvent_PassesArguments_Correctly', () async {
    const calendarId = 'fakeCalendarId';
    const eventId = 'fakeEventId';

    await deviceCalendarPlugin.deleteEvent(calendarId, eventId);
    expect(log, <Matcher>[
      isMethodCall(
        'deleteEvent',
        arguments: <String, dynamic>{
          'calendarId': calendarId,
          'eventId': eventId,
        },
      ),
    ]);
  });

  test('CreateEvent_Arguments_Invalid', () async {
    const String? fakeCalendarId = null;
    final event = Event(fakeCalendarId);

    final result = await deviceCalendarPlugin.createOrUpdateEvent(event);
    expect(result!.isSuccess, false);
    expect(result.errors, isNotEmpty);
    expect(result.errors[0].errorCode, equals(ErrorCodes.invalidArguments));
  });

  test('CreateEvent_Returns_Successfully', () async {
    const fakeNewEventId = 'fakeNewEventId';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return fakeNewEventId;
    });

    const fakeCalendarId = 'fakeCalendarId';
    final event = Event(fakeCalendarId);
    event.title = 'fakeEventTitle';
    event.start = TZDateTime.now(local);
    event.end = event.start!.add(const Duration(hours: 1));

    final result = await deviceCalendarPlugin.createOrUpdateEvent(event);
    expect(result?.isSuccess, true);
    expect(result?.errors, isEmpty);
    expect(result?.data, isNotEmpty);
    expect(result?.data, fakeNewEventId);
  });

  test('UpdateEvent_Returns_Successfully', () async {
    const fakeNewEventId = 'fakeNewEventId';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      final arguments = methodCall.arguments as Map<dynamic, dynamic>;
      if (!arguments.containsKey('eventId') || arguments['eventId'] == null) {
        return null;
      }

      return fakeNewEventId;
    });

    const fakeCalendarId = 'fakeCalendarId';
    final event = Event(fakeCalendarId);
    event.eventId = 'fakeEventId';
    event.title = 'fakeEventTitle';
    event.start = TZDateTime.now(local);
    event.end = event.start!.add(const Duration(hours: 1));

    final result = await deviceCalendarPlugin.createOrUpdateEvent(event);
    expect(result?.isSuccess, true);
    expect(result?.errors, isEmpty);
    expect(result?.data, isNotEmpty);
    expect(result?.data, fakeNewEventId);
  });

  test('Attendee_Serialises_Correctly', () async {
    final attendee = Attendee(
      name: 'Test Attendee',
      emailAddress: 'test@t.com',
      role: AttendeeRole.Required,
      isOrganiser: true,
    );
    final stringAttendee = attendee.toJson();
    expect(stringAttendee, isNotNull);
    final newAttendee = Attendee.fromJson(stringAttendee);
    expect(newAttendee, isNotNull);
    expect(newAttendee.name, equals(attendee.name));
    expect(newAttendee.emailAddress, equals(attendee.emailAddress));
    expect(newAttendee.role, equals(attendee.role));
    expect(newAttendee.isOrganiser, equals(attendee.isOrganiser));
    expect(newAttendee.iosAttendeeDetails, isNull);
    expect(newAttendee.androidAttendeeDetails, isNull);
  });

  test('Event_Serializes_Correctly', () async {
    final startTime = TZDateTime(
      timeZoneDatabase.locations.entries.skip(20).first.value,
      1980,
      10,
      1,
      0,
      0,
      0,
    );
    final endTime = TZDateTime(
      timeZoneDatabase.locations.entries.skip(21).first.value,
      1980,
      10,
      2,
      0,
      0,
      0,
    );
    final attendee = Attendee(
      name: 'Test Attendee',
      emailAddress: 'test@t.com',
      role: AttendeeRole.Required,
      isOrganiser: true,
    );
    final recurrence = RecurrenceRule(frequency: Frequency.daily);
    final reminder = Reminder(minutes: 10);
    var event = Event(
      'calendarId',
      eventId: 'eventId',
      title: 'Test Event',
      start: startTime,
      location: 'Seattle, Washington',
      url: Uri.dataFromString('http://www.example.com'),
      end: endTime,
      attendees: [attendee],
      description: 'Test description',
      recurrenceRule: recurrence,
      reminders: [reminder],
      availability: Availability.Busy,
      status: EventStatus.Confirmed,
      isDetached: true,
      originalStart: startTime.subtract(const Duration(days: 1)),
      originalEventId: 'originalEventId',
    );
    event.updateEventColor(EventColor(0xffff00ff, 1));

    final stringEvent = event.toJson();
    expect(stringEvent, isNotNull);
    final newEvent = Event.fromJson(stringEvent);
    expect(newEvent, isNotNull);
    expect(newEvent.calendarId, equals(event.calendarId));
    expect(newEvent.eventId, equals(event.eventId));
    expect(newEvent.isDetached, isTrue);
    expect(
      newEvent.originalStart!.millisecondsSinceEpoch,
      equals(event.originalStart!.millisecondsSinceEpoch),
    );
    expect(newEvent.originalEventId, equals(event.originalEventId));
    expect(newEvent.title, equals(event.title));
    expect(
      newEvent.start!.millisecondsSinceEpoch,
      equals(event.start!.millisecondsSinceEpoch),
    );
    expect(
      newEvent.end!.millisecondsSinceEpoch,
      equals(event.end!.millisecondsSinceEpoch),
    );
    expect(newEvent.description, equals(event.description));
    expect(newEvent.url, equals(event.url));
    expect(newEvent.location, equals(event.location));
    expect(newEvent.attendees, isNotNull);
    expect(newEvent.attendees?.length, equals(1));
    expect(newEvent.recurrenceRule, isNotNull);
    expect(
      newEvent.recurrenceRule?.frequency,
      equals(event.recurrenceRule?.frequency),
    );
    expect(newEvent.reminders, isNotNull);
    expect(newEvent.reminders?.length, equals(1));
    expect(newEvent.availability, equals(event.availability));
    expect(newEvent.status, equals(event.status));
    expect(newEvent.color, equals(event.color));
    expect(newEvent.colorKey, equals(event.colorKey));
  });
}
