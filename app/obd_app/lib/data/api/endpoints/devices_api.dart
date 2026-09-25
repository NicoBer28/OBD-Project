import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/api_exception.dart';
import 'package:obd_app/data/api/models/api_models.dart';

/// `/api/v1/cars/{carId}/device` and `GET /api/v1/devices/{serial}`.
///
/// ### Why this exists
///
/// Every dongle advertises the same BLE name (`OBD-C`), so a phone that sees
/// one cannot tell two family cars apart. The mapping serial → car lives on
/// the server, which means:
///
/// * every phone in the family resolves the same dongle to the same car;
/// * moving a dongle to another car re-routes everyone at once;
/// * the driver is never asked "which car is this?".
///
/// The serial is keyed on what the firmware exposes rather than the BLE MAC,
/// because iOS hides the real address and hands each app a different random
/// UUID for the same peripheral.
class DevicesApi {
  const DevicesApi(this._client);

  final ApiClient _client;

  /// `PUT /api/v1/cars/{carId}/device` — pairs a dongle to a car you **own**.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [carId] | yes | a car the caller owns |
  /// | [serial] | yes | ≤ 64 chars; letters, digits, `:`, `_`, `-`. Trimmed and upper-cased server-side, so send it however the BLE stack reports it |
  ///
  /// `PUT` semantics: pairing a new serial replaces the car's current dongle
  /// (and clears `lastSeenAt`); pairing the same one again is a no-op.
  /// Owner-only — group members may drive the car, but which device speaks for
  /// it is the owner's call.
  ///
  /// Throws [ApiException] with `isNotFound` if the car is not yours, or
  /// `isConflict` if that serial is paired to a **different** car (unpair it
  /// there first, so a move is always deliberate).
  Future<Device> pair({required String carId, required String serial}) async {
    final response = await _client.put(
      '/cars/$carId/device',
      body: {'serial': serial},
    );
    return Device.fromJson(response.asMap);
  }

  /// `GET /api/v1/cars/{carId}/device` — the dongle paired to a car you may
  /// read (owner or group member).
  ///
  /// Throws [ApiException] with `isNotFound` in two cases the `detail` tells
  /// apart: `No such car`, or `No device is paired to this car`.
  Future<Device> forCar(String carId) async {
    final response = await _client.get('/cars/$carId/device');
    return Device.fromJson(response.asMap);
  }

  /// `GET /api/v1/cars/{carId}/device`, returning null instead of throwing
  /// when the car simply has no dongle — which is the ordinary state of a car
  /// nobody has paired yet, not an error worth a try/catch at the call site.
  Future<Device?> forCarOrNull(String carId) async {
    try {
      return await forCar(carId);
    } on ApiException catch (error) {
      if (error.isNotFound) return null;
      rethrow;
    }
  }

  /// `DELETE /api/v1/cars/{carId}/device` — unpairs. Owner-only, idempotent,
  /// `204`. The serial is free to pair elsewhere afterwards.
  Future<void> unpair(String carId) => _client.delete('/cars/$carId/device');

  /// `GET /api/v1/devices/{serial}` — **"which car is this dongle?"**, what a
  /// phone asks the moment it connects, to show the driver the car's name and
  /// offer to start a trip.
  ///
  /// Not a prerequisite for uploading: `TelemetryApi.upload` takes the serial
  /// directly and does this lookup itself.
  ///
  /// Throws [ApiException] with `isNotFound` both for an unknown serial and
  /// for one paired to a car the caller may not see — identically, so a phone
  /// outside the family cannot confirm that a dongle exists.
  Future<Device> resolve(String serial) async {
    final response = await _client.get(
      '/devices/${Uri.encodeComponent(serial)}',
    );
    return Device.fromJson(response.asMap);
  }

  /// [resolve], but null for an unknown serial.
  Future<Device?> resolveOrNull(String serial) async {
    try {
      return await resolve(serial);
    } on ApiException catch (error) {
      if (error.isNotFound) return null;
      rethrow;
    }
  }
}
