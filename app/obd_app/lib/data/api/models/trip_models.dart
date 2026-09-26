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
    this.cost,
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
    cost: json.integer('cost'),
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

  /// What the trip cost, typed in by the driver (see `TripsApi.setCost`).
  /// Null means "unknown", never "free" — that is what lets a split screen
  /// tell "nothing entered yet" apart from "this trip cost nothing".
  final int? cost;

  /// How long the trip ran (so far, if it is still open).
  Duration? get elapsed {
    final start = startedAt;
    if (start == null) return null;
    return (endedAt ?? DateTime.now().toUtc()).difference(start);
  }

  @override
  String toString() => 'Trip($id, ${active ? 'active' : 'finished'})';
}

/// `TripParticipantDTO.Read` — one person this trip's cost is split with,
/// besides the driver (who is intrinsic to the trip via `driverId` and is
/// never a row here — see `HomeController.driverName`).
///
/// Being listed here grants nothing: it does not add anyone to a group and it
/// does not share the car with them. It only means the driver counted them in
/// when dividing what the trip cost.
class TripParticipant {
  const TripParticipant({
    required this.id,
    required this.tripId,
    required this.name,
    required this.isGuest,
    this.userId,
    this.addedAt,
  });

  factory TripParticipant.fromJson(Map<String, dynamic> json) => TripParticipant(
    id: json.str('id') ?? '',
    tripId: json.str('tripId') ?? '',
    userId: json.str('userId'),
    name: json.str('name') ?? '',
    isGuest: json.flag('isGuest'),
    addedAt: json.instant('addedAt'),
  );

  final String id;
  final String tripId;

  /// Null for a guest with no account of their own.
  final String? userId;

  /// The account's name if [userId] is set, or the name/label the driver
  /// gave — resolved server-side either way.
  final String name;

  final bool isGuest;
  final DateTime? addedAt;

  @override
  String toString() => 'TripParticipant($name, ${isGuest ? 'guest' : userId})';
}

/// `TripParticipantDTO.Share` — one line of a [TripSplit]: a participant, or
/// the driver's own share (`isDriver`, with a null [participantId]).
class TripShare {
  const TripShare({
    required this.isGuest,
    required this.isDriver,
    required this.amount,
    this.participantId,
    this.userId,
    this.name,
  });

  factory TripShare.fromJson(Map<String, dynamic> json) => TripShare(
    participantId: json.str('participantId'),
    userId: json.str('userId'),
    name: json.str('name'),
    isGuest: json.flag('isGuest'),
    isDriver: json.flag('isDriver'),
    amount: json.integer('amount') ?? 0,
  );

  /// Null for the driver's own share.
  final String? participantId;
  final String? userId;

  /// Null for the driver's share — the caller already knows how to name
  /// them (`HomeController.driverName`).
  final String? name;

  final bool isGuest;
  final bool isDriver;
  final int amount;
}

/// `TripParticipantDTO.Split` — `GET /trips/{id}/split`: `cost` divided
/// evenly across everyone counted in, remainder handed out one unit at a time
/// so the shares always add back up to exactly `cost`.
class TripSplit {
  const TripSplit({
    required this.cost,
    required this.includesDriver,
    required this.shareCount,
    required this.shares,
  });

  factory TripSplit.fromJson(Map<String, dynamic> json) => TripSplit(
    cost: json.integer('cost') ?? 0,
    includesDriver: json.flag('includesDriver'),
    shareCount: json.integer('shareCount') ?? 0,
    shares: parseList(json['shares'], TripShare.fromJson),
  );

  final int cost;
  final bool includesDriver;
  final int shareCount;
  final List<TripShare> shares;
}
