class EventFieldChange<T> {
  final T expected;
  final T requested;

  const EventFieldChange({
    required this.expected,
    required this.requested,
  });
}

class EventColorValue {
  final int? color;
  final int? colorKey;

  const EventColorValue({
    required this.color,
    required this.colorKey,
  });

  Map<String, Object?> toJson() => <String, Object?>{
        'color': color,
        'colorKey': colorKey,
      };

  factory EventColorValue.fromJson(Map<Object?, Object?> json) {
    return EventColorValue(
      color: json['color'] as int?,
      colorKey: json['colorKey'] as int?,
    );
  }
}

class EventChangeSet {
  final EventFieldChange<EventColorValue>? color;

  const EventChangeSet({this.color});

  bool get isEmpty => color == null;

  Map<String, Object?> toJson() {
    final EventFieldChange<EventColorValue>? colorChange = color;
    return <String, Object?>{
      if (colorChange != null)
        'color': <String, Object?>{
          'expected': colorChange.expected.toJson(),
          'requested': colorChange.requested.toJson(),
        },
    };
  }
}
