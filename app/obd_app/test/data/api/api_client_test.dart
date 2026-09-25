import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// Pruebas del transporte contra un `MockClient`: no hace falta ni servidor ni
/// base de datos, y cada caso fija una decisión del cliente (mandar el bearer,
/// guardar la cookie, refrescar una sola vez, traducir los errores).

const _config = ApiConfig(baseUrl: 'http://test.local');

/// Cuerpo de `AuthResponseDTO`.
String _authBody({String token = 'access-1', String email = 'ada@example.com'}) {
  return jsonEncode({
    'accessToken': token,
    'tokenType': 'Bearer',
    'expireInSeconds': 900,
    'userId': '8f14e45f-ceea-467a-9f8e-1f1a1c1a1c1a',
    'email': email,
  });
}

/// Un `Set-Cookie` como el que manda `RefreshCookie`.
Map<String, String> _cookie(String value) => {
  'set-cookie': 'refreshToken=$value; Path=/api/v1/auth; HttpOnly; SameSite=Strict',
};

ObdApi _apiWith(MockClient client) => ObdApi(config: _config, httpClient: client);

void main() {
  group('autenticación', () {
    test('login guarda el access token y la cookie de refresh', () async {
      late http.Request enviada;
      final api = _apiWith(
        MockClient((request) async {
          enviada = request;
          return http.Response(
            _authBody(),
            200,
            headers: _cookie('refresh-1'),
          );
        }),
      );

      final sesion = await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );

      expect(enviada.url.path, '/api/v1/auth/login');
      expect(jsonDecode(enviada.body), {
        'userEmail': 'ada@example.com',
        'userPassword': 'supersecret123',
      });
      // El login no manda Authorization: todavía no hay token.
      expect(enviada.headers.containsKey('Authorization'), isFalse);

      expect(sesion.accessToken, 'access-1');
      expect(api.session.isAuthenticated, isTrue);
      expect(api.session.refreshToken, 'refresh-1');
    });

    test('register omite el teléfono cuando viene vacío', () async {
      late http.Request enviada;
      final api = _apiWith(
        MockClient((request) async {
          enviada = request;
          return http.Response(_authBody(), 200, headers: _cookie('refresh-1'));
        }),
      );

      await api.auth.register(
        userName: 'Ada',
        userLastName: 'Lovelace',
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
        userPhone: '   ',
      );

      expect(jsonDecode(enviada.body), isNot(contains('userPhone')));
    });

    test('las llamadas autenticadas mandan el bearer', () async {
      late http.Request enviada;
      final api = _apiWith(
        MockClient((request) async {
          enviada = request;
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          return http.Response('[]', 200);
        }),
      );

      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );
      await api.cars.list();

      expect(enviada.headers['Authorization'], 'Bearer access-1');
    });

    test('sin sesión, lo protegido falla como 401 sin salir a la red', () async {
      var llamadas = 0;
      final api = _apiWith(
        MockClient((request) async {
          llamadas++;
          return http.Response('[]', 200);
        }),
      );

      await expectLater(
        api.cars.list(),
        throwsA(
          isA<ApiException>().having(
            (e) => e.isUnauthorized,
            'isUnauthorized',
            isTrue,
          ),
        ),
      );
      expect(llamadas, 0);
    });

    test('logout limpia la sesión local', () async {
      final api = _apiWith(
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          return http.Response('', 204, headers: _cookie(''));
        }),
      );

      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );
      await api.auth.logout();

      expect(api.session.isAuthenticated, isFalse);
      expect(api.session.refreshToken, isNull);
    });
  });

  group('refresh silencioso', () {
    test('un 401 dispara un refresh y reintenta la llamada', () async {
      var protegidas = 0;
      var refrescos = 0;

      final api = _apiWith(
        MockClient((request) async {
          final path = request.url.path;
          if (path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          if (path.endsWith('/auth/refresh')) {
            refrescos++;
            // La cookie vieja viaja a mano: no hay cookie jar en el celular.
            expect(request.headers['Cookie'], 'refreshToken=refresh-1');
            return http.Response(
              _authBody(token: 'access-2'),
              200,
              headers: _cookie('refresh-2'),
            );
          }
          protegidas++;
          // La primera vez el token está vencido; después del refresh, no.
          if (request.headers['Authorization'] == 'Bearer access-1') {
            return http.Response(
              jsonEncode({'title': 'Unauthorized', 'status': 401}),
              401,
            );
          }
          return http.Response('[]', 200);
        }),
      );

      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );
      final autos = await api.cars.list();

      expect(autos, isEmpty);
      expect(refrescos, 1);
      expect(protegidas, 2); // el 401 y el reintento
      // El refresh rota: se guarda el token nuevo y la cookie nueva.
      expect(api.session.accessToken, 'access-2');
      expect(api.session.refreshToken, 'refresh-2');
    });

    test('dos 401 en paralelo comparten un solo refresh', () async {
      // Importa de verdad: los refresh tokens son de un solo uso y reusar uno
      // ya rotado revoca toda la familia y obliga a loguearse de nuevo.
      var refrescos = 0;

      final api = _apiWith(
        MockClient((request) async {
          final path = request.url.path;
          if (path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          if (path.endsWith('/auth/refresh')) {
            refrescos++;
            await Future<void>.delayed(const Duration(milliseconds: 10));
            return http.Response(
              _authBody(token: 'access-2'),
              200,
              headers: _cookie('refresh-2'),
            );
          }
          if (request.headers['Authorization'] == 'Bearer access-1') {
            return http.Response('{"status":401}', 401);
          }
          return http.Response('[]', 200);
        }),
      );

      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );
      await Future.wait([api.cars.list(), api.trips.mine()]);

      expect(refrescos, 1);
    });

    test('si el refresh es rechazado, la sesión se cierra', () async {
      final api = _apiWith(
        MockClient((request) async {
          final path = request.url.path;
          if (path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          if (path.endsWith('/auth/refresh')) {
            return http.Response('{"status":401}', 401);
          }
          return http.Response('{"status":401}', 401);
        }),
      );

      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );

      await expectLater(api.cars.list(), throwsA(isA<ApiException>()));
      expect(api.session.isAuthenticated, isFalse);
    });
  });

  group('errores', () {
    late ObdApi api;

    Future<ObdApi> conRespuesta(http.Response respuesta) async {
      final creada = _apiWith(
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          return respuesta;
        }),
      );
      await creada.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );
      return creada;
    }

    test('un ProblemDetail se traduce a ApiException', () async {
      api = await conRespuesta(
        http.Response(
          jsonEncode({
            'title': 'Conflict',
            'status': 409,
            'detail': 'That car is already on a trip',
          }),
          409,
        ),
      );

      await expectLater(
        api.trips.start(carId: 'c1'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.isConflict, 'isConflict', isTrue)
              .having((e) => e.detail, 'detail', 'That car is already on a trip'),
        ),
      );
    });

    test('un 400 trae el mapa de errores por campo', () async {
      api = await conRespuesta(
        http.Response(
          jsonEncode({
            'title': 'Validation failed',
            'status': 400,
            'errors': {'name': 'must not be blank'},
          }),
          400,
        ),
      );

      await expectLater(
        api.cars.create(name: '', modelId: 'm1'),
        throwsA(
          isA<ApiException>().having(
            (e) => e.fieldErrors['name'],
            'fieldErrors[name]',
            'must not be blank',
          ),
        ),
      );
    });

    test('un host inalcanzable es NetworkException, no ApiException', () async {
      final api = _apiWith(
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          throw http.ClientException('Connection refused');
        }),
      );
      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );

      await expectLater(api.cars.list(), throwsA(isA<NetworkException>()));
    });
  });

  group('lecturas', () {
    Future<ObdApi> logueada(MockClient client) async {
      final api = _apiWith(client);
      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );
      return api;
    }

    MockClient responder(
      http.Response Function(http.Request request) handler,
    ) {
      return MockClient((request) async {
        if (request.url.path.endsWith('/auth/login')) {
          return http.Response(_authBody(), 200, headers: _cookie('refresh-1'));
        }
        return handler(request);
      });
    }

    test('un auto se parsea con su modelo, snapshot y grupo', () async {
      final api = await logueada(
        responder(
          (_) => http.Response(
            jsonEncode([
              {
                'id': 'car-1',
                'name': "Ada's Gol",
                'licensePlate': 'AB123CD',
                'model': {
                  'id': 'm-1',
                  'brand': 'Volkswagen',
                  'model': 'Gol',
                  'protocol': 'ISO 15765-4 (CAN)',
                },
                'mileage': 120000,
                'fuelLevel': 70,
                'batteryLevel': 85,
                // Entero a propósito: JSON tiene un solo tipo numérico y un
                // cast directo a double explotaría.
                'latitude': -34,
                'longitude': -58.3816,
                'snapshotAt': '2026-09-16T18:35:22Z',
                'group': {'id': 'g-1', 'name': 'Familia Lazzari'},
              },
            ]),
            200,
          ),
        ),
      );

      final autos = await api.cars.list();

      expect(autos, hasLength(1));
      final auto = autos.single;
      expect(auto.model.label, 'Volkswagen Gol');
      expect(auto.latitude, -34.0);
      expect(auto.hasPosition, isTrue);
      expect(auto.isShared, isTrue);
      expect(auto.group?.name, 'Familia Lazzari');
      expect(auto.snapshotAt?.toIso8601String(), '2026-09-16T18:35:22.000Z');
    });

    test('un auto sin snapshot deja los campos del device en null', () async {
      final api = await logueada(
        responder(
          (_) => http.Response(
            jsonEncode({
              'id': 'car-1',
              'name': 'Nuevo',
              'licensePlate': null,
              'model': {
                'id': 'm-1',
                'brand': 'Volkswagen',
                'model': 'Gol',
                'protocol': 'ISO 15765-4 (CAN)',
              },
              'mileage': null,
              'fuelLevel': null,
              'batteryLevel': null,
              'latitude': null,
              'longitude': null,
              'snapshotAt': null,
              'group': null,
            }),
            200,
          ),
        ),
      );

      final auto = await api.cars.byId('car-1');

      expect(auto.hasSnapshot, isFalse);
      expect(auto.hasPosition, isFalse);
      expect(auto.isShared, isFalse);
      expect(auto.group, isNull);
    });

    test('un auto sin viaje abierto devuelve null, no un error', () async {
      // La API contesta 204 porque "sin viaje" es el estado normal de un auto;
      // 404 acá significaría "no existe ese auto".
      final api = await logueada(responder((_) => http.Response('', 204)));

      expect(await api.trips.active('car-1'), isNull);
    });
  });

  group('telemetría', () {
    test('la historia manda carId, since exclusivo y limit', () async {
      late Uri pedida;
      final api = _apiWith(
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          pedida = request.url;
          return http.Response(
            jsonEncode({
              'carId': 'car-1',
              'readings': [],
              'nextSince': '2026-09-10T17:37:52Z',
              'hasMore': false,
            }),
            200,
          );
        }),
      );
      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );

      final pagina = await api.telemetry.history(
        carId: 'car-1',
        since: DateTime.utc(2026, 9, 10, 17, 37, 52),
        limit: 250,
      );

      expect(pedida.queryParameters['carId'], 'car-1');
      expect(pedida.queryParameters['since'], '2026-09-10T17:37:52.000Z');
      expect(pedida.queryParameters['limit'], '250');
      expect(pagina.hasMore, isFalse);
    });

    test('los parámetros opcionales no se mandan vacíos', () async {
      late Uri pedida;
      final api = _apiWith(
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          pedida = request.url;
          return http.Response(
            jsonEncode({
              'carId': 'car-1',
              'readings': [],
              'nextSince': null,
              'hasMore': false,
            }),
            200,
          );
        }),
      );
      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );

      await api.telemetry.history(carId: 'car-1');

      expect(pedida.queryParameters.keys, ['carId']);
    });

    test('subir exige exactamente un destino: serial o carId', () async {
      final api = _apiWith(MockClient((_) async => http.Response('{}', 200)));
      final lecturas = [TelemetryReading(recordedAt: DateTime.now().toUtc())];

      expect(
        () => api.telemetry.upload(readings: lecturas),
        throwsArgumentError,
      );
      expect(
        () => api.telemetry.upload(
          readings: lecturas,
          serial: 'A4:CF:12',
          carId: 'car-1',
        ),
        throwsArgumentError,
      );
    });

    test('una lectura serializa solo los campos que trae', () {
      final lectura = TelemetryReading(
        recordedAt: DateTime.utc(2026, 9, 10, 14, 56, 13),
        speed: 40,
        fuelLevel: 69,
        raw: '01045020',
      );

      expect(lectura.toJson(), {
        'recordedAt': '2026-09-10T14:56:13.000Z',
        'speed': 40,
        'fuelLevel': 69,
        'raw': '01045020',
      });
      expect(lectura.hasValidPosition, isTrue);
      expect(lectura.isInTheFuture, isFalse);
    });

    test('media posición no es posición', () {
      final lectura = TelemetryReading(
        recordedAt: DateTime.now().toUtc(),
        latitude: -34.6037,
      );

      // El servidor rechaza el lote entero con
      // readings[0].positionComplete; conviene verlo antes de subir.
      expect(lectura.hasValidPosition, isFalse);
    });

    test('los duplicados son éxito, no error', () async {
      final api = _apiWith(
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            return http.Response(
              _authBody(),
              200,
              headers: _cookie('refresh-1'),
            );
          }
          return http.Response(
            jsonEncode({
              'carId': 'car-1',
              'stored': 1,
              'duplicates': 2,
              'tripId': null,
              'snapshotUpdated': true,
              'latestRecordedAt': '2026-09-10T14:56:24Z',
            }),
            200,
          );
        }),
      );
      await api.auth.login(
        userEmail: 'ada@example.com',
        userPassword: 'supersecret123',
      );

      final resultado = await api.telemetry.upload(
        serial: 'a4:cf:12:8b:3c:7e',
        readings: [TelemetryReading(recordedAt: DateTime.now().toUtc())],
      );

      expect(resultado.stored, 1);
      expect(resultado.duplicates, 2);
      expect(resultado.accepted, 3);
      // tripId null es la señal para ofrecerle al conductor iniciar un viaje.
      expect(resultado.hasOpenTrip, isFalse);
    });
  });
}
