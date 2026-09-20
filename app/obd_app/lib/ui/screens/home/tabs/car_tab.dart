import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/home/widgets/car_widgets.dart';
import 'package:obd_app/ui/screens/home/widgets/shared_widgets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Auto
/// ---------------------------------------------------------------------------

class CarTab extends StatelessWidget {
  final CarData carData;
  final String initials;
  final double fuel;
  final int speed;
  final int rpm;
  final String connectionStatus;
  final ValueChanged<double> onFuelChanged;
  final VoidCallback onNavigateToShared;
  final VoidCallback onNavigateToProfile;

  const CarTab({
    super.key,
    required this.carData,
    required this.initials,
    required this.fuel,
    required this.speed,
    required this.rpm,
    required this.connectionStatus,
    required this.onFuelChanged,
    required this.onNavigateToShared,
    required this.onNavigateToProfile,
  });

  void _showStartJourneySheet(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.tokens.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetContext) {
        return Padding(
          padding: EdgeInsets.fromLTRB(
            20,
            24,
            20,
            MediaQuery.of(sheetContext).viewInsets.bottom + 24,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Configurar viaje',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -.5,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Definí el recorrido antes de comenzar.',
                style: TextStyle(color: sheetContext.tokens.muted),
              ),
              const SizedBox(height: 22),
              const LocationField(
                label: 'Punto de partida',
                hint: 'Ubicación actual',
                icon: Icons.my_location_rounded,
              ),
              const SizedBox(height: 12),
              const LocationField(
                label: 'Destino',
                hint: 'Ej. Av. Corrientes 980',
                icon: Icons.flag_outlined,
              ),
              const SizedBox(height: 18),
              ActionTile(
                icon: AppIcons.qr,
                title: 'Sumar pasajeros',
                subtitle: 'Mostrá el QR para dividir el costo.',
                onTap: () {},
              ),
              const SizedBox(height: 20),
              SizedBox(
                width: double.infinity,
                height: 54,
                child: FilledButton.icon(
                  onPressed: () => Navigator.pop(sheetContext),
                  icon: const Icon(Icons.play_arrow_rounded),
                  label: const Text('Iniciar viaje'),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      key: const ValueKey('car'),
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
      children: [
        PageHeader(
          title: carData.name,
          subtitle: '${carData.brand} · ${carData.plate}',
          trailing: ProfileAvatar(
            initials: initials,
            onTap: onNavigateToProfile,
          ),
        ),
        const SizedBox(height: 18),
        CarHero(
          fuelPercent: fuel,
          fuelCapacityLiters: carData.fuelCapacityLiters,
          autonomyKm:
              (fuel / 100 * carData.fuelCapacityLiters * carData.kmPerLiter)
                  .round(),
          fuelLiters: fuel / 100 * carData.fuelCapacityLiters,
        ),
        const SizedBox(height: 10),
        VitalGrid(
          items: [
            VitalData(
              label: 'Batería',
              value: '${carData.batteryVoltage.toStringAsFixed(1)} V',
              icon: AppIcons.battery,
            ),
            const VitalData(
              label: 'Estado',
              value: 'OK',
              icon: Icons.check_circle_outline_rounded,
              valueColor: AppColors.success,
            ),
            VitalData(
              label: 'Service',
              value: carData.service,
              icon: AppIcons.service,
              valueColor: AppColors.warning,
            ),
          ],
        ),
        const SizedBox(height: 10),
        ParkingCard(car: carData),
        if (carData.nextTurn != null) ...[
          const SizedBox(height: 10),
          NextTurnCard(slot: carData.nextTurn!, onTap: onNavigateToShared),
        ],
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: () => _showStartJourneySheet(context),
          icon: const Icon(Icons.play_arrow_rounded),
          label: const Text('Iniciar viaje'),
        ),
        const SizedBox(height: 16),
        TelemetryCard(
          speed: speed,
          rpm: rpm,
          fuel: fuel,
          status: connectionStatus,
          onFuelChanged: onFuelChanged,
        ),
      ],
    );
  }
}
