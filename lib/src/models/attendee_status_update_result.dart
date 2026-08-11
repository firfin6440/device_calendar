import 'platform_specifics/android/attendance_status.dart';

/// The outcome of an optimistic attendee-status update.
enum AttendeeStatusUpdateOutcome {
  /// The stored status matched the expected status and was updated.
  updated,

  /// The stored status already matched the requested status.
  alreadyCurrent,

  /// The stored status changed after it was read by the caller.
  conflict,
}

class AttendeeStatusUpdateResult {
  final AttendeeStatusUpdateOutcome outcome;
  final AndroidAttendanceStatus currentStatus;

  const AttendeeStatusUpdateResult({
    required this.outcome,
    required this.currentStatus,
  });
}
