/// The provider's actual saved calendar metadata, including rejected readback.
class CalendarColorUpdateResult {
  const CalendarColorUpdateResult({required this.applied, required this.color,
    required this.colorKey});
  final bool applied;
  final int? color;
  final String? colorKey;

  factory CalendarColorUpdateResult.fromMap(dynamic value) {
    if (value is! Map || value['applied'] is! bool ||
        !value.containsKey('color') || !value.containsKey('colorKey') ||
        (value['color'] != null && value['color'] is! int) ||
        (value['colorKey'] != null && value['colorKey'] is! String)) {
      throw const FormatException('Invalid calendar colour readback');
    }
    return CalendarColorUpdateResult(applied: value['applied'] as bool,
      color: value['color'] as int?, colorKey: value['colorKey'] as String?);
  }
}
