class CarData {
  final String id;
  final String name;
  final String brand;
  final String plate;
  final double fuelPercent;
  final double fuelCapacityLiters;
  final double kmPerLiter;
  final double batteryVoltage;
  final String service;
  final String parkingAddress;
  final String parkingMeta;
  final String parkedBy;

  const CarData({
    required this.id,
    required this.name,
    required this.brand,
    required this.plate,
    required this.fuelPercent,
    required this.fuelCapacityLiters,
    required this.kmPerLiter,
    required this.batteryVoltage,
    required this.service,
    required this.parkingAddress,
    required this.parkingMeta,
    required this.parkedBy,
  });

  int get autonomyKm =>
      (fuelPercent / 100 * fuelCapacityLiters * kmPerLiter).round();

  double get fuelLiters => fuelPercent / 100 * fuelCapacityLiters;
}
