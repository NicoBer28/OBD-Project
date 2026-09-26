import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_mappers.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/ui/screens/home/widgets/activity_widgets.dart';
import 'package:obd_app/ui/screens/home/widgets/home_state_widgets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Actividad
///
/// El mapa muestra el GPS en vivo que llega del servicio nativo y, hasta que
/// llegue, la última posición que el servidor guardó del auto. Los viajes y
/// el resumen salen de `GET /cars/{id}/trips`.
/// ---------------------------------------------------------------------------

class ActivityTab extends StatefulWidget {
  final HomeController controller;
  final bool showSummary;
  final String period;

  // GPS en vivo (servicio nativo)
  final double? currentLat;
  final double? currentLng;

  final ValueChanged<bool> onShowSummaryChanged;
  final ValueChanged<String> onPeriodChanged;
  final VoidCallback onNavigateToCar;

  const ActivityTab({
    super.key,
    required this.controller,
    required this.showSummary,
    required this.period,
    this.currentLat,
    this.currentLng,
    required this.onShowSummaryChanged,
    required this.onPeriodChanged,
    required this.onNavigateToCar,
  });

  @override
  State<ActivityTab> createState() => _ActivityTabState();
}

class _ActivityTabState extends State<ActivityTab> {
  bool _mapReady = false;
  final MapController _mapController = MapController();

  // Se ejecuta cada vez que MainScreen pasa coordenadas nuevas
  @override
  void didUpdateWidget(ActivityTab oldWidget) {
    super.didUpdateWidget(oldWidget);

    // Si llegaron coordenadas frescas y el mapa ya cargó, movemos la cámara
    // respetando el zoom del usuario
    if (_mapReady && widget.currentLat != null && widget.currentLng != null) {
      final newPos = LatLng(widget.currentLat!, widget.currentLng!);
      final currentZoom = _mapController.camera.zoom;
      _mapController.move(newPos, currentZoom);
    }
  }

  @override
  void dispose() {
    _mapController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final c = widget.controller;

    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final car = c.car;

        if (car == null) {
          return ListView(
            key: const ValueKey('activity'),
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
            children: [
              const PageHeader(title: 'Actividad', subtitle: 'Sin auto'),
              const SizedBox(height: 18),
              EmptyStateCard(
                icon: AppIcons.activity,
                title: 'Nada que mostrar todavía',
                message: 'Cuando tengas un auto vas a ver acá sus viajes y su posición.',
                actionLabel: 'Ir a Auto',
                actionIcon: AppIcons.car,
                onAction: widget.onNavigateToCar,
              ),
            ],
          );
        }

        // GPS en vivo primero; si no hay, la última posición del servidor.
        final live = widget.currentLat != null && widget.currentLng != null;
        final position = live
            ? LatLng(widget.currentLat!, widget.currentLng!)
            : car.hasPosition
            ? LatLng(car.latitude!, car.longitude!)
            : null;

        final members = ApiMappers.members(
          members: c.members,
          me: c.me,
          trips: ApiMappers.thisMonth(c.carTrips),
        );
        final trips = ApiMappers.trips(c.carTrips, members: members);
        final summary = ApiMappers.activity(
          c.carTrips,
          period: widget.period,
          members: members,
        );

        return RefreshIndicator(
          onRefresh: c.refresh,
          child: ListView(
            key: const ValueKey('activity'),
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
            children: [
              PageHeader(
                title: 'Actividad y Mapa',
                subtitle: live
                    ? '${car.name} · GPS en vivo'
                    : car.snapshotAt == null
                    ? '${car.name} · sin posición'
                    : '${car.name} · última posición ${TripFormat.ago(car.snapshotAt!)}',
              ),
              const SizedBox(height: 18),

              SizedBox(
                height: 300,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(16),
                  child: position == null
                      ? ColoredBox(
                          color: t.surface2,
                          child: Center(
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  AppIcons.location,
                                  size: 32,
                                  color: t.muted,
                                ),
                                const SizedBox(height: 12),
                                Text(
                                  'Esperando la posición del vehículo…',
                                  style: TextStyle(color: t.muted),
                                ),
                              ],
                            ),
                          ),
                        )
                      : FlutterMap(
                          mapController: _mapController,
                          options: MapOptions(
                            initialCenter: position,
                            initialZoom: 16.0,
                            onMapReady: () {
                              _mapReady = true;
                            },
                          ),
                          children: [
                            TileLayer(
                              urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                              userAgentPackageName: 'com.example.obd_app',
                            ),
                            MarkerLayer(
                              markers: [
                                Marker(
                                  point: position,
                                  width: 40,
                                  height: 40,
                                  child: Icon(
                                    Icons.location_pin,
                                    size: 40,
                                    color: live ? Colors.red : t.accent3,
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                ),
              ),

              const SizedBox(height: 18),
              SegmentedControl(
                labels: const ['Viajes', 'Resumen'],
                selectedIndex: widget.showSummary ? 1 : 0,
                onChanged: (index) => widget.onShowSummaryChanged(index == 1),
              ),
              if (widget.showSummary) ...[
                const SizedBox(height: 12),
                PeriodPicker(
                  value: widget.period,
                  onChanged: widget.onPeriodChanged,
                ),
                const SizedBox(height: 16),
                SummaryContent(data: summary),
              ] else ...[
                const SizedBox(height: 18),
                TripsContent(trips: trips, controller: c),
              ],
            ],
          ),
        );
      },
    );
  }
}
