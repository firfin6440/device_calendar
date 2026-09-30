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

  /// Complete source cancellation inventory used by structural planning.
  /// Native compares this before its guarded atomic reset. Null is legacy.
  final List<int>? expectedCancelledOccurrenceStarts;

  /// Android: reject exception creation before any write unless the master
  /// has a usable sync identity; asserted again inside the atomic batch.
  final bool requireExceptionSyncIdentity;

  const EventRecurrenceChangeTarget({
    required this.scope,
    required this.originalOccurrenceStartMillisecondsSinceEpoch,
    required this.originalEventId,
    required this.selectedOccurrenceWasDetached,
    this.resetDetachedOverrides = false,
    this.expectedCancelledOccurrenceStarts,
    this.requireExceptionSyncIdentity = false,
  });

  Map<String, Object?> toJson() => <String, Object?>{
        'scope': scope.name,
        'originalOccurrenceStart':
            originalOccurrenceStartMillisecondsSinceEpoch,
        'originalEventId': originalEventId,
        'selectedOccurrenceWasDetached': selectedOccurrenceWasDetached,
        if (resetDetachedOverrides) 'resetDetachedOverrides': true,
        if (expectedCancelledOccurrenceStarts != null)
          'expectedCancelledOccurrenceStarts': expectedCancelledOccurrenceStarts,
        if (requireExceptionSyncIdentity) 'requireExceptionSyncIdentity': true,
      };
}
