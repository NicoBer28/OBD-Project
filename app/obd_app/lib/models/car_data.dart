import 'package:obd_app/models/schedule_slot.dart';

class CarData {
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
  final ScheduleSlot? nextTurn;

  const CarData({
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
    this.nextTurn,
  });

  int get autonomyKm =>
      (fuelPercent / 100 * fuelCapacityLiters * kmPerLiter).round();

  double get fuelLiters => fuelPercent / 100 * fuelCapacityLiters;
}
