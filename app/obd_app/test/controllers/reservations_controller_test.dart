import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:obd_app/controllers/reservations_controller.dart';
import 'package:obd_app/data/in_memory_reservation_repository.dart';
import 'package:obd_app/models/models.dart';

const _members = [
  MemberData(
    id: 'u1',
    initials: 'LM',
    name: 'Vos',
    color: Color(0xFF00897B),
    fuelShare: 0,
    fuelAmount: '',
  ),
  MemberData(
    id: 'u2',
    initials: 'SM',
    name: 'Sofía',
    color: Color(0xFF5E35B1),
    fuelShare: 0,
    fuelAmount: '',
  ),
];

DateTime _tomorrowAt(int hour) {
  final n = DateTime.now();
  return DateTime(n.year, n.month, n.day + 1, hour);
}

Reservation _booking(String id, String userId, int from, int to) => Reservation(
  id: id,
  carId: 'car',
  userId: userId,
  start: _tomorrowAt(from),
  end: _tomorrowAt(to),
);

Future<(InMemoryReservationRepository, ReservationsController)> _build([
  List<Reservation> seed = const [],
]) async {
  final repo = InMemoryReservationRepository(seed);
  final controller = ReservationsController(
    repository: repo,
    carId: 'car',
    currentUserId: 'u1',
    members: _members,
  );
  await pumpEventQueue(); // let the first stream event arrive
  return (repo, controller);
}

void main() {
  test('reserve() adds the booking and notifies listeners', () async {
    final (repo, controller) = await _build();
    addTearDown(() {
      controller.dispose();
      repo.dispose();
    });

    var notifications = 0;
    controller.addListener(() => notifications++);

    final created = await controller.reserve(
      start: _tomorrowAt(10),
      end: _tomorrowAt(12),
    );
    await pumpEventQueue();

    expect(controller.reservations.map((r) => r.id), [created.id]);
    expect(notifications, greaterThan(0));
  });

  test('an overlapping booking is rejected and the list is unchanged', () async {
    final (repo, controller) = await _build();
    addTearDown(() {
      controller.dispose();
      repo.dispose();
    });

    await controller.reserve(start: _tomorrowAt(10), end: _tomorrowAt(12));

    await expectLater(
      controller.reserve(start: _tomorrowAt(11), end: _tomorrowAt(13)),
      throwsA(isA<ReservationRejected>()),
    );
    await pumpEventQueue();

    expect(controller.reservations, hasLength(1));
  });

  test('back-to-back bookings are accepted', () async {
    final (repo, controller) = await _build();
    addTearDown(() {
      controller.dispose();
      repo.dispose();
    });

    await controller.reserve(start: _tomorrowAt(10), end: _tomorrowAt(12));
    await controller.reserve(start: _tomorrowAt(12), end: _tomorrowAt(14));
    await pumpEventQueue();

    expect(controller.reservations, hasLength(2));
  });

  test("nextTurn is the current user's earliest upcoming booking", () async {
    final (repo, controller) = await _build([
      _booking('a', 'u2', 9, 11),
      _booking('b', 'u1', 15, 17),
      _booking('c', 'u1', 19, 20),
    ]);
    addTearDown(() {
      controller.dispose();
      repo.dispose();
    });

    expect(controller.nextTurn()?.id, 'b');
    expect(controller.slotFor(controller.nextTurn()!).person, 'Vos');
  });

  test('only the owner can cancel', () async {
    final sofia = _booking('a', 'u2', 9, 11);
    final mine = _booking('b', 'u1', 15, 17);
    final (repo, controller) = await _build([sofia, mine]);
    addTearDown(() {
      controller.dispose();
      repo.dispose();
    });

    await expectLater(
      controller.cancel(sofia),
      throwsA(isA<ReservationRejected>()),
    );

    await controller.cancel(mine);
    await pumpEventQueue();

    expect(controller.reservations.map((r) => r.id), ['a']);
  });
}
