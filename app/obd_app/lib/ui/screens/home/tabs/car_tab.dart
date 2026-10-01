import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/controllers/reservations_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/connection_format.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/cars/create_car_screen.dart';
import 'package:obd_app/ui/screens/cars/my_cars_screen.dart';
import 'package:obd_app/ui/screens/home/widgets/car_widgets.dart';
import 'package:obd_app/ui/screens/home/widgets/home_state_widgets.dart';
import 'package:obd_app/ui/screens/home/widgets/trip_sheets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';
import 'package:obd_app/src/generated/obd_api.g.dart';

/// ---------------------------------------------------------------------------
/// Auto
/// ---------------------------------------------------------------------------

class CarTab extends StatelessWidget {
  final HomeController controller;
  final ReservationsController? reservations;
  final String initials;

  /// Nafta en vivo por BLE (o del simulador); null hasta que llegue algo.
  final double? liveFuel;
  final int speed;
  final int rpm;
  final EstadoConexion conexion;

  /// Con la conexión perdida: cuándo se da por terminado el viaje si no vuelve.
  final DateTime? finGracia;
  final ValueChanged<double> onFuelChanged;
  final VoidCallback onNavigateToShared;
  final VoidCallback onNavigateToActivity;
  final VoidCallback onNavigateToProfile;

  const CarTab({
    super.key,
    required this.controller,
    required this.reservations,
    required this.initials,
    required this.liveFuel,
    required this.speed,
    required this.rpm,
    required this.conexion,
    this.finGracia,
    required this.onFuelChanged,
    required this.onNavigateToShared,
    required this.onNavigateToActivity,
    required this.onNavigateToProfile,
  });

  int? _fuelFor(Car car) => liveFuel?.round() ?? car.fuelLevel;

  Future<void> _startTrip(BuildContext context, Car car) async {
    final trip = await StartTripSheet.show(
      context,
      controller,
      currentFuel: _fuelFor(car),
    );
    if (trip != null && context.mounted) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('¡Buen viaje!')));
    }
  }

  Future<void> _finishTrip(BuildContext context, Car car) async {
    final trip = await FinishTripSheet.show(
      context,
      controller,
      currentFuel: _fuelFor(car),
    );
    if (trip != null && context.mounted) {
      final used = trip.fuelUsed;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(
              used == null
                  ? 'Viaje terminado'
                  : used < 0
                  ? 'Viaje terminado · cargaste ${-used} %'
                  : 'Viaje terminado · usaste $used % de nafta',
            ),
          ),
        );
    }
  }

  Future<void> _switchCar(BuildContext context) async {
    final t = context.tokens;
    final chosen = await showModalBottomSheet<Car>(
      context: context,
      backgroundColor: t.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '¿Qué auto?',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -.5,
                ),
              ),
              const SizedBox(height: 12),
              for (final c in controller.cars)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: IconBadge(
                    icon: AppIcons.car,
                    color: c.id == controller.car?.id ? t.accent : t.muted,
                  ),
                  title: Text(
                    c.name,
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: t.text,
                    ),
                  ),
                  subtitle: Text(
                    c.isShared ? '${c.model.label} · ${c.group!.name}' : c.model.label,
                    style: TextStyle(fontSize: 11, color: t.muted),
                  ),
                  trailing: c.id == controller.car?.id ? Icon(AppIcons.check, color: t.accent) : null,
                  onTap: () => Navigator.pop(sheetContext, c),
                ),
              const Divider(),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: IconBadge(icon: AppIcons.settings, color: t.muted),
                title: Text(
                  'Administrar autos',
                  style: TextStyle(fontWeight: FontWeight.w700, color: t.text),
                ),
                onTap: () {
                  Navigator.pop(sheetContext);
                  MyCarsScreen.show(context, controller);
                },
              ),
            ],
          ),
        ),
      ),
    );
    if (chosen != null) controller.selectCar(chosen.id);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final car = controller.car;

        if (car == null) {
          return ListView(
            key: const ValueKey('car'),
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
            children: [
              PageHeader(
                title: 'Tu auto',
                subtitle: 'Todavía no cargaste ninguno',
                trailing: ProfileAvatar(
                  initials: initials,
                  onTap: onNavigateToProfile,
                ),
              ),
              const SizedBox(height: 18),
              EmptyStateCard(
                icon: AppIcons.car,
                title: 'Agregá tu primer auto',
                message:
                    'Elegí el modelo del catálogo y empezá a ver cómo está. '
                    'Si alguien te compartió el suyo, va a aparecer acá solo.',
                actionLabel: 'Agregar mi auto',
                onAction: () => CreateCarScreen.show(context, controller),
                secondaryLabel: 'Ver invitaciones',
                secondaryIcon: AppIcons.shared,
                onSecondary: onNavigateToShared,
              ),
              const SizedBox(height: 16),
              TelemetryCard(
                car: controller.car,
                speed: speed,
                rpm: rpm,
                fuel: liveFuel ?? 0,
                conexion: conexion,
                finGracia: finGracia,
                onFuelChanged: onFuelChanged,
              ),
            ],
          );
        }

        final fuel = _fuelFor(car);
        final active = controller.activeTrip;
        final device = controller.device;
        // Se cortó la conexión con el auto y todavía corre el tiempo de gracia. Si el viaje
        // en curso es de otro conductor, lo que le pase a este celular no cuenta.
        final sinConexion = conexion == EstadoConexion.reconectando && (active == null || controller.activeTripIsMine);

        return RefreshIndicator(
          onRefresh: controller.refresh,
          child: ListView(
            key: const ValueKey('car'),
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
            children: [
              PageHeader(
                title: car.name,
                subtitle: [
                  car.model.label,
                  if (car.licensePlate != null) car.licensePlate!,
                  if (car.isShared) car.group!.name,
                ].join(' · '),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: controller.cars.length > 1 ? 'Cambiar de auto' : 'Mis autos',
                      onPressed: () =>
                          controller.cars.length > 1 ? _switchCar(context) : MyCarsScreen.show(context, controller),
                      icon: Icon(
                        controller.cars.length > 1 ? AppIcons.swap : AppIcons.settings,
                      ),
                    ),
                    ProfileAvatar(
                      initials: initials,
                      onTap: onNavigateToProfile,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 18),
              CarHero(
                fuelPercent: fuel,
                mileage: car.mileage,
                lastSeen: car.snapshotAt,
              ),
              const SizedBox(height: 10),
              VitalGrid(
                items: [
                  VitalData(
                    label: 'Batería',
                    value: car.batteryLevel == null ? '—' : '${car.batteryLevel}%',
                    icon: AppIcons.battery,
                  ),
                  // La conexión de este celular con el auto pisa al estado del viaje mientras
                  // está a medias: recién detectado, o cortado con el viaje todavía abierto.
                  VitalData(
                    label: 'Estado',
                    value: sinConexion
                        ? ConexionTextos.estadoSinConexion
                        : active == null
                        ? (conexion == EstadoConexion.conectando ? ConexionTextos.estadoConectando : 'Libre')
                        : controller.activeTripIsMine
                        ? 'Con vos'
                        : 'En viaje',
                    icon: sinConexion
                        ? Icons.bluetooth_disabled
                        : active == null
                        ? Icons.check_circle_outline_rounded
                        : AppIcons.trip,
                    valueColor: sinConexion
                        ? AppColors.danger
                        : active == null && conexion != EstadoConexion.conectando
                        ? AppColors.success
                        : AppColors.warning,
                  ),
                  VitalData(
                    label: 'Dongle',
                    value: device == null ? 'Sin vincular' : device.serial,
                    icon: AppIcons.dongle,
                    valueColor: device == null ? AppColors.warning : null,
                  ),
                ],
              ),
              const SizedBox(height: 10),
              ParkingCard(car: car, onTap: onNavigateToActivity),
              if (active != null)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: ActiveTripCard(
                    trip: active,
                    driverName: controller.driverName(active.driverId),
                    isMine: controller.activeTripIsMine,
                    connectionLost: sinConexion,
                    graceEndsAt: finGracia,
                    onFinish: () => _finishTrip(context, car),
                  ),
                ),
              if (reservations case final r?)
                ListenableBuilder(
                  listenable: r,
                  builder: (context, _) {
                    final next = r.nextTurn();
                    if (next == null) return const SizedBox.shrink();

                    return Padding(
                      padding: const EdgeInsets.only(top: 10),
                      child: NextTurnCard(
                        slot: r.slotFor(next),
                        onTap: onNavigateToShared,
                      ),
                    );
                  },
                ),
              const SizedBox(height: 16),
              if (active == null)
                FilledButton.icon(
                  onPressed: () => _startTrip(context, car),
                  icon: const Icon(Icons.play_arrow_rounded),
                  label: const Text('Iniciar viaje'),
                )
              else if (controller.activeTripIsMine)
                FilledButton.icon(
                  onPressed: () => _finishTrip(context, car),
                  icon: const Icon(Icons.stop_rounded),
                  label: const Text('Terminar viaje'),
                )
              else
                OutlinedButton.icon(
                  onPressed: () async {
                    await controller.refreshActiveTrip();
                    if (context.mounted && controller.activeTrip != null) {
                      ScaffoldMessenger.of(context)
                        ..hideCurrentSnackBar()
                        ..showSnackBar(
                          SnackBar(
                            content: Text(
                              '${controller.driverName(controller.activeTrip!.driverId)} '
                              'todavía tiene el auto.',
                            ),
                          ),
                        );
                    }
                  },
                  icon: const Icon(AppIcons.refresh),
                  label: const Text('¿Ya lo devolvieron?'),
                ),
              const SizedBox(height: 16),
              TelemetryCard(
                car: controller.car,
                speed: speed,
                rpm: rpm,
                fuel: liveFuel ?? (car.fuelLevel ?? 0).toDouble(),
                conexion: conexion,
                finGracia: finGracia,
                onFuelChanged: onFuelChanged,
              ),
              if (controller.detailsLoading)
                const Padding(
                  padding: EdgeInsets.only(top: 12),
                  child: Center(
                    child: SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}
