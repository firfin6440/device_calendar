import '../common/calendar_enums.dart';
import 'reminder.dart';

enum CalendarEntityType {
  event,
  reminder,
  unknown,
}

enum CalendarPlatformType {
  local,
  calDav,
  exchange,
  subscription,
  birthday,
  unknown,
}

/// A calendar on the user's device
class Calendar {
  /// Read-only. The unique identifier for this calendar
  String? id;

  /// The name of this calendar
  String? name;

  /// Read-only. If the calendar is read-only
  bool? isReadOnly;

  /// Read-only. If the calendar is the default
  bool? isDefault;

  /// Read-only. Color of the calendar
  int? color;

  // Read-only. Account name associated with the calendar
  String? accountName;

  // Read-only. Account type associated with the calendar
  String? accountType;

  /// Read-only. The account which owns this calendar.
  String? ownerAccount;

  /// Read-only. The provider-specific access level, when available.
  int? accessLevel;

  /// Read-only. The calendar's configured time zone, when available.
  String? timeZone;

  /// Read-only. The maximum number of reminders allowed on one event.
  int? maxReminders;

  /// Read-only. Reminder delivery methods supported by this calendar.
  ///
  /// A null value means that the platform or provider did not report this
  /// capability. An empty list means that it explicitly supports none.
  List<ReminderMethod>? allowedReminderMethods;

  /// Read-only. Availability values supported by this calendar.
  List<Availability>? allowedAvailabilities;

  /// Read-only. Attendee types supported by this calendar.
  List<AttendeeRole>? allowedAttendeeTypes;

  /// Read-only. Whether event time zones may be modified.
  bool? canModifyTimeZone;

  /// Read-only. Whether an event organiser may RSVP to their own event.
  bool? canOrganizerRespond;

  /// Read-only. Whether this calendar is selected for display.
  bool? isVisible;

  /// Read-only. Whether this calendar is synchronized to the device.
  bool? isSyncEnabled;

  /// Read-only. The calendar's provider-defined location.
  String? location;

  /// Read-only. The provider color key associated with this calendar.
  String? colorKey;

  /// Read-only. Whether the calendar's own properties are immutable.
  bool? isImmutable;

  /// Read-only. Whether this is a subscribed calendar.
  bool? isSubscribed;

  /// Read-only. The platform-specific kind of calendar.
  CalendarPlatformType? platformType;

  /// Read-only. Entity types which this calendar can contain.
  List<CalendarEntityType>? allowedEntityTypes;

  Calendar({
    this.id,
    this.name,
    this.isReadOnly,
    this.isDefault,
    this.color,
    this.accountName,
    this.accountType,
    this.ownerAccount,
    this.accessLevel,
    this.timeZone,
    this.maxReminders,
    this.allowedReminderMethods,
    this.allowedAvailabilities,
    this.allowedAttendeeTypes,
    this.canModifyTimeZone,
    this.canOrganizerRespond,
    this.isVisible,
    this.isSyncEnabled,
    this.location,
    this.colorKey,
    this.isImmutable,
    this.isSubscribed,
    this.platformType,
    this.allowedEntityTypes,
  });

  Calendar.fromJson(Map<String, dynamic> json) {
    id = json['id'];
    name = json['name'];
    isReadOnly = json['isReadOnly'];
    isDefault = json['isDefault'];
    color = json['color'];
    accountName = json['accountName'];
    accountType = json['accountType'];
    ownerAccount = json['ownerAccount'];
    accessLevel = (json['accessLevel'] as num?)?.toInt();
    timeZone = json['timeZone'];
    maxReminders = (json['maxReminders'] as num?)?.toInt();
    allowedReminderMethods = _parseList(
      json['allowedReminderMethods'],
      (Object? value) => reminderMethodFromValue(value),
    );
    allowedAvailabilities = _parseList(
      json['allowedAvailabilities'],
      _availabilityFromValue,
    );
    allowedAttendeeTypes = _parseList(
      json['allowedAttendeeTypes'],
      (Object? value) => _enumByIndex(AttendeeRole.values, value),
    );
    canModifyTimeZone = json['canModifyTimeZone'];
    canOrganizerRespond = json['canOrganizerRespond'];
    isVisible = json['isVisible'];
    isSyncEnabled = json['isSyncEnabled'];
    location = json['location'];
    colorKey = json['colorKey']?.toString();
    isImmutable = json['isImmutable'];
    isSubscribed = json['isSubscribed'];
    platformType = _enumByName(
          CalendarPlatformType.values,
          json['platformType'],
        ) ??
        (json['platformType'] == null ? null : CalendarPlatformType.unknown);
    allowedEntityTypes = _parseList(
      json['allowedEntityTypes'],
      (Object? value) =>
          _enumByName(CalendarEntityType.values, value) ??
          CalendarEntityType.unknown,
    );
  }

  Map<String, dynamic> toJson() {
    final data = <String, dynamic>{
      'id': id,
      'name': name,
      'isReadOnly': isReadOnly,
      'isDefault': isDefault,
      'color': color,
      'accountName': accountName,
      'accountType': accountType,
      'ownerAccount': ownerAccount,
      'accessLevel': accessLevel,
      'timeZone': timeZone,
      'maxReminders': maxReminders,
      'allowedReminderMethods':
          allowedReminderMethods?.map((method) => method.value).toList(),
      'allowedAvailabilities':
          allowedAvailabilities?.map((value) => value.name).toList(),
      'allowedAttendeeTypes':
          allowedAttendeeTypes?.map((type) => type.index).toList(),
      'canModifyTimeZone': canModifyTimeZone,
      'canOrganizerRespond': canOrganizerRespond,
      'isVisible': isVisible,
      'isSyncEnabled': isSyncEnabled,
      'location': location,
      'colorKey': colorKey,
      'isImmutable': isImmutable,
      'isSubscribed': isSubscribed,
      'platformType': platformType?.name,
      'allowedEntityTypes':
          allowedEntityTypes?.map((type) => type.name).toList(),
    };

    return data;
  }
}

List<T>? _parseList<T>(
  Object? raw,
  T? Function(Object? value) parse,
) {
  if (raw is! List) return null;
  return raw.map(parse).whereType<T>().toList(growable: false);
}

T? _enumByIndex<T>(List<T> values, Object? raw) {
  final int? index = raw is num ? raw.toInt() : int.tryParse('$raw');
  if (index == null || index < 0 || index >= values.length) return null;
  return values[index];
}

T? _enumByName<T extends Enum>(List<T> values, Object? raw) {
  final String name = raw?.toString() ?? '';
  for (final T value in values) {
    if (value.name.toLowerCase() == name.toLowerCase()) return value;
  }
  return null;
}

Availability? _availabilityFromValue(Object? raw) {
  final String name = raw?.toString().toLowerCase() ?? '';
  for (final Availability availability in Availability.values) {
    if (availability.name.toLowerCase() == name) return availability;
  }
  return null;
}
