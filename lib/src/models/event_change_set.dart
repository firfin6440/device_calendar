import 'package:characters/characters.dart';
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
  final EventFieldChange<EventDateRangeValue>? dateRange;
  final int titleMaxLength;

  const EventChangeSet({
    this.color,
    this.title,
    this.dateRange,
    this.titleMaxLength = EventTitleConstraints.maxLength,
  }) : assert(titleMaxLength > 0);

  bool get isEmpty => color == null && title == null && dateRange == null;

  bool get hasValidTitleLength {
    final String? requestedTitle = title?.requested;
    return requestedTitle == null ||
        EventTitleConstraints.characterCount(requestedTitle) <= titleMaxLength;
  }

  bool get hasValidDateRange =>
      dateRange == null ||
      (dateRange!.expected.isChronological &&
          dateRange!.requested.isChronological);

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
      if (dateRange != null)
        'dateRange': <String, Object?>{
          'expected': dateRange!.expected.toJson(),
          'requested': dateRange!.requested.toJson(),
        },
    };
  }
}
