import 'package:flutter_test/flutter_test.dart';
import 'package:obd_app/core/utils/reservation_format.dart';

void main() {
  // Local DateTimes on purpose: the formatters work in local time.
  final now = DateTime(2030, 1, 1, 10); // a Tuesday

  test('timeRange', () {
    expect(
      ReservationFormat.timeRange(DateTime(2030, 1, 1, 18), DateTime(2030, 1, 1, 21)),
      '18–21',
    );
    expect(
      ReservationFormat.timeRange(DateTime(2030, 1, 1, 18, 30), DateTime(2030, 1, 1, 21)),
      '18:30–21:00',
    );
    expect(
      ReservationFormat.timeRange(DateTime(2030, 1, 1), DateTime(2030, 1, 2)),
      'todo',
    );
    expect(
      ReservationFormat.timeRange(DateTime(2030, 1, 1, 18), DateTime(2030, 1, 2)),
      '18–24',
    );
    expect(
      ReservationFormat.timeRange(DateTime(2030, 1, 1, 22), DateTime(2030, 1, 2, 2)),
      '22–02 +1',
    );
  });

  test('dayLabel and longDate', () {
    expect(ReservationFormat.dayLabel(DateTime(2030, 1, 1, 18), now), 'HOY');
    expect(ReservationFormat.dayLabel(DateTime(2030, 1, 2, 9), now), 'MAÑ');
    expect(ReservationFormat.dayLabel(DateTime(2030, 1, 5, 9), now), 'SÁB 5');
    expect(ReservationFormat.longDate(DateTime(2030, 1, 5, 9), now), 'sáb 5 ene');
  });

  test('durationLabel', () {
    expect(ReservationFormat.durationLabel(const Duration(hours: 2)), '2 h');
    expect(ReservationFormat.durationLabel(const Duration(minutes: 90)), '1 h 30 min');
    expect(ReservationFormat.durationLabel(const Duration(hours: 26)), '1 d 2 h');
  });
}
