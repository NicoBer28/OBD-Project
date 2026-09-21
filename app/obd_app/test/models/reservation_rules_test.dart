import 'package:flutter_test/flutter_test.dart';
import 'package:obd_app/models/models.dart';

Reservation _slot(String id, int fromHour, int toHour) => Reservation(
      id: id,
      carId: 'car',
      userId: 'u1',
      start: DateTime.utc(2030, 1, 1, fromHour),
      end: DateTime.utc(2030, 1, 1, toHour),
    );

void main() {
  group('Reservation.overlaps', () {
    final base = _slot('a', 9, 14);

    test('partial overlap', () {
      expect(base.overlaps(_slot('b', 13, 16)), isTrue);
      expect(_slot('b', 13, 16).overlaps(base), isTrue);
    });

    test('containment, both directions', () {
      expect(base.overlaps(_slot('b', 10, 11)), isTrue);
      expect(_slot('b', 10, 11).overlaps(base), isTrue);
    });

    test('identical interval', () {
      expect(base.overlaps(_slot('b', 9, 14)), isTrue);
    });

    test('back-to-back bookings do not conflict', () {
      expect(base.overlaps(_slot('b', 14, 17)), isFalse);
      expect(_slot('b', 14, 17).overlaps(base), isFalse);
      expect(base.overlaps(_slot('b', 6, 9)), isFalse);
    });

    test('disjoint', () {
      expect(base.overlaps(_slot('b', 15, 17)), isFalse);
    });
  });

  group('ReservationRules.validate', () {
    final now = DateTime.utc(2029, 12, 31);
    final existing = [_slot('a', 9, 14)];

    ReservationCheck check(DateTime start, DateTime end, {DateTime? at}) =>
        ReservationRules.validate(
          start: start,
          end: end,
          now: at ?? now,
          existing: existing,
        );

    test('accepts a free slot', () {
      expect(
        check(DateTime.utc(2030, 1, 1, 14), DateTime.utc(2030, 1, 1, 17)).isValid,
        isTrue,
      );
    });

    test('rejects an overlap and reports the conflicting reservation', () {
      final result =
          check(DateTime.utc(2030, 1, 1, 13), DateTime.utc(2030, 1, 1, 16));
      expect(result.error, ReservationError.overlaps);
      expect(result.conflict?.id, 'a');
    });

    test('ignoreId lets a reservation be edited without clashing with itself', () {
      final result = ReservationRules.validate(
        start: DateTime.utc(2030, 1, 1, 10),
        end: DateTime.utc(2030, 1, 1, 12),
        now: now,
        existing: existing,
        ignoreId: 'a',
      );
      expect(result.isValid, isTrue);
    });

    test('end must be after start', () {
      final same = DateTime.utc(2030, 1, 1, 15);
      expect(check(same, same).error, ReservationError.endBeforeStart);
    });

    test('start cannot be in the past', () {
      final result = check(
        DateTime.utc(2030, 1, 1, 10),
        DateTime.utc(2030, 1, 1, 12),
        at: DateTime.utc(2030, 1, 1, 12),
      );
      expect(result.error, ReservationError.inThePast);
    });

    test('minimum and maximum duration', () {
      expect(
        check(DateTime.utc(2030, 1, 1, 15), DateTime.utc(2030, 1, 1, 15, 20)).error,
        ReservationError.tooShort,
      );
      expect(
        check(DateTime.utc(2030, 1, 5), DateTime.utc(2030, 1, 9)).error,
        ReservationError.tooLong,
      );
    });
  });

  group('ReservationRules.roundUpToQuarter', () {
    test('rounds up and drops the seconds', () {
      expect(
        ReservationRules.roundUpToQuarter(DateTime(2030, 1, 1, 18, 1, 30)),
        DateTime(2030, 1, 1, 18, 15),
      );
      expect(
        ReservationRules.roundUpToQuarter(DateTime(2030, 1, 1, 18, 0, 30)),
        DateTime(2030, 1, 1, 18, 0),
      );
      expect(
        ReservationRules.roundUpToQuarter(DateTime(2030, 1, 1, 23, 50)),
        DateTime(2030, 1, 2, 0, 0),
      );
    });
  });
}
