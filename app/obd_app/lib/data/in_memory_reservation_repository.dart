import 'dart:async';

import 'package:obd_app/data/reservation_repository.dart';
import 'package:obd_app/models/models.dart';

/// Demo / offline implementation. Same job DemoData does for read-only data,
/// but mutable: bookings made here show up in the UI until the app restarts.
class InMemoryReservationRepository implements ReservationRepository {
  InMemoryReservationRepository([Iterable<Reservation> seed = const []]) : _items = [...seed] {
    _sort();
  }

  /// A few reservations around "now" so the UI has something to show.
  factory InMemoryReservationRepository.demo({
    required String carId,
    required List<String> userIds,
  }) {
    DateTime at(int dayOffset, int hour) {
      final n = DateTime.now();
      return DateTime(n.year, n.month, n.day + dayOffset, hour);
    }

    String user(int i) => userIds[i % userIds.length];

    return InMemoryReservationRepository([
      Reservation(
        id: 'demo-1',
        carId: carId,
        userId: user(0),
        start: at(0, 18),
        end: at(0, 21),
      ),
      Reservation(
        id: 'demo-2',
        carId: carId,
        userId: user(1),
        start: at(1, 9),
        end: at(1, 14),
        note: 'Pilar',
      ),
      Reservation(
        id: 'demo-3',
        carId: carId,
        userId: user(2),
        start: at(2, 0),
        end: at(3, 0),
      ),
    ]);
  }

  final List<Reservation> _items;
  final _changes = StreamController<void>.broadcast(sync: true);
  int _nextId = 1;

  @override
  Stream<List<Reservation>> watch(String carId) {
    return Stream.multi((controller) {
      controller.add(_snapshot(carId));
      final sub = _changes.stream.listen(
        (_) => controller.add(_snapshot(carId)),
      );
      controller.onCancel = sub.cancel;
    });
  }

  @override
  Future<Reservation> create({
    required String carId,
    required String userId,
    required DateTime start,
    required DateTime end,
    String? note,
  }) async {
    // Dart runs this synchronously up to the return, so check + insert
    // cannot interleave with another create() call.
    final check = ReservationRules.validate(
      start: start,
      end: end,
      now: DateTime.now(),
      existing: _items.where((r) => r.carId == carId),
    );
    if (!check.isValid) throw ReservationRejected(check);

    final reservation = Reservation(
      id: 'r${_nextId++}',
      carId: carId,
      userId: userId,
      start: start,
      end: end,
      note: note,
    );
    _items.add(reservation);
    _sort();
    _changes.add(null);
    return reservation;
  }

  @override
  Future<void> cancel(
    String reservationId, {
    required String requesterId,
  }) async {
    final index = _items.indexWhere((r) => r.id == reservationId);
    if (index == -1) return;
    if (_items[index].userId != requesterId) {
      throw const ReservationRejected(
        ReservationCheck.fail(ReservationError.notOwner),
      );
    }
    _items.removeAt(index);
    _changes.add(null);
  }

  void dispose() => _changes.close();

  List<Reservation> _snapshot(String carId) => List.unmodifiable(_items.where((r) => r.carId == carId));

  void _sort() => _items.sort((a, b) => a.start.compareTo(b.start));
}
