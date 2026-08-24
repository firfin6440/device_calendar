import 'package:device_calendar/device_calendar.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('EventChangeSet serializes resources as one optimistic field', () {
    const EventChangeSet changes = EventChangeSet(
      resources: EventFieldChange<List<EventResourceValue>>(
        expected: <EventResourceValue>[
          EventResourceValue(
            name: 'Board room',
            email: 'board-room@example.com',
          ),
        ],
        requested: <EventResourceValue>[
          EventResourceValue(
            name: 'Studio',
            email: 'studio@example.com',
          ),
        ],
      ),
    );

    expect(
      changes.toJson()['resources'],
      <String, Object?>{
        'expected': <Map<String, Object?>>[
          <String, Object?>{
            'name': 'Board room',
            'email': 'board-room@example.com',
          },
        ],
        'requested': <Map<String, Object?>>[
          <String, Object?>{
            'name': 'Studio',
            'email': 'studio@example.com',
          },
        ],
      },
    );
    expect(changes.isEmpty, isFalse);
  });
}
