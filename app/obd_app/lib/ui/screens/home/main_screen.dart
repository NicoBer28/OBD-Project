import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/controllers/reservations_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_mappers.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/data/in_memory_reservation_repository.dart';
import 'package:obd_app/data/reservation_repository.dart';
import 'package:obd_app/data/telemetry_uploader.dart';
import 'package:obd_app/src/generated/obd_api.g.dart';
import 'package:obd_app/ui/screens/auth/login_screen.dart';
import 'package:obd_app/ui/screens/home/tabs/activity_tab.dart';
import 'package:obd_app/ui/screens/home/tabs/car_tab.dart';
import 'package:obd_app/ui/screens/home/tabs/profile_tab.dart';
import 'package:obd_app/ui/screens/home/tabs/shared_tab.dart';
import 'package:obd_app/ui/screens/home/widgets/home_state_widgets.dart';
import 'package:permission_handler/permission_handler.dart';

/// ---------------------------------------------------------------------------
/// Main screen
///
/// Owns the app state (selected tab, telemetry coming from the native
/// background service, UI toggles) and hands it down to the tab widgets.
/// Everything that comes from the API lives in [HomeController]; this widget
/// only holds what is local to the phone.
/// ---------------------------------------------------------------------------

class MainScreen extends StatefulWidget {
  /// Lo que se muestra hasta que `GET /users/me` responde.
  final String nombreUsuario;

  /// Inyectables para tests; la app usa los defaults.
  final HomeController? controller;
  final ReservationRepository? reservationRepository;

  const MainScreen({
    super.key,
    required this.nombreUsuario,
    this.controller,
    this.reservationRepository,
  });

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> implements ObdFlutterApi {
  late final HomeController _home;
  late final bool _ownsHome;
  late final TelemetryUploader _uploader;
  final ObdSession _session = ObdApi.instance.session;

  // Las reservas no tienen endpoint todavía: viven en memoria, por auto.
  ReservationsController? _reservations;
  String? _reservationsCarId;
  String? _reservationsMembersKey;

  int _tab = 0;
  bool _showSummary = false;
  bool _maintenanceAlerts = true;
  bool _tripAlerts = true;
  String _period = '30 d';

  /// Nafta en vivo (BLE o simulador). Null hasta que llegue algo, y entonces
  /// pisa el snapshot del servidor en el dashboard.
  double? _fuel;
  int _speed = 0;
  int _rpm = 0;
  String _connectionStatus = 'Desconectado';

  double? _currentLat;
  double? _currentLng;

  @override
  void initState() {
    super.initState();

    _home = widget.controller ?? HomeController();
    _ownsHome = widget.controller == null;
    _home.addListener(_onHomeChanged);

    _uploader = TelemetryUploader(onUploaded: _onTelemetryUploaded);

    // Si la sesión se cae (logout, o un refresh token vencido que el cliente
    // no pudo renovar) volvemos al login desde un solo lugar.
    _session.addListener(_onSessionChanged);

    ObdFlutterApi.setUp(this);
    _solicitarPermisos();
    _home.load();
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
    _session.removeListener(_onSessionChanged);
    _home.removeListener(_onHomeChanged);
    _uploader.flush();
    _uploader.dispose();
    _reservations?.dispose();
    if (_ownsHome) _home.dispose();
    super.dispose();
  }

  void _onSessionChanged() {
    if (_session.isAuthenticated || !mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (route) => false,
    );
  }

  /// Mantiene el uploader y las reservas apuntando al auto elegido.
  void _onHomeChanged() {
    final car = _home.car;
    _uploader.carId = car?.id;
    _syncReservations();
  }

  void _syncReservations() {
    final car = _home.car;
    final me = _home.me;
    if (car == null || me == null) return;

    final members = ApiMappers.members(
      members: _home.members,
      me: me,
      trips: const [],
    );
    final membersKey = members.map((m) => m.id).join(',');
    if (_reservationsCarId == car.id && _reservationsMembersKey == membersKey) {
      return;
    }

    _reservations?.dispose();
    _reservationsCarId = car.id;
    _reservationsMembersKey = membersKey;
    _reservations = ReservationsController(
      repository:
          widget.reservationRepository ?? InMemoryReservationRepository(),
      carId: car.id,
      currentUserId: me.id,
      members: members,
    );
    if (mounted) setState(() {});
  }

  // acá llega el dato directo desde el background service nativo
  @override
  void onTelemetryUpdated(TelemetryEvent event) {
    if (!mounted) return;

    setState(() {
      _speed = event.speed ?? 0;
      _rpm = event.rpm ?? 0;
      if (event.fuel != null) _fuel = event.fuel!.toDouble();

      // Capturamos el GPS en vivo que viene desde el hardware
      if (event.lat != null && event.lng != null) {
        _currentLat = event.lat;
        _currentLng = event.lng;
      }

      if (_speed > 0 || _rpm > 0) {
        _connectionStatus = 'Conectado';
      }
    });

    // El teléfono es el relay: lo que llega por BLE se sube a la API.
    _uploader.add(event);
  }

  void _onTelemetryUploaded(TelemetryIngestResult result) {
    if (!mounted) return;
    if (result.snapshotUpdated) _home.refreshCar();
    if (mounted) {
      setState(() => _connectionStatus = 'Conectado · sincronizado');
    }
  }

  /// -------------------------------------------------------------------------
  /// Navigation / pages
  /// -------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _home,
      builder: (context, _) {
        return switch (_home.status) {
          HomeStatus.loading => const _LoadingScaffold(),
          HomeStatus.failed => _ErrorScaffold(
            error: _home.loadError,
            onRetry: _home.load,
            onLogout: () => ObdApi.instance.auth.logout(),
          ),
          HomeStatus.ready => _buildHome(),
        };
      },
    );
  }

  Widget _buildHome() {
    final pages = [
      CarTab(
        controller: _home,
        reservations: _reservations,
        initials: _initials,
        liveFuel: _fuel,
        speed: _speed,
        rpm: _rpm,
        connectionStatus: _connectionStatus,
        onFuelChanged: (value) => setState(() => _fuel = value),
        onNavigateToShared: () => setState(() => _tab = 1),
        onNavigateToActivity: () => setState(() => _tab = 2),
        onNavigateToProfile: () => setState(() => _tab = 3),
      ),
      SharedTab(controller: _home, reservations: _reservations),
      ActivityTab(
        controller: _home,
        showSummary: _showSummary,
        period: _period,
        currentLat: _currentLat, // INYECTAMOS LA LATITUD
        currentLng: _currentLng, // INYECTAMOS LA LONGITUD
        onShowSummaryChanged: (value) => setState(() => _showSummary = value),
        onPeriodChanged: (value) => setState(() => _period = value),
        onNavigateToCar: () => setState(() => _tab = 0),
      ),
      ProfileTab(
        controller: _home,
        nombreUsuario: widget.nombreUsuario,
        initials: _initials,
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
        destinations: [
          const NavigationDestination(
            icon: Icon(AppIcons.car),
            selectedIcon: Icon(AppIcons.car),
            label: 'Auto',
          ),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: _home.invitations.isNotEmpty,
              label: Text('${_home.invitations.length}'),
              child: const Icon(AppIcons.shared),
            ),
            selectedIcon: const Icon(AppIcons.shared),
            label: 'Compartido',
          ),
          const NavigationDestination(
            icon: Icon(AppIcons.activity),
            selectedIcon: Icon(AppIcons.activity),
            label: 'Actividad',
          ),
          const NavigationDestination(
            icon: Icon(AppIcons.profile),
            selectedIcon: Icon(AppIcons.profile),
            label: 'Perfil',
          ),
        ],
      ),
    );
  }

  String get _initials {
    final me = _home.me;
    if (me != null) return me.initials;

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

class _LoadingScaffold extends StatelessWidget {
  const _LoadingScaffold();

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 68,
              height: 68,
              decoration: BoxDecoration(
                color: t.accentSubtle,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Icon(AppIcons.car, size: 36, color: t.accent),
            ),
            const SizedBox(height: 22),
            const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(height: 14),
            Text('Cargando tus autos…', style: TextStyle(color: t.muted)),
          ],
        ),
      ),
    );
  }
}

class _ErrorScaffold extends StatelessWidget {
  final Object? error;
  final VoidCallback onRetry;
  final VoidCallback onLogout;

  const _ErrorScaffold({
    required this.error,
    required this.onRetry,
    required this.onLogout,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                EmptyStateCard(
                  icon: Icons.cloud_off_rounded,
                  title: 'No pudimos cargar tus datos',
                  message: error == null
                      ? 'Probá de nuevo.'
                      : ApiMessages.of(error!),
                  actionLabel: 'Reintentar',
                  actionIcon: AppIcons.refresh,
                  onAction: onRetry,
                  secondaryLabel: 'Cerrar sesión',
                  secondaryIcon: AppIcons.logout,
                  onSecondary: onLogout,
                ),
                const SizedBox(height: 12),
                Text(
                  ObdApi.instance.config.toString(),
                  style: TextStyle(fontSize: 10, color: t.muted),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
