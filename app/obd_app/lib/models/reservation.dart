/// A block of time in which one member has the car.
///
/// Times are stored in UTC. [end] is exclusive: a booking that ends at 14:00
/// and another that starts at 14:00 do not conflict.
///
/// Ids are plain strings because the API uses UUIDs.
class Reservation {
  final String id;
  final String carId;
  final String userId;
  final DateTime start;
  final DateTime end;
  final String? note;

  Reservation({
    required this.id,
    required this.carId,
    required this.userId,
    required DateTime start,
    required DateTime end,
    this.note,
  })  : start = start.toUtc(),
        end = end.toUtc();

  Duration get duration => end.difference(start);

  bool overlaps(Reservation other) => overlapsRange(other.start, other.end);

  /// Half-open interval test: [start, end) intersects [otherStart, otherEnd).
  bool overlapsRange(DateTime otherStart, DateTime otherEnd) =>
      start.isBefore(otherEnd) && otherStart.isBefore(end);
}
