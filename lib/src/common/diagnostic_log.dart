import 'package:flutter/foundation.dart';

/// Fixed local diagnostic codes only. Raw provider data belongs to result.data;
/// neither that data nor exception text may cross this output boundary.
enum DeviceCalendarDiagnostic { configurationFailed, channelFailed }

void deviceCalendarDiagnostic(DeviceCalendarDiagnostic code) {
  if (!kDebugMode) return;
  try {
    debugPrint('[DEVICE-CALENDAR] ${code.name}');
  } catch (_) {
    // Reporting failure cannot replace the provider result.
  }
}
