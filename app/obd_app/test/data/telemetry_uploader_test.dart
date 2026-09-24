import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/data/telemetry_uploader.dart';
import 'package:obd_app/src/generated/obd_api.g.dart';

const _config = ApiConfig(baseUrl: 'http://test.local');

ObdApi _loggedIn(MockClient client) {
  final api = ObdApi(config: _config, httpClient: client);
  api.session.save(
    const AuthSession(
      accessToken: 'access',
      tokenType: 'Bearer',
      expireInSeconds: 900,
      userId: 'me',
      email: 'ada@example.com',
    ),
  );
  return api;
}

String _ingested({int stored = 1, bool snapshot = true}) => jsonEncode({
  'carId': 'car-1',
  'stored': stored,
  'duplicates': 0,
  'tripId': null,
  'snapshotUpdated': snapshot,
  'latestRecordedAt': '2026-09-21T12:00:00Z',
});

void main() {
  test(
    'sube por carId lo que llegó del servicio nativo y vacía el buffer',
    () async {
      late http.Request enviada;
      final api = _loggedIn(
        MockClient((request) async {
          enviada = request;
          return http.Response(_ingested(), 200);
        }),
      );

      TelemetryIngestResult? notified;
      final uploader = TelemetryUploader(
        api: api,
        flushEvery: const Duration(days: 1),
        onUploaded: (r) => notified = r,
      )..carId = 'car-1';

      uploader.add(
        TelemetryEvent(speed: 40, rpm: 2100, fuel: 70, lat: -34.6, lng: -58.4),
      );
      expect(uploader.pending, 1);

      final result = await uploader.flush();

      expect(enviada.url.path, '/api/v1/telemetry');
      expect(enviada.headers['Authorization'], 'Bearer access');
      final body = jsonDecode(enviada.body) as Map<String, dynamic>;
      expect(body['carId'], 'car-1');
      expect(body.containsKey('serial'), isFalse);
      final reading = (body['readings'] as List).single as Map<String, dynamic>;
      expect(reading['speed'], 40);
      expect(reading['fuelLevel'], 70);
      expect(reading['latitude'], -34.6);
      expect(reading['longitude'], -58.4);
      expect(reading['raw'], {'rpm': 2100});
      expect(reading['recordedAt'], endsWith('Z'));

      expect(result?.stored, 1);
      expect(notified?.snapshotUpdated, isTrue);
      expect(uploader.pending, 0);
      uploader.dispose();
    },
  );

  test('media posición no se manda: ni lat ni lng', () async {
    late http.Request enviada;
    final api = _loggedIn(
      MockClient((request) async {
        enviada = request;
        return http.Response(_ingested(), 200);
      }),
    );
    final uploader = TelemetryUploader(
      api: api,
      flushEvery: const Duration(days: 1),
    )..carId = 'car-1';

    uploader.add(TelemetryEvent(speed: 10, lat: -34.6));
    await uploader.flush();

    final reading = ((jsonDecode(enviada.body) as Map)['readings'] as List).single as Map;
    expect(reading.containsKey('latitude'), isFalse);
    expect(reading.containsKey('longitude'), isFalse);
    uploader.dispose();
  });

  test(
    'si el servidor no contesta, el buffer se conserva para reintentar',
    () async {
      var calls = 0;
      final api = _loggedIn(
        MockClient((request) async {
          calls++;
          if (calls == 1) return http.Response('', 503);
          return http.Response(_ingested(), 200);
        }),
      );
      final uploader = TelemetryUploader(
        api: api,
        flushEvery: const Duration(days: 1),
      )..carId = 'car-1';

      uploader.add(TelemetryEvent(speed: 10));
      expect(await uploader.flush(), isNull);
      expect(uploader.pending, 1);

      expect(await uploader.flush(), isNotNull);
      expect(uploader.pending, 0);
      uploader.dispose();
    },
  );

  test('un 400 descarta el lote: reintentarlo nunca va a pasar', () async {
    final api = _loggedIn(
      MockClient(
        (request) async => http.Response(
          jsonEncode({
            'title': 'Validation failed',
            'status': 400,
            'errors': {
              'readings[0].notFromTheFuture': 'must not be in the future',
            },
          }),
          400,
        ),
      ),
    );
    final uploader = TelemetryUploader(
      api: api,
      flushEvery: const Duration(days: 1),
    )..carId = 'car-1';

    uploader.add(TelemetryEvent(speed: 10));
    expect(await uploader.flush(), isNull);
    expect(uploader.pending, 0);
    uploader.dispose();
  });

  test(
    'sin auto elegido no acumula; cambiar de auto descarta lo acumulado',
    () async {
      final api = _loggedIn(
        MockClient((_) async => http.Response(_ingested(), 200)),
      );
      final uploader = TelemetryUploader(
        api: api,
        flushEvery: const Duration(days: 1),
      );

      uploader.add(TelemetryEvent(speed: 10));
      expect(uploader.pending, 0);

      uploader.carId = 'car-1';
      uploader.add(TelemetryEvent(speed: 10));
      expect(uploader.pending, 1);

      uploader.carId = 'car-2';
      expect(uploader.pending, 0);
      uploader.dispose();
    },
  );
}
