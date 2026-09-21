import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/src/generated/obd_api.g.dart';

/// El teléfono como relay: junta lo que manda el servicio nativo de BLE y lo
/// sube en lotes a `POST /telemetry`.
///
/// El ESP32 nunca habla HTTP, así que cada lectura que llega por
/// `ObdFlutterApi.onTelemetryUpdated` se acumula acá y cada [flushEvery] (o
/// al llenarse el buffer) se manda en un solo `upload`. El endpoint está
/// hecho para esto: un reintento con lecturas repetidas no es error
/// (`duplicates`), así que si la subida falla el buffer se conserva y se
/// vuelve a intentar entero en el próximo tick.
///
/// Se sube **por `carId`** y no por serial: el firmware todavía no expone el
/// serial del dongle (ver `api/README.md` › Devices), así que el teléfono no
/// puede saber a qué dongle está conectado. Cuando lo exponga, alcanza con
/// pasar `serial:` en vez de `carId:` y el servidor resuelve el auto solo.
class TelemetryUploader {
  TelemetryUploader({
    ObdApi? api,
    this.flushEvery = const Duration(seconds: 30),
    this.maxBuffer = 200,
    this.onUploaded,
  }) : _api = api ?? ObdApi.instance;

  final ObdApi _api;
  final Duration flushEvery;

  /// Al llegar acá se sube sin esperar al timer (el servidor acepta 500).
  final int maxBuffer;

  /// Avisa cada subida exitosa, para refrescar el snapshot del auto si hizo
  /// falta.
  final void Function(TelemetryIngestResult result)? onUploaded;

  final List<TelemetryReading> _buffer = [];
  Timer? _timer;
  bool _flushing = false;
  DateTime? _lastRecordedAt;

  String? _carId;

  /// A qué auto se atribuye lo que llega. Cambiarlo descarta lo acumulado
  /// para el auto anterior: esas lecturas ya no se pueden atribuir con
  /// certeza.
  String? get carId => _carId;
  set carId(String? value) {
    if (value == _carId) return;
    _buffer.clear();
    _carId = value;
  }

  int get pending => _buffer.length;

  /// Guarda una lectura del servicio nativo. Como mucho una por segundo:
  /// `telemetry` es única por `(car_id, recorded_at)`, y más resolución que
  /// eso no le sirve a nadie.
  void add(TelemetryEvent event) {
    if (_carId == null) return;

    final now = DateTime.now().toUtc();
    final at = DateTime.utc(
      now.year,
      now.month,
      now.day,
      now.hour,
      now.minute,
      now.second,
    );
    if (_lastRecordedAt == at) return;
    _lastRecordedAt = at;

    final hasPosition = event.lat != null && event.lng != null;
    _buffer.add(
      TelemetryReading(
        recordedAt: at,
        latitude: hasPosition ? event.lat : null,
        longitude: hasPosition ? event.lng : null,
        speed: _nonNegative(event.speed),
        fuelLevel: _nonNegative(event.fuel),
        raw: {if (event.rpm != null) 'rpm': event.rpm},
      ),
    );

    _timer ??= Timer.periodic(flushEvery, (_) => flush());
    if (_buffer.length >= maxBuffer) flush();
  }

  /// Sube lo acumulado. Devuelve null si no había nada, si ya hay una subida
  /// en curso, o si falló (en cuyo caso el buffer queda para el próximo
  /// intento).
  Future<TelemetryIngestResult?> flush() async {
    final carId = _carId;
    if (_flushing || carId == null || _buffer.isEmpty) return null;
    if (!_api.isAuthenticated) return null;

    _flushing = true;
    final batch = List<TelemetryReading>.of(_buffer.take(500));
    try {
      final result = await _api.telemetry.upload(carId: carId, readings: batch);
      // Solo se descarta lo que el servidor confirmó; lo que llegó mientras
      // tanto sigue en el buffer.
      if (_carId == carId) {
        _buffer.removeRange(0, batch.length.clamp(0, _buffer.length));
      }
      onUploaded?.call(result);
      return result;
    } on ObdApiException catch (error) {
      debugPrint('Telemetry upload failed: $error');
      if (error is ApiException && error.isValidation) {
        // Un lote mal formado nunca va a pasar: no tiene sentido reintentarlo.
        _buffer.clear();
      }
      return null;
    } finally {
      _flushing = false;
    }
  }

  void dispose() {
    _timer?.cancel();
    _timer = null;
    _buffer.clear();
  }

  static int? _nonNegative(int? value) =>
      value == null || value < 0 ? null : value;
}
