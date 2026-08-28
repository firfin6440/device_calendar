import 'package:device_calendar/device_calendar.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Calendar parses and serializes provider capabilities', () {
    final Calendar calendar = Calendar.fromJson(<String, dynamic>{
      'id': 'calendar-id',
      'name': 'Work',
      'isReadOnly': false,
      'isDefault': true,
      'color': 0xff123456,
      'accountName': 'person@example.com',
      'accountType': 'com.google',
      'ownerAccount': 'owner@example.com',
      'accessLevel': 700,
      'timeZone': 'Europe/London',
      'maxReminders': 5,
      'allowedReminderMethods': <int>[0, 1, 2],
      'allowedAvailabilities': <String>['Busy', 'Free', 'Tentative'],
      'allowedAttendeeTypes': <int>[0, 1, 2, 3],
      'canModifyTimeZone': true,
      'canOrganizerRespond': false,
      'isVisible': true,
      'isSyncEnabled': true,
      'location': 'London',
      'colorKey': '11',
      'isImmutable': false,
      'isSubscribed': true,
      'platformType': 'calDav',
      'allowedEntityTypes': <String>['event', 'reminder'],
    });

    expect(calendar.ownerAccount, 'owner@example.com');
    expect(calendar.accessLevel, 700);
    expect(calendar.timeZone, 'Europe/London');
    expect(calendar.maxReminders, 5);
    expect(
      calendar.allowedReminderMethods,
      <ReminderMethod>[
        ReminderMethod.defaultMethod,
        ReminderMethod.alert,
        ReminderMethod.email,
      ],
    );
    expect(
      calendar.allowedAvailabilities,
      <Availability>[
        Availability.Busy,
        Availability.Free,
        Availability.Tentative,
      ],
    );
    expect(calendar.allowedAttendeeTypes, AttendeeRole.values);
    expect(calendar.canModifyTimeZone, isTrue);
    expect(calendar.canOrganizerRespond, isFalse);
    expect(calendar.isVisible, isTrue);
    expect(calendar.isSyncEnabled, isTrue);
    expect(calendar.location, 'London');
    expect(calendar.colorKey, '11');
    expect(calendar.isImmutable, isFalse);
    expect(calendar.isSubscribed, isTrue);
    expect(calendar.platformType, CalendarPlatformType.calDav);
    expect(
      calendar.allowedEntityTypes,
      <CalendarEntityType>[
        CalendarEntityType.event,
        CalendarEntityType.reminder,
      ],
    );

    final Map<String, dynamic> encoded = calendar.toJson();
    expect(encoded['maxReminders'], 5);
    expect(encoded['allowedReminderMethods'], <int>[0, 1, 2]);
    expect(encoded['allowedAvailabilities'],
        <String>['Busy', 'Free', 'Tentative']);
    expect(encoded['allowedAttendeeTypes'], <int>[0, 1, 2, 3]);
    expect(encoded['platformType'], 'calDav');
    expect(encoded['allowedEntityTypes'], <String>['event', 'reminder']);
  });

  test('Calendar preserves unknown versus explicitly empty capabilities', () {
    final Calendar unknown = Calendar.fromJson(<String, dynamic>{});
    final Calendar explicitlyEmpty = Calendar.fromJson(<String, dynamic>{
      'allowedReminderMethods': <int>[],
      'allowedAvailabilities': <String>[],
      'allowedAttendeeTypes': <int>[],
      'allowedEntityTypes': <String>[],
    });

    expect(unknown.allowedReminderMethods, isNull);
    expect(unknown.allowedAvailabilities, isNull);
    expect(unknown.allowedAttendeeTypes, isNull);
    expect(unknown.allowedEntityTypes, isNull);
    expect(explicitlyEmpty.allowedReminderMethods, isEmpty);
    expect(explicitlyEmpty.allowedAvailabilities, isEmpty);
    expect(explicitlyEmpty.allowedAttendeeTypes, isEmpty);
    expect(explicitlyEmpty.allowedEntityTypes, isEmpty);
  });
}
