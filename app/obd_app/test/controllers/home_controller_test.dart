import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// Un servidor de mentira ruteado por método + path, con el estado justo para
/// que el controller pueda recorrer el flujo completo: cargar, crear un auto,
/// aceptar una invitación, arrancar y terminar un viaje.
class _FakeServer {
  final requests = <http.Request>[];
  var cars = <Map<String, dynamic>>[];
  var groups = <Map<String, dynamic>>[];
  var invitations = <Map<String, dynamic>>[];
  var trips = <Map<String, dynamic>>[];
  Map<String, dynamic>? device;

  static Map<String, dynamic> car(
    String id,
    String name, {
    Map<String, dynamic>? group,
  }) => {
    'id': id,
    'name': name,
    'licensePlate': 'AB123CD',
    'model': {
      'id': 'm1',
      'brand': 'Volkswagen',
      'model': 'Gol',
      'protocol': 'CAN',
    },
    'mileage': 120000,
    'fuelLevel': 70,
    'group': group,
  };

  static Map<String, dynamic> group(
    String id,
    String name, {
    String role = 'ADMIN',
  }) => {
    'id': id,
    'name': name,
    'createdAt': '2026-09-01T00:00:00Z',
    'memberCount': 2,
    'callerRole': role,
  };

  Map<String, dynamic> trip(String id, {bool active = true}) => {
    'id': id,
    'carId': 'c1',
    'driverId': 'me',
    'startedAt': '2026-09-21T10:00:00Z',
    'endedAt': active ? null : '2026-09-21T11:00:00Z',
    'initialFuel': 70,
    'finalFuel': active ? null : 50,
    'fuelUsed': active ? null : 20,
    'distance': active ? null : 40,
    'active': active,
  };

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    final path = r.url.path.replaceFirst('/api/v1', '');
    final method = r.method;
    http.Response json(Object body, [int status = 200]) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json'},
    );

    if (method == 'GET' && path == '/users/me') {
      return json({
        'id': 'me',
        'userName': 'Ada',
        'userLastName': 'Lovelace',
        'userEmail': 'ada@example.com',
      });
    }
    if (method == 'GET' && path == '/cars') return json(cars);
    if (method == 'POST' && path == '/cars') {
      final body = jsonDecode(r.body) as Map<String, dynamic>;
      final created = car('c${cars.length + 1}', body['name'] as String);
      cars = [...cars, created];
      return json(created, 201);
    }
    if (method == 'GET' && path == '/groups') return json(groups);
    if (method == 'GET' && path == '/invitations/pending') {
      return json(invitations);
    }
    if (method == 'POST' && path.endsWith('/accept')) {
      final id = path.split('/')[2];
      final inv = invitations.firstWhere((i) => i['id'] == id);
      invitations = invitations.where((i) => i['id'] != id).toList();
      groups = [
        ...groups,
        group(
          inv['groupId'] as String,
          inv['groupName'] as String,
          role: 'MEMBER',
        ),
      ];
      cars = [
        ...cars,
        car(
          'shared',
          'Auto de Grace',
          group: {'id': inv['groupId'], 'name': inv['groupName']},
        ),
      ];
      return json({
        'invitationId': id,
        'groupId': inv['groupId'],
        'invitationEmail': 'ada@example.com',
        'invitationStatus': 'ACCEPTED',
      });
    }
    if (method == 'GET' && RegExp(r'^/groups/[^/]+/members$').hasMatch(path)) {
      return json([
        {
          'userId': 'grace',
          'name': 'Grace',
          'email': 'grace@example.com',
          'role': 'ADMIN',
        },
        {
          'userId': 'me',
          'name': 'Ada',
          'email': 'ada@example.com',
          'role': 'MEMBER',
        },
      ]);
    }
    if (method == 'GET' && RegExp(r'^/cars/[^/]+/trips/active$').hasMatch(path)) {
      final open = trips.where((t) => t['active'] == true);
      return open.isEmpty ? http.Response('', 204) : json(open.first);
    }
    if (method == 'GET' && RegExp(r'^/cars/[^/]+/trips$').hasMatch(path)) {
      return json(trips);
    }
    if (method == 'GET' && RegExp(r'^/cars/[^/]+/device$').hasMatch(path)) {
      return device == null
          ? json({
              'title': 'Not Found',
              'status': 404,
              'detail': 'No device is paired to this car',
            }, 404)
          : json(device!);
    }
    if (method == 'POST' && path == '/trips') {
      final t = trip('t${trips.length + 1}');
      trips = [t, ...trips];
      return json(t, 201);
    }
    if (method == 'POST' && RegExp(r'^/trips/[^/]+/finish$').hasMatch(path)) {
      final id = path.split('/')[2];
      trips = [
        for (final t in trips) t['id'] == id ? trip(id, active: false) : t,
      ];
      return json(trip(id, active: false));
    }
    return json({
      'title': 'Not Found',
      'status': 404,
      'detail': 'unrouted $method $path',
    }, 404);
  }
}

HomeController _controller(_FakeServer server) {
  final api = ObdApi(
    config: const ApiConfig(baseUrl: 'http://test.local'),
    httpClient: MockClient(server.handle),
  );
  api.session.save(
    const AuthSession(
      accessToken: 'access',
      tokenType: 'Bearer',
      expireInSeconds: 900,
      userId: 'me',
      email: 'ada@example.com',
    ),
  );
  return HomeController(api: api);
}

void main() {
  test('usuario nuevo: sin autos ni grupos, listo para crear', () async {
    final server = _FakeServer();
    final c = _controller(server);

    await c.load();

    expect(c.status, HomeStatus.ready);
    expect(c.me?.fullName, 'Ada Lovelace');
    expect(c.hasCars, isFalse);
    expect(c.car, isNull);
    expect(c.group, isNull);
    // Sin auto ni grupo no se piden detalles.
    expect(
      server.requests.map((r) => r.url.path),
      isNot(contains(contains('/trips'))),
    );
  });

  test('con un auto carga viajes, viaje activo y dongle', () async {
    final server = _FakeServer()
      ..cars = [
        _FakeServer.car('c1', 'Golf', group: {'id': 'g1', 'name': 'Familia'}),
      ]
      ..groups = [_FakeServer.group('g1', 'Familia')]
      ..device = {
        'id': 'd1',
        'serial': 'A4:CF',
        'carId': 'c1',
        'carName': 'Golf',
      };
    server.trips = [server.trip('t1', active: false)];
    final c = _controller(server);

    await c.load();

    expect(c.car?.name, 'Golf');
    expect(c.group?.name, 'Familia');
    expect(c.carIsInGroup, isTrue);
    expect(c.members.length, 2);
    expect(c.carTrips.length, 1);
    expect(c.activeTrip, isNull);
    expect(c.device?.serial, 'A4:CF');
    expect(c.driverName('grace'), 'Grace');
    expect(c.driverName('me'), 'Vos');
    expect(c.driverName('nadie'), 'Miembro');
  });

  test('crear un auto lo deja seleccionado', () async {
    final server = _FakeServer();
    final c = _controller(server);
    await c.load();

    final created = await c.createCar(name: 'Mi Gol', modelId: 'm1');

    expect(c.car?.id, created.id);
    expect(c.cars.length, 1);
    expect(c.carIsSurelyMine, isTrue);
  });

  test(
    'aceptar una invitación trae el grupo y sus autos compartidos',
    () async {
      final server = _FakeServer()
        ..invitations = [
          {'id': 'i1', 'groupId': 'g9', 'groupName': 'Los Lopez'},
        ];
      final c = _controller(server);
      await c.load();
      expect(c.invitations.length, 1);
      expect(c.group, isNull);

      await c.acceptInvitation(c.invitations.single);

      expect(c.invitations, isEmpty);
      expect(c.group?.name, 'Los Lopez');
      expect(c.group?.callerRole, GroupRole.member);
      expect(c.car?.name, 'Auto de Grace');
      expect(c.members.length, 2);
    },
  );

  test('iniciar y terminar un viaje actualiza el estado local', () async {
    final server = _FakeServer()..cars = [_FakeServer.car('c1', 'Golf')];
    final c = _controller(server);
    await c.load();

    final trip = await c.startTrip(initialFuel: 70);
    expect(c.activeTrip?.id, trip.id);
    expect(c.activeTripIsMine, isTrue);
    expect(c.carTrips.first.id, trip.id);

    final finished = await c.finishTrip(finalFuel: 50, distance: 40);
    expect(finished.fuelUsed, 20);
    expect(c.activeTrip, isNull);
    expect(c.carTrips.single.active, isFalse);

    final started = server.requests.firstWhere(
      (r) => r.method == 'POST' && r.url.path.endsWith('/trips'),
    );
    expect(jsonDecode(started.body), {'carId': 'c1', 'initialFuel': 70});
  });

  test('una carga fallida se puede reintentar', () async {
    var fail = true;
    final server = _FakeServer();
    final api = ObdApi(
      config: const ApiConfig(baseUrl: 'http://test.local'),
      httpClient: MockClient(
        (r) => fail ? Future.value(http.Response('', 503)) : server.handle(r),
      ),
    );
    api.session.save(
      const AuthSession(
        accessToken: 'access',
        tokenType: 'Bearer',
        expireInSeconds: 900,
        userId: 'me',
        email: 'ada@example.com',
      ),
    );
    final c = HomeController(api: api);

    await c.load();
    expect(c.status, HomeStatus.failed);
    expect(c.loadError, isA<ApiException>());

    fail = false;
    await c.load();
    expect(c.status, HomeStatus.ready);
  });
}
