import 'package:obd_app/models/reservation.dart';

enum ReservationError {
  endBeforeStart,
  inThePast,
  tooShort,
  tooLong,
  overlaps,
  notOwner,
}

class ReservationCheck {
  final ReservationError? error;

  /// The existing reservation that blocks this one (only for
  /// [ReservationError.overlaps]).
  final Reservation? conflict;

  const ReservationCheck.ok() : error = null, conflict = null;

  const ReservationCheck.fail(ReservationError this.error, {this.conflict});

  bool get isValid => error == null;
}

/// Thrown by a repository when a create/cancel request is refused: invalid
/// input, overlap with another booking, or cancelling someone else's booking.
class ReservationRejected implements Exception {
  final ReservationCheck check;

  const ReservationRejected(this.check);

  @override
  String toString() => 'ReservationRejected(${check.error})';
}

abstract final class ReservationRules {
  static const minDuration = Duration(minutes: 30);
  static const maxDuration = Duration(days: 3);

  /// A booking may start slightly "in the past" so "now" is still bookable.
  static const startTolerance = Duration(minutes: 5);

  static ReservationCheck validate({
    required DateTime start,
    required DateTime end,
    required DateTime now,
    required Iterable<Reservation> existing,
    String? ignoreId,
  }) {
    if (!end.isAfter(start)) {
      return const ReservationCheck.fail(ReservationError.endBeforeStart);
    }
    if (start.isBefore(now.subtract(startTolerance))) {
      return const ReservationCheck.fail(ReservationError.inThePast);
    }

    final length = end.difference(start);
    if (length < minDuration) {
      return const ReservationCheck.fail(ReservationError.tooShort);
    }
    if (length > maxDuration) {
      return const ReservationCheck.fail(ReservationError.tooLong);
    }

    for (final other in existing) {
      if (other.id == ignoreId) continue;
      if (other.overlapsRange(start, end)) {
        return ReservationCheck.fail(ReservationError.overlaps, conflict: other);
      }
    }
    return const ReservationCheck.ok();
  }

  /// Rounds a local time up to the next quarter hour and drops the seconds.
  static DateTime roundUpToQuarter(DateTime local) {
    final minutes = ((local.minute + 14) ~/ 15) * 15;
    return DateTime(local.year, local.month, local.day, local.hour, minutes);
  }
}
