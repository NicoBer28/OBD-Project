import 'package:flutter_test/flutter_test.dart';
import 'package:obd_app/core/utils/api_mappers.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/data/api/obd_api.dart';

Trip _trip({
  required String id,
  required String driver,
  required DateTime start,
  Duration? length,
  int? fuelUsed,
  int? distance,
}) => Trip(
  id: id,
  carId: 'car',
  driverId: driver,
  active: length == null,
  startedAt: start,
  endedAt: length == null ? null : start.add(length),
  fuelUsed: fuelUsed,
  distance: distance,
);

const _me = UserProfile(
  id: 'me',
  userName: 'Ada',
  userLastName: 'Lovelace',
  userEmail: 'ada@example.com',
);

const _members = [
  GroupMember(
    userId: 'grace',
    name: 'Grace',
    email: 'grace@example.com',
    role: GroupRole.admin,
  ),
  GroupMember(
    userId: 'me',
    name: 'Ada',
    email: 'ada@example.com',
    role: GroupRole.member,
  ),
];

void main() {
  final now = DateTime.utc(2026, 9, 21, 12);

  group('members', () {
    test('yo voy primero como "Vos" y la nafta se reparte por conductor', () {
      final trips = [
        _trip(
          id: '1',
          driver: 'me',
          start: now,
          length: const Duration(hours: 1),
          fuelUsed: 30,
        ),
        _trip(
          id: '2',
          driver: 'grace',
          start: now,
          length: const Duration(hours: 1),
          fuelUsed: 10,
        ),
        // Cargó nafta: no cuenta como consumo.
        _trip(
          id: '3',
          driver: 'grace',
          start: now,
          length: const Duration(hours: 1),
          fuelUsed: -20,
        ),
      ];

      final members = ApiMappers.members(
        members: _members,
        me: _me,
        trips: trips,
      );

      expect(members.map((m) => m.name), ['Vos', 'Grace']);
      expect(members[0].fuelShare, closeTo(.75, .001));
      expect(members[0].fuelAmount, '30 %');
      expect(members[1].fuelShare, closeTo(.25, .001));
      expect(members[0].initials, 'AL');
    });

    test('sin grupo, la lista soy solo yo', () {
      final members = ApiMappers.members(
        members: const [],
        me: _me,
        trips: const [],
      );
      expect(members.length, 1);
      expect(members.single.id, 'me');
      expect(members.single.fuelAmount, '—');
    });
  });

  group('trips', () {
    test('formatea día, horario, distancia y conductor', () {
      final members = ApiMappers.members(
        members: _members,
        me: _me,
        trips: const [],
      );
      final rows = ApiMappers.trips(
        [
          _trip(
            id: '1',
            driver: 'grace',
            start: now.subtract(const Duration(days: 1)),
            length: const Duration(minutes: 45),
            fuelUsed: 8,
            distance: 12,
          ),
          _trip(id: '2', driver: 'me', start: now),
        ],
        members: members,
        now: now,
      );

      expect(rows[0].day, 'Ayer');
      expect(rows[0].distance, '12 km');
      expect(rows[0].duration, contains('45 min'));
      expect(rows[0].duration, contains('8 % nafta'));
      expect(rows[0].drivers.single.name, 'Grace');
      expect(rows[0].active, isFalse);

      expect(rows[1].day, 'Hoy');
      expect(rows[1].route, endsWith('en curso'));
      expect(rows[1].distance, '—');
      expect(rows[1].active, isTrue);
    });
  });

  group('activity', () {
    test('suma el período y compara con el anterior', () {
      final members = ApiMappers.members(
        members: _members,
        me: _me,
        trips: const [],
      );
      final trips = [
        // Esta semana
        _trip(
          id: '1',
          driver: 'me',
          start: now.subtract(const Duration(days: 1)),
          length: const Duration(hours: 1),
          fuelUsed: 10,
          distance: 50,
        ),
        _trip(
          id: '2',
          driver: 'grace',
          start: now.subtract(const Duration(days: 2)),
          length: const Duration(minutes: 30),
          fuelUsed: 5,
          distance: 25,
        ),
        // La semana anterior
        _trip(
          id: '3',
          driver: 'me',
          start: now.subtract(const Duration(days: 10)),
          length: const Duration(hours: 2),
          fuelUsed: 30,
          distance: 50,
        ),
        // Fuera de ambos
        _trip(
          id: '4',
          driver: 'me',
          start: now.subtract(const Duration(days: 40)),
          length: const Duration(hours: 2),
          fuelUsed: 99,
          distance: 999,
        ),
      ];

      final summary = ApiMappers.activity(
        trips,
        period: '7 d',
        members: members,
        now: now,
      );

      expect(summary.distance.value, '75');
      expect(summary.distance.delta, '↑ 50% vs. 7 d anteriores');
      expect(summary.drivingTime.value, '1 h 30 min');
      expect(summary.fuel.value, '15');
      expect(summary.fuel.delta, '↓ 50% vs. 7 d anteriores');
      expect(summary.fuel.improved, isTrue);
      expect(summary.trips.value, '2');
      expect(summary.consumption.length, 7);
      expect(summary.consumption.reduce((a, b) => a + b), 15);
      expect(summary.consumptionLabels.length, 3);
      expect(summary.consumptionPeak, startsWith('pico: '));
      expect(summary.driverDistances.first.member.name, 'Vos');
      expect(summary.driverDistances.first.distance, '50 km');
    });

    test('sin viajes previos no inventa porcentajes', () {
      final summary = ApiMappers.activity(
        const [],
        period: '30 d',
        members: const [],
        now: now,
      );
      expect(summary.distance.delta, 'sin actividad');
      expect(summary.consumption.length, 30);
      expect(summary.consumptionPeak, '');
      expect(summary.driverDistances, isEmpty);
    });

    test('períodos largos agrupan por semana', () {
      final summary = ApiMappers.activity(
        const [],
        period: '3 m',
        members: const [],
        now: now,
      );
      expect(summary.consumption.length, 13);
    });
  });

  group('TripFormat', () {
    test('miles y km', () {
      expect(TripFormat.thousands(120000), '120.000');
      expect(TripFormat.thousands(999), '999');
      expect(TripFormat.km(1284), '1.284 km');
    });

    test('ago', () {
      expect(
        TripFormat.ago(now.subtract(const Duration(seconds: 5)), now),
        'recién',
      );
      expect(
        TripFormat.ago(now.subtract(const Duration(minutes: 5)), now),
        'hace 5 min',
      );
      expect(
        TripFormat.ago(now.subtract(const Duration(hours: 3)), now),
        'hace 3 h',
      );
      expect(
        TripFormat.ago(now.subtract(const Duration(days: 2)), now),
        'hace 2 d',
      );
    });
  });
}
