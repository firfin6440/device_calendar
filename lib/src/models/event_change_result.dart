import 'event_change_set.dart';

enum EventChangeOutcome {
  updated,
  alreadyCurrent,
  conflict,
}

enum EventChangeField {
  color,
  title,
  dateRange,
  reminders,
}

class EventChangeResult {
  final EventChangeOutcome outcome;
  final Set<EventChangeField> conflictingFields;
  final EventColorValue? currentColor;
  final String? currentTitle;
  final EventDateRangeValue? currentDateRange;
  final List<EventReminderValue>? currentReminders;
  final String? resultingEventId;

  const EventChangeResult({
    required this.outcome,
    this.conflictingFields = const <EventChangeField>{},
    this.currentColor,
    this.currentTitle,
    this.currentDateRange,
    this.currentReminders,
    this.resultingEventId,
  });
}
