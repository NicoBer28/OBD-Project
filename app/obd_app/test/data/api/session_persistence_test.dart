import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// Pruebas de que el refresh token sobrevive al cierre de la app. El keystore
/// real se reemplaza por un [_MemoryStore]: lo que se verifica es cuándo el
/// código escribe, borra o conserva el token, no el plugin.

const _config = ApiConfig(baseUrl: 'http://test.local');

String _authBody({String token = 'access-1'}) => jsonEncode({
  'accessToken': token,
  'tokenType': 'Bearer',
  'expireInSeconds': 900,
  'userId': '8f14e45f-ceea-467a-9f8e-1f1a1c1a1c1a',
  'email': 'ada@example.com',
});

Map<String, String> _cookie(String value) => {
  'set-cookie': 'refreshToken=$value; Path=/api/v1/auth; HttpOnly; SameSite=Strict',
};

/// Un "disco" en memoria.
class _MemoryStore implements SessionStore {
  _MemoryStore([this.token]);

  String? token;
  bool failReads = false;

  @override
  Future<String?> readRefreshToken() async {
    if (failReads) throw StateError('keystore ilegible');
    return token;
  }

  @override
  Future<void> writeRefreshToken(String value) async {
    token = value;
  }

  @override
  Future<void> deleteRefreshToken() async {
    token = null;
  }
}

ObdApi _apiWith(ObdSession session, MockClient client) => ObdApi(config: _config, httpClient: client, session: session);

/// Simula abrir la app de nuevo con [store] tal como quedó en el disco.
Future<ObdSession> _relaunch(_MemoryStore store) async {
  final session = ObdSession(store: store);
  await session.restore();
  return session;
}

void main() {
  group('persistencia del refresh token', () {
    test('el login guarda el refresh token', () async {
      final store = _MemoryStore();
      final session = ObdSession(store: store);
      final api = _apiWith(
        session,
        MockClient((_) async => http.Response(_authBody(), 200, headers: _cookie('refresh-1'))),
      );

      await api.auth.login(userEmail: 'ada@example.com', userPassword: 'supersecret123');
      await session.pendingWrites;

      expect(store.token, 'refresh-1');
    });

    test('solo se guarda el refresh token, no el access token', () async {
      // Lo que cambia entre corridas es el access token (15 min); el que dura
      // es el refresh. El store ni siquiera tiene un lugar para el primero.
      final store = _MemoryStore();
      final session = ObdSession(store: store);
      final api = _apiWith(
        session,
        MockClient((_) async => http.Response(_authBody(token: 'jwt-secreto'), 200, headers: _cookie('refresh-1'))),
      );

      await api.auth.login(userEmail: 'ada@example.com', userPassword: 'supersecret123');
      await session.pendingWrites;

      expect(store.token, isNot(contains('jwt-secreto')));
    });

    test('cada rotación pisa el token guardado', () async {
      final store = _MemoryStore();
      final session = ObdSession(store: store);
      final api = _apiWith(
        session,
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/refresh')) {
            return http.Response(_authBody(token: 'access-2'), 200, headers: _cookie('refresh-2'));
          }
          return http.Response(_authBody(), 200, headers: _cookie('refresh-1'));
        }),
      );

      await api.auth.login(userEmail: 'ada@example.com', userPassword: 'supersecret123');
      await api.client.refreshSession();
      await session.pendingWrites;

      // Si quedara refresh-1 en disco, el próximo arranque presentaría un
      // token ya usado y el servidor revocaría toda la familia.
      expect(store.token, 'refresh-2');
    });

    test('un arranque nuevo recupera la sesión con el token guardado', () async {
      final session = await _relaunch(_MemoryStore('refresh-1'));

      // Recién restaurado: hay token, pero todavía no hay access token.
      expect(session.refreshToken, 'refresh-1');
      expect(session.isAuthenticated, isFalse);

      String? cookieEnviada;
      final api = _apiWith(
        session,
        MockClient((request) async {
          cookieEnviada = request.headers['Cookie'];
          return http.Response(_authBody(token: 'access-2'), 200, headers: _cookie('refresh-2'));
        }),
      );

      final restored = await api.client.refreshSession();

      expect(restored, isTrue);
      expect(cookieEnviada, 'refreshToken=refresh-1');
      expect(session.isAuthenticated, isTrue);
      expect(session.accessToken, 'access-2');
      expect(session.email, 'ada@example.com');
    });

    test('el logout borra el token guardado', () async {
      final store = _MemoryStore();
      final session = ObdSession(store: store);
      final api = _apiWith(
        session,
        MockClient((request) async {
          if (request.url.path.endsWith('/auth/logout')) {
            return http.Response(
              '',
              204,
              headers: {'set-cookie': 'refreshToken=; Path=/api/v1/auth; Max-Age=0; HttpOnly'},
            );
          }
          return http.Response(_authBody(), 200, headers: _cookie('refresh-1'));
        }),
      );

      await api.auth.login(userEmail: 'ada@example.com', userPassword: 'supersecret123');
      await session.pendingWrites;
      expect(store.token, 'refresh-1');

      await api.auth.logout();
      await session.pendingWrites;

      expect(store.token, isNull);
      expect(session.refreshToken, isNull);
    });

    test('un refresh rechazado borra el token guardado', () async {
      final store = _MemoryStore('refresh-1');
      final session = await _relaunch(store);
      final api = _apiWith(session, MockClient((_) async => http.Response('{"status":401}', 401)));

      final restored = await api.client.refreshSession();
      await session.pendingWrites;

      expect(restored, isFalse);
      expect(session.refreshToken, isNull);
      expect(store.token, isNull);
    });

    test('un error 5xx en el refresh conserva el token guardado', () async {
      // Un 503 del proxy no es un veredicto sobre el token: si lo borráramos,
      // un tropiezo del servidor sería un logout permanente.
      final store = _MemoryStore('refresh-1');
      final session = await _relaunch(store);
      final api = _apiWith(session, MockClient((_) async => http.Response('bad gateway', 503)));

      final restored = await api.client.refreshSession();
      await session.pendingWrites;

      expect(restored, isFalse);
      expect(session.refreshToken, 'refresh-1');
      expect(store.token, 'refresh-1');
    });

    test('sin red el refresh conserva el token guardado', () async {
      final store = _MemoryStore('refresh-1');
      final session = await _relaunch(store);
      final api = _apiWith(
        session,
        MockClient((_) async {
          throw http.ClientException('sin señal');
        }),
      );

      final restored = await api.client.refreshSession();
      await session.pendingWrites;

      expect(restored, isFalse);
      expect(session.refreshToken, 'refresh-1');
      expect(store.token, 'refresh-1');
    });

    test('un keystore ilegible se trata como sesión cerrada, sin excepción', () async {
      final store = _MemoryStore('refresh-1')..failReads = true;

      final session = await _relaunch(store);
      await session.pendingWrites;

      expect(session.refreshToken, isNull);
      // Y se limpia, para no volver a tropezar con lo mismo en cada arranque.
      expect(store.token, isNull);
    });

    test('sin store la sesión no persiste nada', () async {
      final session = ObdSession();
      await session.restore();

      expect(session.refreshToken, isNull);
    });
  });
}
