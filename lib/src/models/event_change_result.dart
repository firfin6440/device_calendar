import 'event_change_set.dart';

enum EventChangeOutcome {
  updated,
  alreadyCurrent,
  conflict,
}

enum EventChangeField {
  color,
  title,
  location,
  dateRange,
  reminders,
  resources,
  recurrence,
}

class EventChangeResult {
  final EventChangeOutcome outcome;
  final Set<EventChangeField> conflictingFields;
  final EventColorValue? currentColor;
  final String? currentTitle;
  final String? currentLocation;
  final EventDateRangeValue? currentDateRange;
  final List<EventReminderValue>? currentReminders;
  final List<EventResourceValue>? currentResources;
  final EventRecurrenceValue? currentRecurrence;
  final String? resultingEventId;

  const EventChangeResult({
    required this.outcome,
    this.conflictingFields = const <EventChangeField>{},
    this.currentColor,
    this.currentTitle,
    this.currentLocation,
    this.currentDateRange,
    this.currentReminders,
    this.currentResources,
    this.currentRecurrence,
    this.resultingEventId,
  });
}
