import 'package:obd_app/data/api/models/json.dart';

/// `CarDTO.Read` — a car the caller may use.
///
/// Everything from [mileage] down is a **cached snapshot** of the last reading
/// the dongle reported, refreshed by `POST /telemetry`. All null on a car that
/// has never reported, and never accepted from a client.
class Car {
  const Car({
    required this.id,
    required this.name,
    required this.model,
    this.licensePlate,
    this.mileage,
    this.fuelLevel,
    this.batteryLevel,
    this.latitude,
    this.longitude,
    this.snapshotAt,
    this.group,
  });

  factory Car.fromJson(Map<String, dynamic> json) => Car(
    id: json.str('id') ?? '',
    name: json.str('name') ?? '',
    licensePlate: json.str('licensePlate'),
    model: CarModelRef.fromJson(json.object('model')),
    mileage: json.integer('mileage'),
    fuelLevel: json.integer('fuelLevel'),
    batteryLevel: json.integer('batteryLevel'),
    latitude: json.decimal('latitude'),
    longitude: json.decimal('longitude'),
    snapshotAt: json.instant('snapshotAt'),
    group: CarGroupRef.fromJsonOrNull(json.object('group')),
  );

  final String id;

  /// The label shown in car lists, e.g. `Ada's Gol`.
  final String name;

  /// Trimmed and upper-cased by the server; unique per owner, not globally.
  final String? licensePlate;

  final CarModelRef model;

  final int? mileage;

  /// Percentage or litres is still undecided server-side, so treat it as an
  /// opaque number for now (see the API's ROADMAP §2).
  final int? fuelLevel;

  final int? batteryLevel;
  final double? latitude;
  final double? longitude;

  /// `recordedAt` of the reading the snapshot came from — "last seen".
  final DateTime? snapshotAt;

  /// The group this car is shared with, or null (the common case).
  final CarGroupRef? group;

  bool get isShared => group != null;

  bool get hasPosition => latitude != null && longitude != null;

  /// Has the car ever reported?
  bool get hasSnapshot => snapshotAt != null;

  @override
  String toString() => 'Car($name, ${licensePlate ?? 'no plate'})';
}

/// The `model` block nested inside a car. Different field names from the
/// catalog's own shape — see [CarModel].
class CarModelRef {
  const CarModelRef({
    required this.id,
    required this.brand,
    required this.model,
    required this.protocol,
  });

  factory CarModelRef.fromJson(Map<String, dynamic>? json) {
    if (json == null) {
      return const CarModelRef(id: '', brand: '', model: '', protocol: '');
    }
    return CarModelRef(
      id: json.str('id') ?? '',
      brand: json.str('brand') ?? '',
      model: json.str('model') ?? '',
      protocol: json.str('protocol') ?? '',
    );
  }

  final String id;
  final String brand;
  final String model;

  /// OBD-II protocol, e.g. `ISO 15765-4 (CAN)`.
  final String protocol;

  String get label => '$brand $model'.trim();

  @override
  String toString() => label;
}

/// The `group` block nested inside a car: just enough to name it.
class CarGroupRef {
  const CarGroupRef({required this.id, required this.name});

  static CarGroupRef? fromJsonOrNull(Map<String, dynamic>? json) {
    if (json == null) return null;
    return CarGroupRef(id: json.str('id') ?? '', name: json.str('name') ?? '');
  }

  final String id;
  final String name;

  @override
  String toString() => name;
}

/// `ModelDTO.Read` — one entry of the car model catalog (`GET /models`).
///
/// The catalog is curated: `POST /cars` takes a [modelId] from here rather
/// than free text, so the table cannot fill up with `VW` / `vw` /
/// `Volkswagen` variants of the same car.
class CarModel {
  const CarModel({
    required this.modelId,
    required this.modelBrand,
    required this.modelName,
    required this.modelProtocol,
  });

  factory CarModel.fromJson(Map<String, dynamic> json) => CarModel(
    modelId: json.str('modelId') ?? '',
    modelBrand: json.str('modelBrand') ?? '',
    modelName: json.str('modelName') ?? '',
    modelProtocol: json.str('modelProtocol') ?? '',
  );

  final String modelId;
  final String modelBrand;
  final String modelName;
  final String modelProtocol;

  String get label => '$modelBrand $modelName'.trim();

  @override
  String toString() => label;
}

/// `DeviceDTO.Read` — the OBD dongle paired to a car.
///
/// The mapping lives on the server so every phone in the family resolves the
/// same serial to the same car, and so a dongle moved between cars re-routes
/// everyone at once.
class Device {
  const Device({
    required this.id,
    required this.serial,
    required this.carId,
    required this.carName,
    this.pairedAt,
    this.lastSeenAt,
  });

  factory Device.fromJson(Map<String, dynamic> json) => Device(
    id: json.str('id') ?? '',
    serial: json.str('serial') ?? '',
    carId: json.str('carId') ?? '',
    carName: json.str('carName') ?? '',
    pairedAt: json.instant('pairedAt'),
    lastSeenAt: json.instant('lastSeenAt'),
  );

  final String id;

  /// Stored trimmed and upper-cased, so `a4:cf:12` and `A4:CF:12` are one
  /// device.
  final String serial;

  final String carId;
  final String carName;
  final DateTime? pairedAt;

  /// Server clock, set only by uploads that name the serial. Null until the
  /// dongle has delivered its first batch.
  final DateTime? lastSeenAt;

  bool get hasReported => lastSeenAt != null;

  @override
  String toString() => 'Device($serial -> $carName)';
}
