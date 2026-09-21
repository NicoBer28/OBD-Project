import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/home/widgets/activity_widgets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

class ActivityTab extends StatefulWidget {
  final CarData carData;
  final List<TripData> trips;
  final ActivitySummaryData activityData;
  final bool showSummary;
  final String period;
  
  // NUEVOS PARÁMETROS NATIVOS
  final double? currentLat;
  final double? currentLng;
  
  final ValueChanged<bool> onShowSummaryChanged;
  final ValueChanged<String> onPeriodChanged;

  const ActivityTab({
    super.key,
    required this.carData,
    required this.trips,
    required this.activityData,
    required this.showSummary,
    required this.period,
    this.currentLat,
    this.currentLng,
    required this.onShowSummaryChanged,
    required this.onPeriodChanged,
  });

  @override
  State<ActivityTab> createState() => _ActivityTabState();
}

class _ActivityTabState extends State<ActivityTab> {
  bool _mapReady = false;
  final MapController _mapController = MapController();

  // MÁGIA DE FLUTTER: Se ejecuta cada vez que el "MainScreen" le pasa coordenadas nuevas
  @override
  void didUpdateWidget(ActivityTab oldWidget) {
    super.didUpdateWidget(oldWidget);
    
    // Si llegaron coordenadas frescas y el mapa ya cargó, movemos la cámara respetando el zoom del usuario
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
    // Validamos si el hardware ya nos mandó datos válidos
    final hasLocation = widget.currentLat != null && widget.currentLng != null;
    final currentPosition = hasLocation ? LatLng(widget.currentLat!, widget.currentLng!) : null;

    return ListView(
      key: const ValueKey('activity'),
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
      children: [
        PageHeader(
          title: 'Actividad y Mapa',
          subtitle: '${widget.carData.name} - GPS en vivo',
        ),
        const SizedBox(height: 18),
        
        SizedBox(
          height: 300,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: !hasLocation 
                ? const Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CircularProgressIndicator(),
                        SizedBox(height: 16),
                        Text(
                          'Esperando datos del vehículo en segundo plano...', 
                          style: TextStyle(color: Colors.grey)
                        ),
                      ],
                    ),
                  )
                : FlutterMap(
                    mapController: _mapController,
                    options: MapOptions(
                      initialCenter: currentPosition!,
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
                            point: currentPosition,
                            width: 40,
                            height: 40,
                            child: const Icon(
                              Icons.location_pin,
                              size: 40,
                              color: Colors.red,
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
          PeriodPicker(value: widget.period, onChanged: widget.onPeriodChanged),
          const SizedBox(height: 16),
          SummaryContent(data: widget.activityData),
        ] else ...[
          const SizedBox(height: 18),
          TripsContent(trips: widget.trips),
        ],
      ],
    );
  }
}