import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/native_bridge.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

class VitalData {
  final String label;
  final String value;
  final IconData icon;
  final Color? valueColor;

  const VitalData({
    required this.label,
    required this.value,
    required this.icon,
    this.valueColor,
  });
}

class CarHero extends StatelessWidget {
  final double fuelPercent;
  final double fuelCapacityLiters;
  final int autonomyKm;
  final double fuelLiters;

  const CarHero({
    super.key,
    required this.fuelPercent,
    required this.fuelCapacityLiters,
    required this.autonomyKm,
    required this.fuelLiters,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      radius: 20,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
      child: Column(
        children: [
          SizedBox(
            height: 106,
            child: Center(child: Icon(AppIcons.car, size: 96, color: t.accent)),
          ),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: TextStack(
                  title: '$autonomyKm km',
                  titleStyle: TextStyle(
                    fontSize: 30,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -1,
                    color: t.text,
                  ),
                  subtitle: 'AUTONOMÍA',
                  subtitleStyle: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: .7,
                    color: t.muted,
                  ),
                  spacing: 4,
                ),
              ),
              TextStack(
                crossAxisAlignment: CrossAxisAlignment.end,
                title: '${fuelPercent.round()}%',
                titleStyle: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                  color: t.text,
                ),
                subtitle:
                    '${fuelLiters.toStringAsFixed(0)} L de '
                    '${fuelCapacityLiters.toStringAsFixed(0)}',
                subtitleStyle: TextStyle(fontSize: 11, color: t.muted),
                spacing: 3,
              ),
            ],
          ),
          const SizedBox(height: 9),
          ClipRRect(
            borderRadius: BorderRadius.circular(99),
            child: LinearProgressIndicator(
              value: (fuelPercent / 100).clamp(0, 1),
              minHeight: 8,
              backgroundColor: t.surface2,
              color: t.accent,
            ),
          ),
        ],
      ),
    );
  }
}

class VitalGrid extends StatelessWidget {
  final List<VitalData> items;

  const VitalGrid({super.key, required this.items});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (var i = 0; i < items.length; i++) ...[
          if (i > 0) const SizedBox(width: 8),
          Expanded(child: VitalCard(data: items[i])),
        ],
      ],
    );
  }
}

class VitalCard extends StatelessWidget {
  final VitalData data;

  const VitalCard({super.key, required this.data});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      radius: 14,
      padding: const EdgeInsets.all(11),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(data.icon, size: 17, color: t.muted),
          const SizedBox(height: 7),
          Text(
            data.label,
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w700,
              letterSpacing: .5,
              color: t.muted,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            data.value,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w800,
              color: data.valueColor ?? t.text,
            ),
          ),
        ],
      ),
    );
  }
}

class ParkingCard extends StatelessWidget {
  final CarData car;

  const ParkingCard({super.key, required this.car});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      padding: const EdgeInsets.all(13),
      child: Row(
        children: [
          IconBadge(icon: AppIcons.location, color: t.accent3),
          const SizedBox(width: 11),
          Expanded(
            child: TextStack(
              title: car.parkingAddress,
              subtitle: '${car.parkingMeta} · lo dejó ${car.parkedBy}',
              titleStyle: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: t.text,
              ),
              subtitleStyle: TextStyle(fontSize: 11, color: t.muted),
            ),
          ),
          TextButton(onPressed: () {}, child: const Text('Ir')),
        ],
      ),
    );
  }
}

class NextTurnCard extends StatelessWidget {
  final ScheduleSlot slot;
  final VoidCallback onTap;

  const NextTurnCard({super.key, required this.slot, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Material(
      color: t.surface2,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.all(13),
          child: Row(
            children: [
              Icon(AppIcons.calendar, size: 19, color: t.member1),
              const SizedBox(width: 10),
              Expanded(
                child: TextStack(
                  title: 'Tu próximo turno',
                  subtitle: '${slot.day} · ${slot.time}',
                  titleStyle: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    color: t.muted,
                    letterSpacing: .4,
                  ),
                  subtitleStyle: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                    color: t.text,
                  ),
                ),
              ),
              Text(
                slot.person,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                  color: t.member1,
                ),
              ),
              Icon(AppIcons.arrow, size: 18, color: t.muted),
            ],
          ),
        ),
      ),
    );
  }
}

/// ---------------------------------------------------------------------------
/// BLE card
/// ---------------------------------------------------------------------------

class TelemetryCard extends StatelessWidget {
  final int speed;
  final int rpm;
  final double fuel;
  final String status;
  final ValueChanged<double> onFuelChanged;

  const TelemetryCard({
    super.key,
    required this.speed,
    required this.rpm,
    required this.fuel,
    required this.status,
    required this.onFuelChanged,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final hasData = speed > 0 || rpm > 0;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 9,
                height: 9,
                decoration: BoxDecoration(
                  color: hasData ? t.success : t.warning,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  status,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: t.text,
                  ),
                ),
              ),
              Icon(AppIcons.bluetooth, size: 18, color: t.muted),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              MetricPill(label: 'Velocidad', value: '$speed km/h'),
              const SizedBox(width: 8),
              MetricPill(label: 'RPM', value: '$rpm'),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              icon: const Icon(Icons.bluetooth_searching),
              label: const Text('Vincular ESP32 (Fondo)'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.blue.shade700,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              onPressed: () async {
                await NativeBleBridge.iniciarVinculacion();
              },
            ),
          ),
          const SizedBox(height: 16),
          Text(
            'Simulador de nafta UI',
            style: TextStyle(fontSize: 10, color: t.muted),
          ),
          Slider(
            value: fuel.clamp(0, 100),
            onChanged: onFuelChanged,
            min: 0,
            max: 100,
          ),
        ],
      ),
    );
  }
}

class MetricPill extends StatelessWidget {
  final String label;
  final String value;

  const MetricPill({super.key, required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(
          color: t.surface2,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 9,
                color: t.muted,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              value,
              style: TextStyle(
                fontSize: 12,
                color: t.text,
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
