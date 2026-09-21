import 'package:flutter/material.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/models/models.dart';

/// De los modelos de la API a los que dibujan los widgets.
///
/// Los widgets del dashboard nacieron sobre `DemoData` con modelos propios
/// (`MemberData`, `TripData`, …). En vez de reescribirlos, se les da lo mismo
/// que esperaban, pero calculado a partir de lo que devuelve el servidor.
/// Todo lo que acá es una fórmula (nafta por miembro, km por conductor,
/// variación contra el período anterior) sale de los viajes del auto:
/// `GET /cars/{id}/trips` trae cada viaje con conductor, nafta y distancia.
abstract final class ApiMappers {
  static const _memberColors = [
    AppPalette.member1,
    AppPalette.member2,
    AppPalette.member3,
    AppPalette.member4,
  ];

  static Color memberColor(int index) =>
      _memberColors[index % _memberColors.length];

  static String initialsOf(String name, String fallback) {
    final parts = name
        .trim()
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .toList();
    if (parts.length >= 2) {
      return '${parts[0][0]}${parts[1][0]}'.toUpperCase();
    }
    if (parts.length == 1 && parts[0].isNotEmpty) {
      return parts[0].substring(0, parts[0].length.clamp(0, 2)).toUpperCase();
    }
    return fallback;
  }

  // ---------------------------------------------------------------------------
  // Miembros
  // ---------------------------------------------------------------------------

  /// Los miembros del grupo con su parte de la nafta de [trips].
  ///
  /// Yo aparezco como "Vos" (así lo hacía el demo). Si el grupo está vacío
  /// (auto sin compartir) la lista es solo yo, para que las tarjetas tengan
  /// a quién atribuirle los viajes.
  static List<MemberData> members({
    required List<GroupMember> members,
    required UserProfile? me,
    required List<Trip> trips,
  }) {
    final fuelByDriver = <String, int>{};
    var total = 0;
    for (final t in trips) {
      final used = t.fuelUsed;
      if (used == null || used <= 0) continue;
      fuelByDriver[t.driverId] = (fuelByDriver[t.driverId] ?? 0) + used;
      total += used;
    }

    MemberData build(String id, String name, String email, int index) {
      final used = fuelByDriver[id] ?? 0;
      final isMe = me != null && id == me.id;
      // `GET /groups/{id}/members` trae solo el nombre de pila; para mí
      // tengo el perfil completo y las iniciales quedan como en el avatar.
      return MemberData(
        id: id,
        initials: isMe
            ? me.initials
            : initialsOf(name, email.isNotEmpty ? email[0].toUpperCase() : '?'),
        name: isMe ? 'Vos' : name,
        color: memberColor(index),
        fuelShare: total == 0 ? 0 : used / total,
        fuelAmount: used == 0 ? '—' : '$used %',
      );
    }

    if (members.isEmpty) {
      if (me == null) return const [];
      return [build(me.id, me.fullName, me.userEmail, 0)];
    }

    // Yo primero, con el color 1 (el "Vos" del demo), y el resto en el orden
    // del servidor (admins primero, después por nombre).
    final sorted = [...members]
      ..sort((a, b) {
        final aMe = me != null && a.userId == me.id;
        final bMe = me != null && b.userId == me.id;
        if (aMe != bMe) return aMe ? -1 : 1;
        return 0;
      });

    return [
      for (var i = 0; i < sorted.length; i++)
        build(sorted[i].userId, sorted[i].name, sorted[i].email, i),
    ];
  }

  static MemberData memberOf(List<MemberData> members, String userId) {
    for (final m in members) {
      if (m.id == userId) return m;
    }
    return MemberData(
      id: userId,
      initials: '?',
      name: 'Miembro',
      color: AppPalette.surface2,
      fuelShare: 0,
      fuelAmount: '',
    );
  }

  // ---------------------------------------------------------------------------
  // Nafta del grupo (mes en curso)
  // ---------------------------------------------------------------------------

  /// Los viajes terminados del mes en curso.
  static List<Trip> thisMonth(List<Trip> trips, [DateTime? now]) {
    final n = (now ?? DateTime.now()).toLocal();
    final from = DateTime(n.year, n.month).toUtc();
    return trips
        .where(
          (t) =>
              !t.active && t.startedAt != null && !t.startedAt!.isBefore(from),
        )
        .toList(growable: false);
  }

  static FuelSummaryData fuelSummary({
    required List<MemberData> members,
    required List<Trip> monthTrips,
    DateTime? now,
  }) {
    var total = 0;
    for (final t in monthTrips) {
      final used = t.fuelUsed;
      if (used != null && used > 0) total += used;
    }
    return FuelSummaryData(
      periodLabel: TripFormat.monthName(now ?? DateTime.now()),
      totalFuel: total,
      tripCount: monthTrips.length,
      members: members.where((m) => m.fuelShare > 0).toList(growable: false),
    );
  }

  // ---------------------------------------------------------------------------
  // Viajes
  // ---------------------------------------------------------------------------

  static List<TripData> trips(
    List<Trip> trips, {
    required List<MemberData> members,
    DateTime? now,
  }) {
    final n = now ?? DateTime.now();
    return [
      for (final t in trips)
        TripData(
          id: t.id,
          day: t.startedAt == null ? '—' : TripFormat.dayLabel(t.startedAt!, n),
          route: t.startedAt == null
              ? 'Viaje'
              : TripFormat.timeRange(t.startedAt!, t.endedAt),
          distance: t.distance == null ? '—' : TripFormat.km(t.distance!),
          duration: _tripSubtitle(t),
          drivers: [memberOf(members, t.driverId)],
          active: t.active,
        ),
    ];
  }

  static String _tripSubtitle(Trip t) {
    final parts = <String>[];
    final elapsed = t.elapsed;
    if (elapsed != null) parts.add(TripFormat.duration(elapsed));
    final used = t.fuelUsed;
    if (used != null) {
      parts.add(used < 0 ? 'cargó ${-used} %' : '$used % nafta');
    }
    return parts.isEmpty ? 'Sin datos' : parts.join(' · ');
  }

  // ---------------------------------------------------------------------------
  // Resumen de actividad
  // ---------------------------------------------------------------------------

  /// Las opciones de `PeriodPicker` → cuántos días hacia atrás.
  static int periodDays(String period) => switch (period) {
    '7 d' => 7,
    '30 d' => 30,
    '3 m' => 90,
    '6 m' => 180,
    '1 a' => 365,
    _ => 30,
  };

  static ActivitySummaryData activity(
    List<Trip> trips, {
    required String period,
    required List<MemberData> members,
    DateTime? now,
  }) {
    final n = (now ?? DateTime.now()).toUtc();
    final days = periodDays(period);
    final from = n.subtract(Duration(days: days));
    final previousFrom = from.subtract(Duration(days: days));

    bool within(Trip t, DateTime start, DateTime end) {
      final s = t.startedAt;
      return s != null && !s.isBefore(start) && s.isBefore(end);
    }

    final current = trips.where((t) => within(t, from, n)).toList();
    final previous = trips.where((t) => within(t, previousFrom, from)).toList();

    final distance = _sumDistance(current);
    final time = _sumTime(current);
    final fuel = _sumFuel(current);

    return ActivitySummaryData(
      distance: ActivityStat(
        value: TripFormat.thousands(distance),
        unit: 'km',
        delta: _delta(distance, _sumDistance(previous), period),
      ),
      drivingTime: ActivityStat(
        value: TripFormat.duration(time),
        delta: _delta(time.inMinutes, _sumTime(previous).inMinutes, period),
      ),
      fuel: ActivityStat(
        value: '$fuel',
        unit: '% del tanque',
        delta: _delta(fuel, _sumFuel(previous), period, lowerIsBetter: true),
        improved: _improved(fuel, _sumFuel(previous), lowerIsBetter: true),
      ),
      trips: ActivityStat(
        value: '${current.length}',
        unit: current.length == 1 ? 'viaje' : 'viajes',
        delta: _delta(current.length, previous.length, period),
      ),
      consumption: _consumptionBuckets(current, from: from, days: days),
      consumptionLabels: _axisLabels(from, n, days),
      consumptionPeak: _peakLabel(current, from: from, days: days),
      driverDistances: _driverDistances(current, members),
    );
  }

  static int _sumDistance(List<Trip> trips) =>
      trips.fold(0, (sum, t) => sum + (t.distance ?? 0));

  static Duration _sumTime(List<Trip> trips) => trips.fold(
    Duration.zero,
    (sum, t) => t.active ? sum : sum + (t.elapsed ?? Duration.zero),
  );

  static int _sumFuel(List<Trip> trips) => trips.fold(
    0,
    (sum, t) => sum + ((t.fuelUsed ?? 0) > 0 ? t.fuelUsed! : 0),
  );

  static bool? _improved(num now, num before, {bool lowerIsBetter = false}) {
    if (before == 0 || now == before) return null;
    final up = now > before;
    return lowerIsBetter ? !up : up;
  }

  /// '↑ 12% vs. período anterior', '↓ 6%', 'sin datos previos', '= igual'.
  static String _delta(
    num now,
    num before,
    String period, {
    bool lowerIsBetter = false,
  }) {
    if (before == 0) return now == 0 ? 'sin actividad' : 'sin datos previos';
    final change = ((now - before) / before * 100).round();
    if (change == 0) return 'igual que antes';
    final arrow = change > 0 ? '↑' : '↓';
    return '$arrow ${change.abs()}% vs. $period anteriores';
  }

  /// Un bucket por día (períodos ≤ 31 días) o por semana.
  static List<double> _consumptionBuckets(
    List<Trip> trips, {
    required DateTime from,
    required int days,
  }) {
    final perWeek = days > 31;
    final count = perWeek ? (days / 7).ceil() : days;
    final buckets = List<double>.filled(count, 0);
    for (final t in trips) {
      final used = t.fuelUsed;
      final s = t.startedAt;
      if (used == null || used <= 0 || s == null) continue;
      final offset = s.difference(from).inDays;
      final index = (perWeek ? offset ~/ 7 : offset).clamp(0, count - 1);
      buckets[index] += used;
    }
    return buckets;
  }

  static List<String> _axisLabels(DateTime from, DateTime to, int days) {
    final mid = from.add(Duration(days: days ~/ 2));
    return [
      TripFormat.dayMonth(from),
      TripFormat.dayMonth(mid),
      TripFormat.dayMonth(to),
    ];
  }

  static String _peakLabel(
    List<Trip> trips, {
    required DateTime from,
    required int days,
  }) {
    final buckets = _consumptionBuckets(trips, from: from, days: days);
    var best = 0;
    for (var i = 1; i < buckets.length; i++) {
      if (buckets[i] > buckets[best]) best = i;
    }
    if (buckets.isEmpty || buckets[best] == 0) return '';
    final perWeek = days > 31;
    final start = from.add(Duration(days: perWeek ? best * 7 : best));
    return perWeek
        ? 'pico: semana del ${TripFormat.dayMonth(start)}'
        : 'pico: ${TripFormat.shortDay(start)}';
  }

  static List<MemberDistance> _driverDistances(
    List<Trip> trips,
    List<MemberData> members,
  ) {
    final byDriver = <String, int>{};
    for (final t in trips) {
      final d = t.distance;
      if (d == null || d <= 0) continue;
      byDriver[t.driverId] = (byDriver[t.driverId] ?? 0) + d;
    }
    final entries = byDriver.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    return [
      for (final e in entries)
        MemberDistance(memberOf(members, e.key), TripFormat.km(e.value)),
    ];
  }
}
