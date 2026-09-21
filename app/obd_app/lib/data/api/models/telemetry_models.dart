import 'package:obd_app/data/api/models/json.dart';

/// One reading **being uploaded** (`TelemetryDTO.Reading`).
///
/// Every field but [recordedAt] is optional: a frame that only carries fuel is
/// a perfectly good reading, and the server merges it into the car's snapshot
/// without blanking the fields it did not mention.
///
/// Two rules the server enforces, worth respecting locally to avoid a `400`
/// that rejects the whole batch:
///
/// * [latitude] and [longitude] go together or not at all — half a position is
///   no position.
/// * [recordedAt] may not be more than five minutes in the future. A dongle
///   with a broken clock would otherwise stamp a row that stays "the latest"
///   for ever.
class TelemetryReading {
  const TelemetryReading({
    required this.recordedAt,
    this.latitude,
    this.longitude,
    this.speed,
    this.fuelLevel,
    this.batteryLevel,
    this.mileage,
    this.raw,
  });

  /// When the reading was **taken** on the device, not when it is uploaded.
  /// This is what places it in the car's history.
  final DateTime recordedAt;

  final double? latitude;
  final double? longitude;
  final int? speed;
  final int? fuelLevel;
  final int? batteryLevel;
  final int? mileage;

  /// The frame exactly as the device sent it — a map, a list or a bare string
  /// like `"01045020"`. Stored verbatim as `jsonb`; the decoder is unfinished,
  /// so a value discarded here is gone for good.
  final Object? raw;

  /// Both coordinates or neither.
  bool get hasValidPosition => (latitude == null) == (longitude == null);

  bool get isInTheFuture =>
      recordedAt.toUtc().isAfter(
        DateTime.now().toUtc().add(const Duration(minutes: 5)),
      );

  Map<String, dynamic> toJson() => {
    'recordedAt': recordedAt.toUtc().toIso8601String(),
    if (latitude != null) 'latitude': latitude,
    if (longitude != null) 'longitude': longitude,
    if (speed != null) 'speed': speed,
    if (fuelLevel != null) 'fuelLevel': fuelLevel,
    if (batteryLevel != null) 'batteryLevel': batteryLevel,
    if (mileage != null) 'mileage': mileage,
    if (raw != null) 'raw': raw,
  };

  @override
  String toString() => 'TelemetryReading(${recordedAt.toIso8601String()})';
}

/// One reading **read back** (`TelemetryDTO.Read`). Same fields plus the two
/// the server adds: which trip it belongs to and when it actually arrived.
class TelemetryRecord {
  const TelemetryRecord({
    required this.recordedAt,
    this.tripId,
    this.receivedAt,
    this.latitude,
    this.longitude,
    this.speed,
    this.fuelLevel,
    this.batteryLevel,
    this.mileage,
    this.raw,
  });

  factory TelemetryRecord.fromJson(Map<String, dynamic> json) =>
      TelemetryRecord(
        tripId: json.str('tripId'),
        recordedAt: json.instant('recordedAt') ?? DateTime.now().toUtc(),
        receivedAt: json.instant('receivedAt'),
        latitude: json.decimal('latitude'),
        longitude: json.decimal('longitude'),
        speed: json.integer('speed'),
        fuelLevel: json.integer('fuelLevel'),
        batteryLevel: json.integer('batteryLevel'),
        mileage: json.integer('mileage'),
        raw: json['raw'],
      );

  /// The trip this reading was stamped with at ingestion, or null when the car
  /// was reporting outside any trip.
  final String? tripId;

  final DateTime recordedAt;

  /// Server clock. Only this explains why a reading taken in a tunnel arrived
  /// an hour late.
  final DateTime? receivedAt;

  final double? latitude;
  final double? longitude;
  final int? speed;
  final int? fuelLevel;
  final int? batteryLevel;
  final int? mileage;
  final Object? raw;

  bool get hasPosition => latitude != null && longitude != null;

  @override
  String toString() => 'TelemetryRecord(${recordedAt.toIso8601String()})';
}

/// `TelemetryDTO.Page` — one page of history, **oldest first**.
///
/// Ascending order with an exclusive cursor is what makes paging correct:
/// newest-first with a limit silently skips whatever falls between the newest
/// N and the cursor.
class TelemetryPage {
  const TelemetryPage({
    required this.carId,
    required this.readings,
    required this.hasMore,
    this.nextSince,
  });

  factory TelemetryPage.fromJson(Map<String, dynamic> json) => TelemetryPage(
    carId: json.str('carId') ?? '',
    readings: parseList(json['readings'], TelemetryRecord.fromJson),
    nextSince: json.instant('nextSince'),
    hasMore: json.flag('hasMore'),
  );

  final String carId;
  final List<TelemetryRecord> readings;

  /// Pass this back as `since` on the next call. When the page is empty it is
  /// the cursor you passed in, so it can always be stored unconditionally.
  final DateTime? nextSince;

  /// Keep calling while this is true.
  final bool hasMore;

  @override
  String toString() =>
      'TelemetryPage(${readings.length} readings, hasMore: $hasMore)';
}

/// `TelemetryDTO.Ingested` — what an upload returns.
///
/// Everything here is about the *client's* next move: which rows to drop from
/// the buffer, whether to redraw, and where to resume.
class TelemetryIngestResult {
  const TelemetryIngestResult({
    required this.carId,
    required this.stored,
    required this.duplicates,
    required this.snapshotUpdated,
    this.tripId,
    this.latestRecordedAt,
  });

  factory TelemetryIngestResult.fromJson(Map<String, dynamic> json) =>
      TelemetryIngestResult(
        carId: json.str('carId') ?? '',
        stored: json.integer('stored') ?? 0,
        duplicates: json.integer('duplicates') ?? 0,
        tripId: json.str('tripId'),
        snapshotUpdated: json.flag('snapshotUpdated'),
        latestRecordedAt: json.instant('latestRecordedAt'),
      );

  /// The car the readings landed on. When uploading by serial, this is how the
  /// phone learns which car it is plugged into.
  final String carId;

  /// How many rows were new.
  final int stored;

  /// How many were already there, from an earlier upload. **Success, not an
  /// error**: a retry that finds its readings present has done its job.
  final int duplicates;

  /// The car's open trip these readings were stamped with. Null means the car
  /// is reporting with no trip open — the cue to prompt the driver to start
  /// one.
  final String? tripId;

  /// False when the whole batch was older than what the car already knew:
  /// nothing to redraw.
  final bool snapshotUpdated;

  /// The newest `recordedAt` in the batch; the client's sync cursor.
  final DateTime? latestRecordedAt;

  int get accepted => stored + duplicates;

  bool get hasOpenTrip => tripId != null;

  @override
  String toString() =>
      'TelemetryIngestResult(stored: $stored, duplicates: $duplicates)';
}
