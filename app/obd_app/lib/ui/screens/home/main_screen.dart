import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/constants/demo_data.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/src/generated/obd_api.g.dart';
import 'package:obd_app/ui/screens/home/tabs/activity_tab.dart';
import 'package:obd_app/ui/screens/home/tabs/car_tab.dart';
import 'package:obd_app/ui/screens/home/tabs/profile_tab.dart';
import 'package:obd_app/ui/screens/home/tabs/shared_tab.dart';
import 'package:permission_handler/permission_handler.dart';

/// ---------------------------------------------------------------------------
/// Main screen
///
/// Owns the app state (selected tab, telemetry coming from the native
/// background service, UI toggles) and hands it down to the tab widgets.
/// ---------------------------------------------------------------------------

class MainScreen extends StatefulWidget {
  final String nombreUsuario;

  final CarData? car;
  final FuelSummaryData? fuelSummary;
  final List<MemberData>? members;
  final List<ScheduleSlot>? schedule;
  final List<TripData>? trips;
  final ActivitySummaryData? activity;

  const MainScreen({
    super.key,
    required this.nombreUsuario,
    this.car,
    this.fuelSummary,
    this.members,
    this.schedule,
    this.trips,
    this.activity,
  });

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> implements ObdFlutterApi {
  late final CarData _carData;
  late final FuelSummaryData _fuelData;
  late final List<MemberData> _members;
  late final List<ScheduleSlot> _schedule;
  late final List<TripData> _trips;
  late final ActivitySummaryData _activityData;

  int _tab = 0;
  bool _showSummary = false;
  bool _maintenanceAlerts = true;
  bool _tripAlerts = true;
  String _period = '30 d';

  double _fuel = DemoData.car.fuelPercent;
  int _speed = 0;
  int _rpm = 0;
  String _connectionStatus = 'Desconectado';

  double? _currentLat;
  double? _currentLng;

  @override
  void initState() {
    super.initState();

    _carData = widget.car ?? DemoData.car;
    _fuelData = widget.fuelSummary ?? DemoData.fuel;
    _members = widget.members ?? DemoData.members;
    _schedule = widget.schedule ?? DemoData.schedule;
    _trips = widget.trips ?? DemoData.trips;
    _activityData = widget.activity ?? DemoData.activity;

    _fuel = _carData.fuelPercent;

    ObdFlutterApi.setUp(this);
    _solicitarPermisos();
  }

  /// -------------------------------------------------------------------------
  /// BLE
  /// -------------------------------------------------------------------------

  Future<void> _solicitarPermisos() async {
    await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.location,
      Permission.notification,
    ].request();
  }

  @override
  void dispose() {
    // Nos desuscribimos al cerrar la pantalla
    ObdFlutterApi.setUp(null);
    super.dispose();
  }

  // acá llega el dato directo desde el background service nativo
  @override
  void onTelemetryUpdated(TelemetryEvent event) {
    if (!mounted) return;

    setState(() {
      _speed = event.speed ?? 0;
      _rpm = event.rpm ?? 0;
      _fuel = (event.fuel ?? 0).toDouble();

      // Capturamos el GPS en vivo que viene desde el hardware
      if (event.lat != null && event.lng != null) {
        _currentLat = event.lat;
        _currentLng = event.lng;
      }

      if (_speed > 0 || _rpm > 0) {
        _connectionStatus = 'Conectado';
      }
    });
  }

  /// -------------------------------------------------------------------------
  /// Navigation / pages
  /// -------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final pages = [
      CarTab(
        carData: _carData,
        initials: _initials,
        fuel: _fuel,
        speed: _speed,
        rpm: _rpm,
        connectionStatus: _connectionStatus,
        onFuelChanged: (value) => setState(() => _fuel = value),
        onNavigateToShared: () => setState(() => _tab = 1),
        onNavigateToProfile: () => setState(() => _tab = 3),
      ),
      SharedTab(members: _members, fuelData: _fuelData, schedule: _schedule),
      ActivityTab(
        carData: _carData,
        trips: _trips,
        activityData: _activityData,
        showSummary: _showSummary,
        period: _period,
        currentLat: _currentLat, // INYECTAMOS LA LATITUD
        currentLng: _currentLng, // INYECTAMOS LA LONGITUD
        onShowSummaryChanged: (value) => setState(() => _showSummary = value),
        onPeriodChanged: (value) => setState(() => _period = value),
      ),
      ProfileTab(
        nombreUsuario: widget.nombreUsuario,
        initials: _initials,
        carData: _carData,
        connectionStatus: _connectionStatus,
        maintenanceAlerts: _maintenanceAlerts,
        tripAlerts: _tripAlerts,
        onMaintenanceAlertsChanged: (value) =>
            setState(() => _maintenanceAlerts = value),
        onTripAlertsChanged: (value) => setState(() => _tripAlerts = value),
      ),
    ];

    return Scaffold(
      body: SafeArea(
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 180),
          child: pages[_tab],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (index) {
          setState(() => _tab = index);
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(AppIcons.car),
            selectedIcon: Icon(AppIcons.car),
            label: 'Auto',
          ),
          NavigationDestination(
            icon: Icon(AppIcons.shared),
            selectedIcon: Icon(AppIcons.shared),
            label: 'Compartido',
          ),
          NavigationDestination(
            icon: Icon(AppIcons.activity),
            selectedIcon: Icon(AppIcons.activity),
            label: 'Actividad',
          ),
          NavigationDestination(
            icon: Icon(AppIcons.profile),
            selectedIcon: Icon(AppIcons.profile),
            label: 'Perfil',
          ),
        ],
      ),
    );
  }

  String get _initials {
    final name = widget.nombreUsuario.trim().split('@').first;

    if (name.isEmpty) return 'LM';

    final parts = name
        .split(RegExp(r'[\s._-]+'))
        .where((part) => part.isNotEmpty)
        .toList();

    if (parts.length >= 2) {
      return '${parts.first[0]}${parts[1][0]}'.toUpperCase();
    }

    return name.substring(0, name.length.clamp(0, 2)).toUpperCase();
  }
}
