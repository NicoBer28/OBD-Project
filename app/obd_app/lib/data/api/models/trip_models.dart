import 'package:obd_app/data/api/models/json.dart';

/// `TripDTO.Read` — one "viaje".
///
/// A car's current trip, and therefore its current driver, **is** the open row
/// here: the car holds no pointer back to it, so the two can never disagree.
/// At most one trip may be open per car, enforced by a partial unique index.
class Trip {
  const Trip({
    required this.id,
    required this.carId,
    required this.driverId,
    required this.active,
    this.startedAt,
    this.endedAt,
    this.initialFuel,
    this.finalFuel,
    this.fuelUsed,
    this.distance,
  });

  factory Trip.fromJson(Map<String, dynamic> json) => Trip(
    id: json.str('id') ?? '',
    carId: json.str('carId') ?? '',
    driverId: json.str('driverId') ?? '',
    startedAt: json.instant('startedAt'),
    endedAt: json.instant('endedAt'),
    initialFuel: json.integer('initialFuel'),
    finalFuel: json.integer('finalFuel'),
    fuelUsed: json.integer('fuelUsed'),
    distance: json.integer('distance'),
    active: json.flag('active'),
  );

  final String id;
  final String carId;

  /// Always the token holder at the time the trip started — a trip cannot be
  /// logged in someone else's name.
  final String driverId;

  /// Server clock, not the client's.
  final DateTime? startedAt;

  /// Null while the trip is running.
  final DateTime? endedAt;

  /// Defaults to the car's cached `fuelLevel` when the client sends nothing.
  final int? initialFuel;

  final int? finalFuel;

  /// `initialFuel - finalFuel`, computed on every read and never stored. Null
  /// while running, and afterwards if either reading is missing. May be
  /// negative: the driver refuelled.
  final int? fuelUsed;

  final int? distance;

  /// `endedAt == null`, spelled out by the API so clients do not infer it.
  final bool active;

  /// How long the trip ran (so far, if it is still open).
  Duration? get elapsed {
    final start = startedAt;
    if (start == null) return null;
    return (endedAt ?? DateTime.now().toUtc()).difference(start);
  }

  @override
  String toString() => 'Trip($id, ${active ? 'active' : 'finished'})';
}
