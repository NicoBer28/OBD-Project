import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/models/json.dart';

/// `/api/v1/cars/*` plus `GET /groups/{groupId}/cars`.
///
/// Every read here goes through one server-side predicate — owner **or**
/// member of the group the car is shared with — so a car that shows up in
/// [list] is by construction a car you can start a trip on and upload
/// telemetry for.
class CarsApi {
  const CarsApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/cars` — registers a car owned by the caller. `201`.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [name] | yes | ≤ 60 chars; the label shown in car lists |
  /// | [modelId] | yes | a `modelId` from `ModelsApi.list()` |
  /// | [licensePlate] | no | ≤ 16 chars; trimmed and upper-cased server-side |
  /// | [mileage] | no | odometer at registration, ≥ 0 |
  ///
  /// The owner comes from the token, never the body. Fuel, battery and
  /// position are not accepted here — they only ever arrive from the device.
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown [modelId],
  /// `isConflict` when you already have a car with that plate (plates are
  /// unique per owner, not globally), `isValidation` otherwise.
  Future<Car> create({
    required String name,
    required String modelId,
    String? licensePlate,
    int? mileage,
  }) async {
    final plate = licensePlate?.trim();
    final response = await _client.post(
      '/cars',
      body: {
        'name': name,
        'modelId': modelId,
        if (plate != null && plate.isNotEmpty) 'licensePlate': plate,
        if (mileage != null) 'mileage': mileage,
      },
    );
    return Car.fromJson(response.asMap);
  }

  /// `GET /api/v1/cars` — every car the caller may use: their own plus any
  /// shared with a group they belong to, ordered by name.
  ///
  /// Empty for a new user, never an error.
  Future<List<Car>> list() async {
    final response = await _client.get('/cars');
    return parseList(response.body, Car.fromJson);
  }

  /// `GET /api/v1/cars/{carId}` — one car.
  ///
  /// A client that already has [list] gains nothing here; it is for deep links
  /// and for refreshing one car's snapshot after an upload.
  ///
  /// Throws `ApiException` with `isNotFound` both when the car does not exist
  /// and when it belongs to someone you share no group with.
  Future<Car> byId(String carId) async {
    final response = await _client.get('/cars/$carId');
    return Car.fromJson(response.asMap);
  }

  /// `PUT /api/v1/cars/{carId}/group` — shares a car you **own** with a group
  /// you **belong to**. Every member can then see it, drive it and upload for
  /// it.
  ///
  /// `PUT`, not `POST`: a car is in at most one group at a time, so sharing
  /// with a second group moves it. Re-sharing with the same group is a no-op.
  ///
  /// Throws `ApiException` with `isNotFound` if the car is not yours *or* you
  /// are not in [groupId] — so this cannot be used to probe group ids.
  Future<Car> share({required String carId, required String groupId}) async {
    final response = await _client.put(
      '/cars/$carId/group',
      body: {'groupId': groupId},
    );
    return Car.fromJson(response.asMap);
  }

  /// `DELETE /api/v1/cars/{carId}/group` — un-shares. Owner-only, idempotent,
  /// `204`.
  ///
  /// Members lose access immediately, but an **open trip is left alone**: a
  /// trip is a record of what happened, not a permission. They just cannot
  /// start the next one.
  Future<void> unshare(String carId) => _client.delete('/cars/$carId/group');

  /// `GET /api/v1/groups/{groupId}/cars` — the cars shared with a group, for
  /// any member of it. `[]` when nobody has shared one yet.
  ///
  /// Throws `ApiException` with `isNotFound` if the caller is not a member.
  Future<List<Car>> forGroup(String groupId) async {
    final response = await _client.get('/groups/$groupId/cars');
    return parseList(response.body, Car.fromJson);
  }
}
