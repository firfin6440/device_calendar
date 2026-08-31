import 'package:characters/characters.dart';
import 'package:rrule/rrule.dart';
import 'package:timezone/timezone.dart';

class EventFieldChange<T> {
  final T expected;
  final T requested;

  const EventFieldChange({
    required this.expected,
    required this.requested,
  });
}

class EventColorValue {
  final int? color;
  final int? colorKey;

  const EventColorValue({
    required this.color,
    required this.colorKey,
  });

  Map<String, Object?> toJson() => <String, Object?>{
        'color': color,
        'colorKey': colorKey,
      };

  factory EventColorValue.fromJson(Map<Object?, Object?> json) {
    return EventColorValue(
      color: json['color'] as int?,
      colorKey: json['colorKey'] as int?,
    );
  }
}

class EventReminderValue {
  final int minutes;
  final int method;

  const EventReminderValue({
    required this.minutes,
    required this.method,
  });

  factory EventReminderValue.fromJson(Map<Object?, Object?> json) {
    return EventReminderValue(
      minutes: json['minutes'] as int,
      method: json['method'] as int,
    );
  }

  Map<String, Object?> toJson() => <String, Object?>{
        'minutes': minutes,
        'method': method,
      };
}

class EventResourceValue {
  final String? name;
  final String? email;

  const EventResourceValue({this.name, this.email});

  factory EventResourceValue.fromJson(Map<Object?, Object?> json) =>
      EventResourceValue(
        name: json['name'] as String?,
        email: json['email'] as String?,
      );

  Map<String, Object?> toJson() => <String, Object?>{
        'name': name,
        'email': email,
      };
}

class EventAttendeeValue {
  final String? name;
  final String email;
  final int role;

  const EventAttendeeValue({
    this.name,
    required this.email,
    required this.role,
  });

  factory EventAttendeeValue.fromJson(Map<Object?, Object?> json) =>
      EventAttendeeValue(
        name: json['name'] as String?,
        email: json['email'] as String,
        role: json['role'] as int,
      );

  Map<String, Object?> toJson() => <String, Object?>{
        'name': name,
        'email': email,
        'role': role,
      };
}

/// A recurrence value carried by an optimistic event change.
///
/// The wrapper is intentional: a `null` [rule] means "make this event
/// non-recurring", while a missing recurrence change means "do not touch the
/// recurrence".
class EventRecurrenceValue {
  final RecurrenceRule? rule;

  const EventRecurrenceValue({required this.rule});

  factory EventRecurrenceValue.fromJson(Map<Object?, Object?> json) {
    final Object? rawRule = json['rule'];
    return EventRecurrenceValue(
      rule: rawRule is Map
          ? RecurrenceRule.fromJson(
              Map<String, dynamic>.from(rawRule),
            )
          : null,
    );
  }

  Map<String, Object?> toJson() => <String, Object?>{
        'rule': rule?.toJson(),
      };
}

class EventDateRangeValue {
  final int startMillisecondsSinceEpoch;
  final String startTimeZone;
  final int endMillisecondsSinceEpoch;
  final String endTimeZone;
  final bool allDay;

  const EventDateRangeValue({
    required this.startMillisecondsSinceEpoch,
    required this.startTimeZone,
    required this.endMillisecondsSinceEpoch,
    required this.endTimeZone,
    required this.allDay,
  });

  factory EventDateRangeValue.fromDateTimes({
    required TZDateTime start,
    required TZDateTime end,
    required bool allDay,
  }) {
    return EventDateRangeValue(
      startMillisecondsSinceEpoch: start.millisecondsSinceEpoch,
      startTimeZone: start.location.name,
      endMillisecondsSinceEpoch: end.millisecondsSinceEpoch,
      endTimeZone: end.location.name,
      allDay: allDay,
    );
  }

  factory EventDateRangeValue.fromJson(Map<Object?, Object?> json) {
    return EventDateRangeValue(
      startMillisecondsSinceEpoch: json['startDate'] as int,
      startTimeZone: json['startTimeZone'] as String,
      endMillisecondsSinceEpoch: json['endDate'] as int,
      endTimeZone: json['endTimeZone'] as String,
      allDay: json['allDay'] as bool,
    );
  }

  bool get isChronological =>
      endMillisecondsSinceEpoch >= startMillisecondsSinceEpoch;

  Map<String, Object?> toJson() => <String, Object?>{
        'startDate': startMillisecondsSinceEpoch,
        'startTimeZone': startTimeZone,
        'endDate': endMillisecondsSinceEpoch,
        'endTimeZone': endTimeZone,
        'allDay': allDay,
      };
}

class EventTitleConstraints {
  static const int maxLength = 1024;

  static int characterCount(String value) => value.characters.length;

  const EventTitleConstraints._();
}

class EventChangeSet {
  final EventFieldChange<EventColorValue>? color;
  final EventFieldChange<String?>? title;
  final EventFieldChange<String?>? location;
  final EventFieldChange<EventDateRangeValue>? dateRange;
  final EventFieldChange<List<EventReminderValue>>? reminders;
  final EventFieldChange<List<EventAttendeeValue>>? attendees;
  final EventFieldChange<List<EventResourceValue>>? resources;
  final EventFieldChange<EventRecurrenceValue>? recurrence;
  final int titleMaxLength;

  const EventChangeSet({
    this.color,
    this.title,
    this.location,
    this.dateRange,
    this.reminders,
    this.attendees,
    this.resources,
    this.recurrence,
    this.titleMaxLength = EventTitleConstraints.maxLength,
  }) : assert(titleMaxLength > 0);

  bool get isEmpty =>
      color == null &&
      title == null &&
      location == null &&
      dateRange == null &&
      reminders == null &&
      attendees == null &&
      resources == null &&
      recurrence == null;

  bool get hasValidTitleLength {
    final String? requestedTitle = title?.requested;
    return requestedTitle == null ||
        EventTitleConstraints.characterCount(requestedTitle) <= titleMaxLength;
  }

  bool get hasValidDateRange =>
      dateRange == null ||
      (dateRange!.expected.isChronological &&
          dateRange!.requested.isChronological);

  bool get hasValidReminders =>
      reminders == null ||
      <EventReminderValue>[
        ...reminders!.expected,
        ...reminders!.requested,
      ].every((EventReminderValue reminder) =>
          reminder.minutes >= 0 && reminder.method >= 0);

  Map<String, Object?> toJson() {
    final EventFieldChange<EventColorValue>? colorChange = color;
    return <String, Object?>{
      if (colorChange != null)
        'color': <String, Object?>{
          'expected': colorChange.expected.toJson(),
          'requested': colorChange.requested.toJson(),
        },
      if (title != null)
        'title': <String, Object?>{
          'expected': title!.expected,
          'requested': title!.requested,
        },
      if (location != null)
        'location': <String, Object?>{
          'expected': location!.expected,
          'requested': location!.requested,
        },
      if (dateRange != null)
        'dateRange': <String, Object?>{
          'expected': dateRange!.expected.toJson(),
          'requested': dateRange!.requested.toJson(),
        },
      if (reminders != null)
        'reminders': <String, Object?>{
          'expected': reminders!.expected
              .map((EventReminderValue reminder) => reminder.toJson())
              .toList(growable: false),
          'requested': reminders!.requested
              .map((EventReminderValue reminder) => reminder.toJson())
              .toList(growable: false),
        },
      if (attendees != null)
        'attendees': <String, Object?>{
          'expected': attendees!.expected
              .map((EventAttendeeValue attendee) => attendee.toJson())
              .toList(growable: false),
          'requested': attendees!.requested
              .map((EventAttendeeValue attendee) => attendee.toJson())
              .toList(growable: false),
        },
      if (resources != null)
        'resources': <String, Object?>{
          'expected': resources!.expected
              .map((EventResourceValue resource) => resource.toJson())
              .toList(growable: false),
          'requested': resources!.requested
              .map((EventResourceValue resource) => resource.toJson())
              .toList(growable: false),
        },
      if (recurrence != null)
        'recurrence': <String, Object?>{
          'expected': recurrence!.expected.toJson(),
          'requested': recurrence!.requested.toJson(),
        },
    };
  }
}
