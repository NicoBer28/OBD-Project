import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';

/// `/api/v1/telemetry` — the readings themselves.
///
/// The ESP32 speaks BLE and never HTTP, so **the phone is the relay**: it
/// buffers what the dongle sent and uploads it in batches, possibly late,
/// possibly repeating something it was unsure about. Both endpoints are shaped
/// for that: a retry is always safe on the way up, and history is read forward
/// from a cursor on the way down.
class TelemetryApi {
  const TelemetryApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/telemetry` — stores a batch and refreshes the car's cached
  /// snapshot. `200` (not `201`: an all-duplicate batch creates nothing).
  ///
  /// **Name the car exactly one way:**
  ///
  /// * [serial] — the dongle's serial as read over BLE. The normal path: the
  ///   server resolves it to the paired car, so the phone never has to know
  ///   which car it is plugged into and cannot get it wrong. Also refreshes
  ///   the dongle's `lastSeenAt`.
  /// * [carId] — the car directly. For a car with no dongle, manual entry, or
  ///   tests. Says nothing about hardware, so `lastSeenAt` is untouched.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [serial] / [carId] | exactly one | passing both or neither is a `400` |
  /// | [readings] | yes | 1–500 entries |
  ///
  /// Per reading: `recordedAt` is required and is *device* time; latitude and
  /// longitude go together or not at all; speed, fuel, battery and mileage are
  /// each ≥ 0; `raw` is any JSON, kept verbatim.
  ///
  /// A malformed reading rejects the **whole batch** with errors keyed by
  /// index (`readings[2].positionComplete`) — half-applying would leave the
  /// phone's buffer state unknowable. Duplicates, by contrast, are success:
  /// they come back in `duplicates`, and there is deliberately no `409`.
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown car, an unknown
  /// serial, or one paired to a car the caller may not see (all the same
  /// answer); `isValidation` for a bad batch.
  Future<TelemetryIngestResult> upload({
    required List<TelemetryReading> readings,
    String? serial,
    String? carId,
  }) async {
    if ((serial == null) == (carId == null)) {
      throw ArgumentError('Pass exactly one of serial or carId');
    }
    if (readings.isEmpty || readings.length > 500) {
      throw ArgumentError('A batch holds between 1 and 500 readings');
    }

    final response = await _client.post(
      '/telemetry',
      body: {
        if (serial != null) 'serial': serial,
        if (carId != null) 'carId': carId,
        'readings': readings.map((r) => r.toJson()).toList(),
      },
    );
    return TelemetryIngestResult.fromJson(response.asMap);
  }

  /// `GET /api/v1/telemetry` — one page of a car's readings after a cursor,
  /// **oldest first**.
  ///
  /// | Query parameter | Required | Notes |
  /// |---|---|---|
  /// | [carId] | yes | must be a car the caller may read |
  /// | [since] | no | returns readings recorded **strictly after** it. Absent means from the beginning |
  /// | [limit] | no | 1–500, default 100 |
  ///
  /// [since] is exclusive because the client passes back a value it was
  /// already given (`nextSince` from the last page, or `latestRecordedAt` from
  /// an upload) and therefore already holds that row.
  ///
  /// Throws `ApiException` with `isValidation` for a limit outside 1–500 or an
  /// unparseable [since], `isNotFound` if the car is not readable.
  Future<TelemetryPage> history({
    required String carId,
    DateTime? since,
    int? limit,
  }) async {
    final response = await _client.get(
      '/telemetry',
      query: {'carId': carId, 'since': since, 'limit': limit},
    );
    return TelemetryPage.fromJson(response.asMap);
  }

  /// Walks [history] to the end and returns everything after [since].
  ///
  /// The loop is the whole point of the cursor design: ascending order with an
  /// exclusive cursor covers the series with no gaps and no repeats.
  ///
  /// [maxPages] is a seat belt, not a limit of the API — at the default page
  /// size it reads 25 000 readings before giving up, which is far more than a
  /// phone should pull in one go.
  Future<List<TelemetryRecord>> readAll({
    required String carId,
    DateTime? since,
    int pageSize = 500,
    int maxPages = 50,
  }) async {
    final all = <TelemetryRecord>[];
    var cursor = since;

    for (var page = 0; page < maxPages; page++) {
      final result = await history(carId: carId, since: cursor, limit: pageSize);
      all.addAll(result.readings);
      cursor = result.nextSince ?? cursor;
      if (!result.hasMore) break;
    }

    return all;
  }
}
