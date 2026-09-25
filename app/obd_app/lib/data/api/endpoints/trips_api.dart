import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/models/json.dart';

/// `/api/v1/trips/*` plus the two per-car reads.
///
/// A trip ("viaje") records that someone is using a car. The driver always
/// comes from the token and both timestamps from the server clock, so a trip
/// can be neither logged in someone else's name nor back-dated.
class TripsApi {
  const TripsApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/trips` — starts a trip and leaves it open. `201`.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [carId] | yes | a car the caller may use: their own, or one shared with a group they belong to |
  /// | [initialFuel] | no | reading at the start, ≥ 0. Defaults to the car's cached `fuelLevel` — what the dongle last reported |
  ///
  /// At most one trip may be open per car, enforced by a partial unique index
  /// rather than a check in the service: parallel starts yield one `201` and
  /// the rest `409`.
  ///
  /// Throws `ApiException`: `isNotFound` if the car does not exist **or** the
  /// caller may not use it, `isConflict` if the car is already on a trip.
  Future<Trip> start({required String carId, int? initialFuel}) async {
    final response = await _client.post(
      '/trips',
      body: {'carId': carId, 'initialFuel': ?initialFuel},
    );
    return Trip.fromJson(response.asMap);
  }

  /// `GET /api/v1/trips` — every trip the **caller** drove, in any car, newest
  /// first, open and finished alike (`active` tells them apart).
  ///
  /// The driver is always the token holder; there is no way to ask for someone
  /// else's history. Unpaged for now.
  Future<List<Trip>> mine() async {
    final response = await _client.get('/trips');
    return parseList(response.body, Trip.fromJson);
  }

  /// `GET /api/v1/cars/{carId}/trips` — **the car's** history: every trip on
  /// it by every driver, newest first, for anyone who may read the car.
  ///
  /// This is what a shared car's history is for — who used it, and who used
  /// the fuel.
  Future<List<Trip>> forCar(String carId) async {
    final response = await _client.get('/cars/$carId/trips');
    return parseList(response.body, Trip.fromJson);
  }

  /// `GET /api/v1/cars/{carId}/trips/active` — **"who has the car right
  /// now?"**.
  ///
  /// Returns null when the car is idle (the API answers `204 No Content`,
  /// which is a normal answer, not an error — `404` here means "no such
  /// car").
  Future<Trip?> active(String carId) async {
    final response = await _client.get('/cars/$carId/trips/active');
    if (response.isEmpty) return null;
    return Trip.fromJson(response.asMap);
  }

  /// `POST /api/v1/trips/{tripId}/finish` — the **driver** ends their trip.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [tripId] | yes | must be the caller's own open trip |
  /// | [tripFinalFuel] | no | reading at the end, ≥ 0. Absent means the expense is *unknown*, not zero |
  /// | [tripDistance] | no | > 0 |
  ///
  /// The end time is the server clock. [tripFinalFuel] may exceed the initial
  /// reading — the driver refuelled, and `fuelUsed` comes out negative rather
  /// than being validated away.
  ///
  /// Only the driver can finish their own trip: the car's owner cannot close
  /// it out from under them. A double tap is a `409`, not a second reading
  /// overwriting the first.
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown trip **or** someone
  /// else's, `isConflict` if it has already ended.
  Future<Trip> finish(
    String tripId, {
    int? tripFinalFuel,
    int? tripDistance,
  }) async {
    final response = await _client.post(
      '/trips/$tripId/finish',
      body: {
        'tripFinalFuel': ?tripFinalFuel,
        'tripDistance': ?tripDistance,
      },
    );
    return Trip.fromJson(response.asMap);
  }

  /// `DELETE /api/v1/trips/{tripId}` — cancels a trip started by mistake, as
  /// if it never happened. `204`.
  ///
  /// Only an **open** trip of the caller's own: a finished trip is history, it
  /// carries an expense, and it stays.
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown trip or someone
  /// else's, `isConflict` for one that has already ended.
  Future<void> cancel(String tripId) => _client.delete('/trips/$tripId');
}
