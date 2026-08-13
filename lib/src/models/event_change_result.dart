import 'event_change_set.dart';

enum EventChangeOutcome {
  updated,
  alreadyCurrent,
  conflict,
}

enum EventChangeField {
  color,
}

class EventChangeResult {
  final EventChangeOutcome outcome;
  final Set<EventChangeField> conflictingFields;
  final EventColorValue? currentColor;

  const EventChangeResult({
    required this.outcome,
    this.conflictingFields = const <EventChangeField>{},
    this.currentColor,
  });
}
