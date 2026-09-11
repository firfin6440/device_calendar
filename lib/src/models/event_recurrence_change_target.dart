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

  /// Android: atomically flatten detached rows when editing the entire series,
  /// including field-only edits. Defaults to the existing native behavior.
  final bool resetDetachedOverrides;

  const EventRecurrenceChangeTarget({
    required this.scope,
    required this.originalOccurrenceStartMillisecondsSinceEpoch,
    required this.originalEventId,
    required this.selectedOccurrenceWasDetached,
    this.resetDetachedOverrides = false,
  });

  Map<String, Object?> toJson() => <String, Object?>{
        'scope': scope.name,
        'originalOccurrenceStart':
            originalOccurrenceStartMillisecondsSinceEpoch,
        'originalEventId': originalEventId,
        'selectedOccurrenceWasDetached': selectedOccurrenceWasDetached,
        if (resetDetachedOverrides) 'resetDetachedOverrides': true,
      };
}
