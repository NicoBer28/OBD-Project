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

  /// `PUT /api/v1/trips/{tripId}/cost` — sets, replaces or clears what the
  /// trip cost. A single slot, like `CarsApi.share`: `null` clears it.
  ///
  /// The **driver** only, but unlike [finish] this works after the trip has
  /// already ended — the real cost (a fuel receipt, a toll) is often only
  /// known afterwards.
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown trip or someone
  /// else's.
  Future<Trip> setCost(String tripId, int? cost) async {
    final response = await _client.put('/trips/$tripId/cost', body: {'cost': cost});
    return Trip.fromJson(response.asMap);
  }

  // ---------------------------------------------------------------------------
  // Acompañantes ("who this trip's cost is split with")
  // ---------------------------------------------------------------------------

  /// `GET /api/v1/trips/{tripId}/participants` — everyone the trip's cost is
  /// split with, besides the driver, oldest first.
  ///
  /// Readable by anyone who may read the trip's car — the rest of the family,
  /// typically, same as [forCar].
  Future<List<TripParticipant>> participants(String tripId) async {
    final response = await _client.get('/trips/$tripId/participants');
    return parseList(response.body, TripParticipant.fromJson);
  }

  /// `POST /api/v1/trips/{tripId}/participants` — "select a user already on
  /// the group".
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [tripId] | yes | the caller's own trip |
  /// | [userId] | yes | must be able to read the trip's car — the same people `GroupsApi.members` offers |
  ///
  /// The **driver** only. Throws `ApiException`: `isNotFound` for an unknown
  /// trip or someone else's, `isConflict` if [userId] does not share the car,
  /// or is already a participant.
  Future<TripParticipant> addParticipant({required String tripId, required String userId}) async {
    final response = await _client.post('/trips/$tripId/participants', body: {'userId': userId});
    return TripParticipant.fromJson(response.asMap);
  }

  /// `POST /api/v1/trips/{tripId}/participants/by-email` — "invite them via
  /// their user/QR ... in case they are not part of the group".
  ///
  /// Looked up by email — exactly what scanning someone's "Mi código QR"
  /// already yields. Added either way, unlike [addParticipant] with **no**
  /// check that they share the car: that is the point of this path.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [email] | yes | looked up against existing accounts |
  /// | [name] | yes | used only when no account matches — there is no cheap way to know that in advance |
  ///
  /// The **driver** only. Throws `ApiException`: `isNotFound` for an unknown
  /// trip or someone else's, `isConflict` if that email is already a
  /// participant.
  Future<TripParticipant> inviteParticipant({
    required String tripId,
    required String email,
    required String name,
  }) async {
    final response = await _client.post(
      '/trips/$tripId/participants/by-email',
      body: {'email': email, 'name': name},
    );
    return TripParticipant.fromJson(response.asMap);
  }

  /// `POST /api/v1/trips/{tripId}/participants/guests` — "let the owner count
  /// the persons": a participant with no account and no email, just a name.
  ///
  /// [name] may be a generated label ("Acompañante 2") for a quick headcount,
  /// or a real one when the driver has it.
  ///
  /// The **driver** only.
  Future<TripParticipant> addGuest({required String tripId, required String name}) async {
    final response = await _client.post('/trips/$tripId/participants/guests', body: {'name': name});
    return TripParticipant.fromJson(response.asMap);
  }

  /// `DELETE /api/v1/trips/{tripId}/participants/{participantId}`. `204`.
  ///
  /// The **driver** only. Throws `ApiException` `isNotFound` for an unknown
  /// participant, an unknown trip, or someone else's trip.
  Future<void> removeParticipant({required String tripId, required String participantId}) =>
      _client.delete('/trips/$tripId/participants/$participantId');

  /// `GET /api/v1/trips/{tripId}/split` — `cost` divided evenly across the
  /// driver (when [includeDriver]) and every participant.
  ///
  /// [includeDriver] defaults to `true`: the common case is the driver
  /// fronted the money and wants their own share back too, not just to
  /// recoup everyone else's. With no participants at all it is included
  /// regardless — there is nobody else the cost could belong to.
  ///
  /// Readable by anyone who may read the trip's car — a participant checking
  /// what they owe is exactly what this is for.
  ///
  /// Throws `ApiException` `isConflict` if [setCost] was never called for
  /// this trip.
  Future<TripSplit> split(String tripId, {bool includeDriver = true}) async {
    final response = await _client.get('/trips/$tripId/split', query: {'includeDriver': includeDriver});
    return TripSplit.fromJson(response.asMap);
  }
}
