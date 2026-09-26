import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/home/widgets/shared_widgets.dart';
import 'package:obd_app/ui/screens/home/widgets/trip_people_sheet.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Activity widgets
/// ---------------------------------------------------------------------------

class SegmentedControl extends StatelessWidget {
  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int> onChanged;

  const SegmentedControl({
    super.key,
    required this.labels,
    required this.selectedIndex,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: t.surface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: GestureDetector(
                onTap: () => onChanged(i),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: const EdgeInsets.symmetric(vertical: 9),
                  decoration: BoxDecoration(
                    color: selectedIndex == i ? t.surface : Colors.transparent,
                    borderRadius: BorderRadius.circular(9),
                  ),
                  child: Text(
                    labels[i],
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: selectedIndex == i ? t.text : t.muted,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class PeriodPicker extends StatelessWidget {
  final String value;
  final ValueChanged<String> onChanged;

  const PeriodPicker({super.key, required this.value, required this.onChanged});

  static const periods = ['7 d', '30 d', '3 m', '6 m', '1 a'];

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (var i = 0; i < periods.length; i++) ...[
            if (i > 0) const SizedBox(width: 6),
            ChoiceChip(
              label: Text(periods[i]),
              selected: periods[i] == value,
              onSelected: (_) => onChanged(periods[i]),
              selectedColor: t.accent,
              labelStyle: TextStyle(
                color: periods[i] == value ? Colors.white : t.muted,
                fontWeight: FontWeight.w700,
                fontSize: 12,
              ),
              side: BorderSide(
                color: periods[i] == value ? t.accent : t.border,
              ),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(9),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class TripsContent extends StatelessWidget {
  final List<TripData> trips;
  final HomeController controller;

  const TripsContent({super.key, required this.trips, required this.controller});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Últimos viajes',
          style: TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.w800,
            letterSpacing: -.3,
            color: t.text,
          ),
        ),
        const SizedBox(height: 9),
        SectionCard(
          child: trips.isEmpty
              ? Text(
                  'Todavía no hay viajes en este auto. Arrancá uno desde la '
                  'pestaña Auto.',
                  style: TextStyle(fontSize: 12, color: t.muted),
                )
              : Column(
                  children: [
                    for (var i = 0; i < trips.length; i++) ...[
                      TripRow(trip: trips[i], onTap: () => _openTrip(context, trips[i].id)),
                      if (i < trips.length - 1) const Divider(height: 18),
                    ],
                  ],
                ),
        ),
      ],
    );
  }

  // El viaje completo (con driverId, carId, cost) no está en TripData — esa
  // es la forma que ya usaba el dashboard de antes de haber una API — así que
  // se busca en controller.carTrips, que sí lo tiene.
  void _openTrip(BuildContext context, String tripId) {
    for (final trip in controller.carTrips) {
      if (trip.id == tripId) {
        TripPeopleSheet.show(context, controller, trip);
        return;
      }
    }
  }
}

class TripRow extends StatelessWidget {
  final TripData trip;
  final VoidCallback? onTap;

  const TripRow({super.key, required this.trip, this.onTap});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final row = Row(
      children: [
        SizedBox(
          width: 73,
          child: Text(trip.day, style: TextStyle(fontSize: 11, color: t.muted)),
        ),
        AvatarStack(members: trip.drivers),
        const SizedBox(width: 6),
        Expanded(
          child: TextStack(
            title: trip.route,
            subtitle: trip.duration,
            titleStyle: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: trip.active ? t.accent : t.text,
            ),
            subtitleStyle: TextStyle(fontSize: 11, color: t.muted),
          ),
        ),
        Text(
          trip.distance,
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w800,
            color: t.text,
          ),
        ),
        if (onTap != null) ...[
          const SizedBox(width: 4),
          Icon(AppIcons.arrow, size: 16, color: t.muted),
        ],
      ],
    );

    if (onTap == null) return row;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: row),
    );
  }
}

class SummaryContent extends StatelessWidget {
  final ActivitySummaryData data;

  const SummaryContent({super.key, required this.data});

  Color _deltaColor(AppTokens t, ActivityStat stat) => switch (stat.improved) {
    true => t.success,
    false => t.warning,
    null => t.muted,
  };

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: StatCard(
                icon: AppIcons.trip,
                label: 'Distancia',
                value: data.distance.value,
                unit: data.distance.unit,
                delta: data.distance.delta,
                deltaColor: _deltaColor(t, data.distance),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: StatCard(
                icon: AppIcons.clock,
                label: 'Al volante',
                value: data.drivingTime.value,
                unit: data.drivingTime.unit,
                delta: data.drivingTime.delta,
                deltaColor: _deltaColor(t, data.drivingTime),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: StatCard(
                icon: AppIcons.fuel,
                label: 'Nafta',
                value: data.fuel.value,
                unit: data.fuel.unit,
                delta: data.fuel.delta,
                deltaColor: _deltaColor(t, data.fuel),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: StatCard(
                icon: AppIcons.car,
                label: 'Viajes',
                value: data.trips.value,
                unit: data.trips.unit,
                delta: data.trips.delta,
                deltaColor: _deltaColor(t, data.trips),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        ConsumptionChart(
          values: data.consumption,
          labels: data.consumptionLabels,
          peak: data.consumptionPeak,
        ),
        if (data.driverDistances.isNotEmpty) ...[
          const SizedBox(height: 12),
          DriverBreakdown(data: data.driverDistances),
        ],
      ],
    );
  }
}

class StatCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final String unit;
  final String delta;
  final Color deltaColor;

  const StatCard({
    super.key,
    required this.icon,
    required this.label,
    required this.value,
    required this.unit,
    required this.delta,
    required this.deltaColor,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      padding: const EdgeInsets.all(12),
      radius: 14,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 15, color: t.muted),
              const SizedBox(width: 5),
              Expanded(
                child: Text(
                  label,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    letterSpacing: .5,
                    color: t.muted,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 7),
          Text.rich(
            TextSpan(
              children: [
                TextSpan(
                  text: value,
                  style: TextStyle(
                    fontSize: 21,
                    fontWeight: FontWeight.w800,
                    color: t.text,
                  ),
                ),
                if (unit.isNotEmpty)
                  TextSpan(
                    text: ' $unit',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: t.muted,
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 4),
          Text(
            delta,
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w700,
              color: deltaColor,
            ),
          ),
        ],
      ),
    );
  }
}

class ConsumptionChart extends StatelessWidget {
  final List<double> values;

  /// Inicio, medio y fin del eje.
  final List<String> labels;
  final String peak;

  const ConsumptionChart({
    super.key,
    required this.values,
    required this.labels,
    required this.peak,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final maxValue = values.isEmpty ? 1.0 : values.reduce((a, b) => a > b ? a : b);

    // Prevent 0.0 / 0.0 NaN exceptions
    final safeDivisor = maxValue > 0 ? maxValue : 1.0;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(
            title: 'Nafta usada por ${values.length > 31 ? 'semana' : 'día'}',
            trailingText: peak.isEmpty ? 'sin consumo registrado' : peak,
          ),
          const SizedBox(height: 13),
          SizedBox(
            height: 104,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                for (final value in values)
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.symmetric(
                        horizontal: values.length > 14 ? 1 : 2,
                      ),
                      child: FractionallySizedBox(
                        heightFactor: value / safeDivisor, // Use the safe divisor here
                        alignment: Alignment.bottomCenter,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color:
                                value == maxValue &&
                                    maxValue >
                                        0 // Ensure 0-value bars aren't solid
                                ? t.accent
                                : t.accent.withValues(alpha: .70),
                            borderRadius: const BorderRadius.vertical(
                              top: Radius.circular(4),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 7),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              for (final label in labels) Text(label, style: TextStyle(fontSize: 10, color: t.muted)),
            ],
          ),
        ],
      ),
    );
  }
}

class DriverBreakdown extends StatelessWidget {
  final List<MemberDistance> data;

  const DriverBreakdown({super.key, required this.data});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final total = data.fold<double>(
      0,
      (sum, item) => sum + _parseKm(item.distance),
    );

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('Kilómetros por conductor'),
          const SizedBox(height: 13),
          Row(
            children: [
              SizedBox(
                width: 96,
                height: 96,
                child: CustomPaint(
                  painter: _DonutPainter(
                    values: data.map((item) => _parseKm(item.distance)).toList(),
                    colors: data.map((item) => item.member.color).toList(),
                  ),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          total.round().toString(),
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w800,
                            color: t.text,
                          ),
                        ),
                        Text(
                          'km',
                          style: TextStyle(fontSize: 10, color: t.muted),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  children: [
                    for (var i = 0; i < data.length; i++) ...[
                      LegendRow(
                        color: data[i].member.color,
                        name: data[i].member.name,
                        value: data[i].distance,
                      ),
                      if (i < data.length - 1) const SizedBox(height: 7),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  double _parseKm(String value) {
    final normalized = value.replaceAll('.', '').replaceAll('km', '').replaceAll(',', '.').trim();

    return double.tryParse(normalized) ?? 0;
  }
}

/// ---------------------------------------------------------------------------
/// Small custom painter
/// ---------------------------------------------------------------------------

class _DonutPainter extends CustomPainter {
  final List<double> values;
  final List<Color> colors;

  const _DonutPainter({required this.values, required this.colors});

  @override
  void paint(Canvas canvas, Size size) {
    if (values.isEmpty || colors.isEmpty) {
      return;
    }

    final total = values.fold<double>(0, (sum, value) => sum + value);

    if (total <= 0) return;

    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 16
      ..strokeCap = StrokeCap.butt;

    final rect = Offset.zero & size;
    var start = -1.5708;

    for (var i = 0; i < values.length && i < colors.length; i++) {
      final sweep = values[i] / total * 6.28318;

      if (sweep > 0.025) {
        paint.color = colors[i];

        canvas.drawArc(rect.deflate(8), start, sweep - .025, false, paint);
      }
      start += sweep;
    }
  }

  @override
  bool shouldRepaint(covariant _DonutPainter oldDelegate) {
    return oldDelegate.values != values || oldDelegate.colors != colors;
  }
}
