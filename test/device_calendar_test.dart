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

  test('RetrieveEvent_Returns_Event_Directly_By_Id', () async {
    final event = Event(
      'fakeCalendarId',
      eventId: 'eventId',
      title: 'Direct event',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return jsonEncode(event.toJson());
    });

    final result = await deviceCalendarPlugin.retrieveEvent(
      'fakeCalendarId',
      'eventId',
    );

    expect(result.isSuccess, true);
    expect(result.data?.title, 'Direct event');
    expect(log, <Matcher>[
      isMethodCall(
        'retrieveEvent',
        arguments: <String, dynamic>{
          'calendarId': 'fakeCalendarId',
          'eventId': 'eventId',
        },
      ),
    ]);
  });

  test('RetrieveEvent_Preserves_Platform_NotFound_Evidence', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      throw PlatformException(
        code: '404',
        message: 'The event could not be found',
      );
    });

    final result = await deviceCalendarPlugin.retrieveEvent(
      'fakeCalendarId',
      'missingEventId',
    );

    expect(result.isSuccess, false);
    expect(result.errors, hasLength(1));
    expect(result.errors.single.errorCode, ErrorCodes.notFound);
  });

  test('RetrieveEvent_Requires_Both_Identifiers', () async {
    final missingCalendar =
        await deviceCalendarPlugin.retrieveEvent(null, 'eventId');
    final missingEvent =
        await deviceCalendarPlugin.retrieveEvent('calendarId', null);

    expect(missingCalendar.isSuccess, false);
    expect(missingEvent.isSuccess, false);
    expect(
      missingCalendar.errors.first.errorCode,
      ErrorCodes.invalidArguments,
    );
    expect(missingEvent.errors.first.errorCode, ErrorCodes.invalidArguments);
  });

  test('UpdateAttendeeStatus_Returns_Successfully', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'currentStatus': AndroidAttendanceStatus.Accepted.index,
        'resultingEventId': 'new-occurrence-id',
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
    expect(result.data?.resultingEventId, 'new-occurrence-id');
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

  test('UpdateAttendeeStatus_SendsRecurringChangeTarget', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'currentStatus': AndroidAttendanceStatus.Declined.index,
        'resultingEventId': 'future-series-id',
      };
    });

    final result = await deviceCalendarPlugin.updateAttendeeStatus(
      calendarId: 'calendarId',
      eventId: 'selectedEventId',
      attendeeEmail: 'user@example.com',
      expectedStatus: AndroidAttendanceStatus.Accepted,
      newStatus: AndroidAttendanceStatus.Declined,
      recurrenceTarget: const EventRecurrenceChangeTarget(
        scope: EventRecurrenceChangeScope.thisAndFollowing,
        originalOccurrenceStartMillisecondsSinceEpoch: 123456789,
        originalEventId: 'masterEventId',
        selectedOccurrenceWasDetached: true,
      ),
    );

    expect(result.isSuccess, true);
    expect(result.data?.resultingEventId, 'future-series-id');
    expect(log, <Matcher>[
      isMethodCall(
        'updateAttendeeStatus',
        arguments: <String, dynamic>{
          'calendarId': 'calendarId',
          'eventId': 'selectedEventId',
          'attendeeEmail': 'user@example.com',
          'expectedAttendeeStatus': AndroidAttendanceStatus.Accepted.index,
          'newAttendeeStatus': AndroidAttendanceStatus.Declined.index,
          'recurrenceChangeTarget': <String, Object?>{
            'scope': 'thisAndFollowing',
            'originalOccurrenceStart': 123456789,
            'originalEventId': 'masterEventId',
            'selectedOccurrenceWasDetached': true,
          },
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
        'resultingEventId': 'new-series-id',
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
    expect(result.data?.resultingEventId, 'new-series-id');
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

  test('ApplyEventChanges_UpdatesTitleSurgically', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'conflictingFields': <String>[],
        'currentValues': <String, Object>{'title': 'Updated title'},
      };
    });

    const EventChangeSet changes = EventChangeSet(
      title: EventFieldChange<String>(
        expected: 'Previous title',
        requested: 'Updated title',
      ),
    );
    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: changes,
    );

    expect(result.isSuccess, true);
    expect(result.data?.outcome, EventChangeOutcome.updated);
    expect(result.data?.currentTitle, 'Updated title');
    expect(log, <Matcher>[
      isMethodCall(
        'applyEventChanges',
        arguments: <String, dynamic>{
          'calendarId': 'calendarId',
          'eventId': 'eventId',
          'eventChanges': <String, Object?>{
            'title': <String, Object?>{
              'expected': 'Previous title',
              'requested': 'Updated title',
            },
          },
        },
      ),
    ]);
  });

  test('ApplyEventChanges_SendsRecurringChangeTarget', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'conflictingFields': <String>[],
        'currentValues': <String, Object>{'title': 'Updated title'},
      };
    });

    const recurrenceTarget = EventRecurrenceChangeTarget(
      scope: EventRecurrenceChangeScope.thisAndFollowing,
      originalOccurrenceStartMillisecondsSinceEpoch: 1787229000000,
      originalEventId: 'masterEventId',
      selectedOccurrenceWasDetached: true,
    );
    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'detachedEventId',
      changes: const EventChangeSet(
        title: EventFieldChange<String>(
          expected: 'Previous title',
          requested: 'Updated title',
        ),
      ),
      recurrenceTarget: recurrenceTarget,
    );

    expect(result.isSuccess, true);
    expect(log, <Matcher>[
      isMethodCall(
        'applyEventChanges',
        arguments: <String, dynamic>{
          'calendarId': 'calendarId',
          'eventId': 'detachedEventId',
          'eventChanges': <String, Object?>{
            'title': <String, Object?>{
              'expected': 'Previous title',
              'requested': 'Updated title',
            },
          },
          'recurrenceChangeTarget': <String, Object?>{
            'scope': 'thisAndFollowing',
            'originalOccurrenceStart': 1787229000000,
            'originalEventId': 'masterEventId',
            'selectedOccurrenceWasDetached': true,
          },
        },
      ),
    ]);
  });

  test('ApplyEventChanges_RejectsTitleLongerThanTheSharedLimit', () async {
    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: EventChangeSet(
        title: EventFieldChange<String>(
          expected: 'Previous title',
          requested: 'x' * (EventTitleConstraints.maxLength + 1),
        ),
      ),
    );

    expect(result.isSuccess, false);
    expect(result.errors.first.errorCode, ErrorCodes.invalidArguments);
    expect(log, isEmpty);
  });

  test('ApplyEventChanges_ReturnsCurrentTitleOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['title'],
        'currentValues': <String, Object>{'title': 'Concurrent title'},
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        title: EventFieldChange<String?>(
          expected: 'Previous title',
          requested: 'Requested title',
        ),
      ),
    );

    expect(result.data?.outcome, EventChangeOutcome.conflict);
    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.title},
    );
    expect(result.data?.currentTitle, 'Concurrent title');
  });

  for (final withDiagnostics in [false, true]) {
    test('RejectionDiagnostics_RoundTrip_$withDiagnostics', () async {
      final diagnostics = <String, Object?>{
        'stage': 'event.atomicBatch',
        'expectedMismatchesAtRead': ['title'],
        'comparisonStorage': {
          'dtstart': 1788810300000,
          'dtend': null,
          'duration': 'P3600S',
          'startTimeZone': 'Europe/London'
        },
        'batchExceptionMessage': 'Expected 1 rows but actual 0 🥕',
      };
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
              channel,
              (call) async => {
                    'outcome': 'conflict',
                    if (call.method == 'updateAttendeeStatus')
                      'currentStatus': AndroidAttendanceStatus.Tentative.index,
                    'conflictingFields': ['title'],
                    'currentValues': {'title': 'test-rec'},
                    if (withDiagnostics) 'diagnostics': diagnostics,
                  });
      final event = await deviceCalendarPlugin.applyEventChanges(
        calendarId: '7',
        eventId: '20970',
        changes: const EventChangeSet(
            title: EventFieldChange(expected: 'old', requested: 'test-rec')),
      );
      expect(event.isSuccess, isTrue);
      expect(event.data!.outcome, EventChangeOutcome.conflict);
      expect(event.data!.currentTitle, 'test-rec');
      expect(event.data!.diagnostics, withDiagnostics ? diagnostics : null);
      final rsvp = await deviceCalendarPlugin.updateAttendeeStatus(
        calendarId: '7',
        eventId: '20970',
        attendeeEmail: 'me@example.com',
        expectedStatus: AndroidAttendanceStatus.Accepted,
        newStatus: AndroidAttendanceStatus.Declined,
      );
      expect(rsvp.isSuccess, isTrue);
      expect(rsvp.data!.outcome, AttendeeStatusUpdateOutcome.conflict);
      expect(rsvp.data!.currentStatus, AndroidAttendanceStatus.Tentative);
      expect(rsvp.data!.diagnostics, withDiagnostics ? diagnostics : null);
    });
  }

  test('ApplyEventChanges_ReturnsCurrentLocationOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['location'],
        'currentValues': <String, Object>{
          'location': 'Concurrent location',
        },
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        location: EventFieldChange<String?>(
          expected: 'Previous location',
          requested: 'Requested location',
        ),
      ),
    );

    expect(result.data?.outcome, EventChangeOutcome.conflict);
    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.location},
    );
    expect(result.data?.currentLocation, 'Concurrent location');
  });

  test('EventChangeSet_SerializesNullableLocationAtomically', () {
    const EventChangeSet changes = EventChangeSet(
      location: EventFieldChange<String?>(
        expected: 'Room A',
        requested: null,
      ),
    );

    expect(changes.isEmpty, false);
    expect(changes.toJson(), <String, Object?>{
      'location': <String, Object?>{
        'expected': 'Room A',
        'requested': null,
      },
    });
  });

  test('EventChangeSet_SerializesDateRangeAndTimeZonesAtomically', () {
    const EventChangeSet changes = EventChangeSet(
      dateRange: EventFieldChange<EventDateRangeValue>(
        expected: EventDateRangeValue(
          startMillisecondsSinceEpoch: 1000,
          startTimeZone: 'Europe/London',
          endMillisecondsSinceEpoch: 2000,
          endTimeZone: 'Europe/London',
          allDay: false,
        ),
        requested: EventDateRangeValue(
          startMillisecondsSinceEpoch: 3000,
          startTimeZone: 'Europe/Madrid',
          endMillisecondsSinceEpoch: 4000,
          endTimeZone: 'Europe/Madrid',
          allDay: false,
        ),
      ),
    );

    expect(changes.isEmpty, false);
    expect(changes.hasValidDateRange, true);
    expect(changes.toJson(), <String, Object?>{
      'dateRange': <String, Object?>{
        'expected': <String, Object?>{
          'startDate': 1000,
          'startTimeZone': 'Europe/London',
          'endDate': 2000,
          'endTimeZone': 'Europe/London',
          'allDay': false,
        },
        'requested': <String, Object?>{
          'startDate': 3000,
          'startTimeZone': 'Europe/Madrid',
          'endDate': 4000,
          'endTimeZone': 'Europe/Madrid',
          'allDay': false,
        },
      },
    });
  });

  test('ApplyEventChanges_UpdatesAndReturnsDateRangeAtomically', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'conflictingFields': <String>[],
        'currentValues': <String, Object>{
          'dateRange': <String, Object>{
            'startDate': 3000,
            'startTimeZone': 'Europe/Madrid',
            'endDate': 4000,
            'endTimeZone': 'Europe/Madrid',
            'allDay': false,
          },
        },
      };
    });

    const EventChangeSet changes = EventChangeSet(
      dateRange: EventFieldChange<EventDateRangeValue>(
        expected: EventDateRangeValue(
          startMillisecondsSinceEpoch: 1000,
          startTimeZone: 'Europe/London',
          endMillisecondsSinceEpoch: 2000,
          endTimeZone: 'Europe/London',
          allDay: false,
        ),
        requested: EventDateRangeValue(
          startMillisecondsSinceEpoch: 3000,
          startTimeZone: 'Europe/Madrid',
          endMillisecondsSinceEpoch: 4000,
          endTimeZone: 'Europe/Madrid',
          allDay: false,
        ),
      ),
    );

    final Result<EventChangeResult> result =
        await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: changes,
    );

    expect(result.data?.outcome, EventChangeOutcome.updated);
    expect(
      result.data?.currentDateRange?.startMillisecondsSinceEpoch,
      3000,
    );
    expect(result.data?.currentDateRange?.startTimeZone, 'Europe/Madrid');
    expect(log.single.arguments['eventChanges'], changes.toJson());
  });

  test('ApplyEventChanges_ReturnsTheWholeCurrentRangeOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['dateRange'],
        'currentValues': <String, Object>{
          'dateRange': <String, Object>{
            'startDate': 5000,
            'startTimeZone': 'Europe/Paris',
            'endDate': 6000,
            'endTimeZone': 'Europe/Paris',
            'allDay': false,
          },
        },
      };
    });

    final Result<EventChangeResult> result =
        await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        dateRange: EventFieldChange<EventDateRangeValue>(
          expected: EventDateRangeValue(
            startMillisecondsSinceEpoch: 1000,
            startTimeZone: 'Europe/London',
            endMillisecondsSinceEpoch: 2000,
            endTimeZone: 'Europe/London',
            allDay: false,
          ),
          requested: EventDateRangeValue(
            startMillisecondsSinceEpoch: 3000,
            startTimeZone: 'Europe/Madrid',
            endMillisecondsSinceEpoch: 4000,
            endTimeZone: 'Europe/Madrid',
            allDay: false,
          ),
        ),
      ),
    );

    expect(result.data?.outcome, EventChangeOutcome.conflict);
    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.dateRange},
    );
    expect(result.data?.currentDateRange?.startTimeZone, 'Europe/Paris');
    expect(result.data?.currentDateRange?.endMillisecondsSinceEpoch, 6000);
  });

  test('EventChangeSet_RejectsAnEndBeforeItsStart', () async {
    final Result<EventChangeResult> result =
        await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        dateRange: EventFieldChange<EventDateRangeValue>(
          expected: EventDateRangeValue(
            startMillisecondsSinceEpoch: 1000,
            startTimeZone: 'Europe/London',
            endMillisecondsSinceEpoch: 2000,
            endTimeZone: 'Europe/London',
            allDay: false,
          ),
          requested: EventDateRangeValue(
            startMillisecondsSinceEpoch: 4000,
            startTimeZone: 'Europe/Madrid',
            endMillisecondsSinceEpoch: 3000,
            endTimeZone: 'Europe/Madrid',
            allDay: false,
          ),
        ),
      ),
    );

    expect(result.isSuccess, false);
    expect(result.errors.first.errorCode, ErrorCodes.invalidArguments);
    expect(log, isEmpty);
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

  test('EventChangeSet_SerializesRemindersAsOneOptimisticField', () {
    const EventChangeSet changes = EventChangeSet(
      reminders: EventFieldChange<List<EventReminderValue>>(
        expected: <EventReminderValue>[
          EventReminderValue(minutes: 30, method: 2),
        ],
        requested: <EventReminderValue>[
          EventReminderValue(minutes: 10, method: 1),
          EventReminderValue(minutes: 30, method: 2),
        ],
      ),
    );

    expect(changes.isEmpty, false);
    expect(changes.hasValidReminders, true);
    expect(changes.toJson(), <String, Object?>{
      'reminders': <String, Object?>{
        'expected': <Object?>[
          <String, Object?>{'minutes': 30, 'method': 2},
        ],
        'requested': <Object?>[
          <String, Object?>{'minutes': 10, 'method': 1},
          <String, Object?>{'minutes': 30, 'method': 2},
        ],
      },
    });
  });

  test('EventChangeSet_SerializesRecurrenceAndExplicitRemoval', () {
    final RecurrenceRule weekly = RecurrenceRule(
      frequency: Frequency.weekly,
      byWeekDays: <ByWeekDayEntry>[
        ByWeekDayEntry(DateTime.monday),
        ByWeekDayEntry(DateTime.friday),
      ],
    );
    final EventChangeSet changes = EventChangeSet(
      recurrence: EventFieldChange<EventRecurrenceValue>(
        expected: EventRecurrenceValue(rule: weekly),
        requested: const EventRecurrenceValue(rule: null),
      ),
    );

    expect(changes.isEmpty, false);
    expect(changes.toJson(), <String, Object?>{
      'recurrence': <String, Object?>{
        'expected': <String, Object?>{'rule': weekly.toJson()},
        'requested': <String, Object?>{'rule': null},
      },
    });
  });

  test('ApplyEventChanges_ReturnsCurrentRecurrenceOnConflict', () async {
    final RecurrenceRule current = RecurrenceRule(
      frequency: Frequency.monthly,
      byMonthDays: const <int>[19],
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['recurrence'],
        'currentValues': <String, Object>{
          'recurrence': <String, Object?>{'rule': current.toJson()},
        },
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: EventChangeSet(
        recurrence: EventFieldChange<EventRecurrenceValue>(
          expected: EventRecurrenceValue(
            rule: RecurrenceRule(frequency: Frequency.weekly),
          ),
          requested: EventRecurrenceValue(
            rule: RecurrenceRule(frequency: Frequency.daily),
          ),
        ),
      ),
    );

    expect(result.data?.outcome, EventChangeOutcome.conflict);
    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.recurrence},
    );
    expect(result.data?.currentRecurrence?.rule.toString(), current.toString());
  });

  test('ApplyEventChanges_ReturnsCurrentRemindersOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['reminders'],
        'currentValues': <String, Object>{
          'reminders': <Object>[
            <String, Object>{'minutes': 15, 'method': 1},
            <String, Object>{'minutes': 60, 'method': 2},
          ],
        },
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        reminders: EventFieldChange<List<EventReminderValue>>(
          expected: <EventReminderValue>[],
          requested: <EventReminderValue>[
            EventReminderValue(minutes: 10, method: 1),
          ],
        ),
      ),
    );

    expect(result.data?.outcome, EventChangeOutcome.conflict);
    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.reminders},
    );
    expect(result.data?.currentReminders?.length, 2);
    expect(result.data?.currentReminders?.first.minutes, 15);
    expect(result.data?.currentReminders?.last.method, 2);
  });

  test('EventChangeSet_SerializesAttendeesAsOneOptimisticField', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      return <String, Object>{
        'outcome': 'updated',
        'conflictingFields': <String>[],
        'currentValues': <String, Object>{
          'attendees': <Object>[
            <String, Object>{
              'name': 'Bob',
              'email': 'bob@example.com',
              'role': 1,
            },
          ],
        },
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        attendees: EventFieldChange<List<EventAttendeeValue>>(
          expected: <EventAttendeeValue>[],
          requested: <EventAttendeeValue>[
            EventAttendeeValue(
              name: 'Bob',
              email: 'bob@example.com',
              role: 1,
            ),
          ],
        ),
      ),
    );

    expect(result.data?.currentAttendees?.single.email, 'bob@example.com');
    expect(
      log.single.arguments['eventChanges']['attendees']['requested'],
      <Object>[
        <String, Object?>{
          'name': 'Bob',
          'email': 'bob@example.com',
          'role': 1,
        },
      ],
    );
  });

  test('ApplyEventChanges_ReturnsCurrentAttendeesOnConflict', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, Object>{
        'outcome': 'conflict',
        'conflictingFields': <String>['attendees'],
        'currentValues': <String, Object>{
          'attendees': <Object>[
            <String, Object>{
              'name': 'Alice',
              'email': 'alice@example.com',
              'role': 2,
            },
          ],
        },
      };
    });

    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        attendees: EventFieldChange<List<EventAttendeeValue>>(
          expected: <EventAttendeeValue>[],
          requested: <EventAttendeeValue>[],
        ),
      ),
    );

    expect(
      result.data?.conflictingFields,
      <EventChangeField>{EventChangeField.attendees},
    );
    expect(result.data?.currentAttendees?.single.name, 'Alice');
    expect(result.data?.currentAttendees?.single.role, 2);
  });

  test('ApplyEventChanges_RejectsInvalidReminders', () async {
    final result = await deviceCalendarPlugin.applyEventChanges(
      calendarId: 'calendarId',
      eventId: 'eventId',
      changes: const EventChangeSet(
        reminders: EventFieldChange<List<EventReminderValue>>(
          expected: <EventReminderValue>[],
          requested: <EventReminderValue>[
            EventReminderValue(minutes: -1, method: 1),
          ],
        ),
      ),
    );

    expect(result.isSuccess, false);
    expect(result.errors.first.errorCode, ErrorCodes.invalidArguments);
    expect(log, isEmpty);
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
    final reminder = Reminder(minutes: 10, method: ReminderMethod.email);
    var event = Event(
      'calendarId',
      eventId: 'eventId',
      syncId: 'remote-sync-id',
      uid2445: 'rfc-event-uid',
      originalSyncId: 'remote-original-sync-id',
      isDirty: true,
      isDeleted: true,
      mutators: 'calendar.sync.adapter',
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
    expect(newEvent.syncId, equals(event.syncId));
    expect(newEvent.uid2445, equals(event.uid2445));
    expect(newEvent.originalSyncId, equals(event.originalSyncId));
    expect(newEvent.isDirty, isTrue);
    expect(newEvent.isDeleted, isTrue);
    expect(newEvent.mutators, equals(event.mutators));
    expect(stringEvent['syncId'], equals('remote-sync-id'));
    expect(stringEvent['uid2445'], equals('rfc-event-uid'));
    expect(
      stringEvent['originalSyncId'],
      equals('remote-original-sync-id'),
    );
    expect(stringEvent['eventIsDirty'], isTrue);
    expect(stringEvent['eventIsDeleted'], isTrue);
    expect(stringEvent['eventMutators'], equals('calendar.sync.adapter'));
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
    expect(newEvent.reminders?.single.method, ReminderMethod.email);
    expect(newEvent.availability, equals(event.availability));
    expect(newEvent.status, equals(event.status));
    expect(newEvent.color, equals(event.color));
    expect(newEvent.colorKey, equals(event.colorKey));
  });
}
