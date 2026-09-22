import 'package:obd_app/models/member_data.dart';

/// Nafta del grupo en el período: cuánto usó cada miembro, según los viajes
/// (`fuelUsed` = nafta inicial − final de cada viaje terminado).
///
/// La API todavía no define la unidad de `fuelLevel`; el OBD la reporta como
/// porcentaje del tanque y así la muestra la UI.
class FuelSummaryData {
  final String periodLabel;

  /// Suma de `fuelUsed` de los viajes del período, en puntos de tanque.
  final int totalFuel;
  final int tripCount;
  final List<MemberData> members;

  const FuelSummaryData({
    required this.periodLabel,
    required this.totalFuel,
    required this.tripCount,
    required this.members,
  });

  bool get isEmpty => tripCount == 0;
}

/// Una fila de "Últimos viajes".
class TripData {
  final String id;
  final String day;
  final String route;
  final String distance;
  final String duration;
  final List<MemberData> drivers;
  final bool active;

  const TripData({
    required this.id,
    required this.day,
    required this.route,
    required this.distance,
    required this.duration,
    required this.drivers,
    this.active = false,
  });
}

/// Un número del resumen y cómo cambió contra el período anterior.
class ActivityStat {
  final String value;
  final String unit;
  final String delta;

  /// null = neutro; true = cambió para bien; false = para mal.
  final bool? improved;

  const ActivityStat({
    required this.value,
    this.unit = '',
    required this.delta,
    this.improved,
  });
}

/// El resumen de actividad de un período, ya formateado para dibujar.
class ActivitySummaryData {
  final ActivityStat distance;
  final ActivityStat drivingTime;
  final ActivityStat fuel;
  final ActivityStat trips;

  /// Nafta consumida por día (o por semana en períodos largos), de más viejo
  /// a más nuevo.
  final List<double> consumption;

  /// Etiquetas de inicio, medio y fin del eje del gráfico.
  final List<String> consumptionLabels;

  /// 'pico: sáb 14', o vacío si no hubo consumo.
  final String consumptionPeak;

  final List<MemberDistance> driverDistances;

  const ActivitySummaryData({
    required this.distance,
    required this.drivingTime,
    required this.fuel,
    required this.trips,
    required this.consumption,
    required this.consumptionLabels,
    required this.consumptionPeak,
    required this.driverDistances,
  });

  bool get isEmpty =>
      consumption.every((v) => v == 0) && driverDistances.isEmpty;
}

class MemberDistance {
  final MemberData member;
  final String distance;

  const MemberDistance(this.member, this.distance);
}
