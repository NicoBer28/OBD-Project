import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// Pruebas de `api.deviceTokens` contra un `MockClient`: qué se manda y cómo se lee lo que
/// contesta `DeviceTokenController`.

const _config = ApiConfig(baseUrl: 'http://test.local');

String _authBody() => jsonEncode({
  'accessToken': 'access-1',
  'tokenType': 'Bearer',
  'expireInSeconds': 900,
  'userId': '8f14e45f-ceea-467a-9f8e-1f1a1c1a1c1a',
  'email': 'ada@example.com',
});

/// Un cliente logueado cuyo resto de respuestas sale de [handler].
Future<ObdApi> _loggedIn(Future<http.Response> Function(http.Request) handler) async {
  final api = ObdApi(
    config: _config,
    httpClient: MockClient((request) async {
      if (request.url.path.endsWith('/auth/login')) {
        return http.Response(
          _authBody(),
          200,
          headers: {'set-cookie': 'refreshToken=refresh-1; Path=/api/v1/auth; HttpOnly'},
        );
      }
      return handler(request);
    }),
  );
  await api.auth.login(userEmail: 'ada@example.com', userPassword: 'supersecret123');
  return api;
}

void main() {
  group('tokens de dispositivo', () {
    test('create manda el label y lee el token, que solo viene al crear', () async {
      late http.Request enviada;
      final api = await _loggedIn((request) async {
        enviada = request;
        return http.Response(
          jsonEncode({
            'id': 'tok-1',
            'carId': 'car-1',
            'label': 'samsung SM-A546E',
            'token': 'obdd_secreto',
            'createdAt': '2026-10-02T15:00:00Z',
            'idleExpiresAt': '2026-12-31T15:00:00Z',
          }),
          201,
        );
      });

      final token = await api.deviceTokens.create('car-1', label: '  samsung SM-A546E  ');

      expect(enviada.method, 'POST');
      expect(enviada.url.path, '/api/v1/cars/car-1/device-tokens');
      expect(enviada.headers['Authorization'], 'Bearer access-1');
      expect(jsonDecode(enviada.body), {'label': 'samsung SM-A546E'});

      expect(token.id, 'tok-1');
      expect(token.token, 'obdd_secreto');
      expect(token.idleExpiresAt, DateTime.utc(2026, 12, 31, 15));
    });

    test('un label de más de 60 caracteres se recorta en vez de dar 400', () async {
      late http.Request enviada;
      final api = await _loggedIn((request) async {
        enviada = request;
        return http.Response(jsonEncode({'id': 'tok-1', 'carId': 'car-1', 'label': 'x', 'token': 'obdd_x'}), 201);
      });

      await api.deviceTokens.create('car-1', label: 'x' * 80);

      expect((jsonDecode(enviada.body) as Map)['label'], hasLength(60));
    });

    test('revoke hace DELETE y un 404 llega como isNotFound', () async {
      late http.Request enviada;
      final api = await _loggedIn((request) async {
        enviada = request;
        return http.Response(
          jsonEncode({'status': 404, 'title': 'Not Found', 'detail': 'No such device token'}),
          404,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      await expectLater(
        api.deviceTokens.revoke('tok-1'),
        throwsA(isA<ApiException>().having((e) => e.isNotFound, 'isNotFound', isTrue)),
      );
      expect(enviada.method, 'DELETE');
      expect(enviada.url.path, '/api/v1/device-tokens/tok-1');
    });

    test('forCar lista los tokens vivos, sin el secreto', () async {
      final api = await _loggedIn((request) async {
        return http.Response(
          jsonEncode([
            {
              'id': 'tok-1',
              'carId': 'car-1',
              'label': 'Pixel 8',
              'createdAt': '2026-10-01T10:00:00Z',
              'lastUsedAt': '2026-10-02T10:00:00Z',
              'idleExpiresAt': '2026-12-31T10:00:00Z',
            },
          ]),
          200,
        );
      });

      final tokens = await api.deviceTokens.forCar('car-1');

      expect(tokens, hasLength(1));
      expect(tokens.single.label, 'Pixel 8');
      expect(tokens.single.token, isNull);
      expect(tokens.single.lastUsedAt, DateTime.utc(2026, 10, 2, 10));
    });
  });
}
