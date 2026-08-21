enum EventRecurrenceChangeScope {
  thisOccurrence,
  thisAndFollowing,
  entireSeries,
}

/// Identifies which part of a recurring series an atomic event change targets.
class EventRecurrenceChangeTarget {
  final EventRecurrenceChangeScope scope;
  final int originalOccurrenceStartMillisecondsSinceEpoch;
  final String? originalEventId;
  final bool selectedOccurrenceWasDetached;

  const EventRecurrenceChangeTarget({
    required this.scope,
    required this.originalOccurrenceStartMillisecondsSinceEpoch,
    required this.originalEventId,
    required this.selectedOccurrenceWasDetached,
  });

  Map<String, Object?> toJson() => <String, Object?>{
        'scope': scope.name,
        'originalOccurrenceStart':
            originalOccurrenceStartMillisecondsSinceEpoch,
        'originalEventId': originalEventId,
        'selectedOccurrenceWasDetached': selectedOccurrenceWasDetached,
      };
}
