import 'package:flutter/foundation.dart';

enum ReminderMethod {
  defaultMethod,
  alert,
  email,
  sms,
  alarm,
  unknown,
}

extension ReminderMethodValue on ReminderMethod {
  int get value {
    switch (this) {
      case ReminderMethod.defaultMethod:
        return 0;
      case ReminderMethod.alert:
        return 1;
      case ReminderMethod.email:
        return 2;
      case ReminderMethod.sms:
        return 3;
      case ReminderMethod.alarm:
        return 4;
      case ReminderMethod.unknown:
        return -1;
    }
  }
}

ReminderMethod reminderMethodFromValue(Object? value) {
  final int? numericValue = value is num ? value.toInt() : null;
  return ReminderMethod.values.firstWhere(
    (ReminderMethod method) => method.value == numericValue,
    orElse: () => ReminderMethod.unknown,
  );
}

class Reminder {
  /// The time when the reminder should be triggered expressed in terms of minutes before the start of the event
  int? minutes;

  /// How the calendar provider delivers this reminder.
  ///
  /// Older plugin responses did not include a method. They are treated as
  /// notification alerts to preserve the previous behaviour.
  ReminderMethod method;

  Reminder({@required this.minutes, this.method = ReminderMethod.alert})
      : assert(minutes != null && minutes >= 0,
            'Minutes must be greater than or equal than zero');

  Reminder.fromJson(Map<String, dynamic> json)
      : minutes = json['minutes'] as int,
        method = json.containsKey('method')
            ? reminderMethodFromValue(json['method'])
            : ReminderMethod.alert;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'minutes': minutes,
      'method': method.value,
    };
  }
}
