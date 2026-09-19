import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../src/generated/obd_api.g.dart';
import '../ui/app_theme.dart';
import './login_screen.dart';
import '../native_bridge.dart';




/// ---------------------------------------------------------------------------
/// UI / domain data
/// ---------------------------------------------------------------------------

class CarData {
  final String name;
  final String brand;
  final String plate;
  final double fuelPercent;
  final double fuelCapacityLiters;
  final double kmPerLiter;
  final double batteryVoltage;
  final String service;
  final String parkingAddress;
  final String parkingMeta;
  final String parkedBy;
  final ScheduleSlot? nextTurn;

  const CarData({
    required this.name,
    required this.brand,
    required this.plate,
    required this.fuelPercent,
    required this.fuelCapacityLiters,
    required this.kmPerLiter,
    required this.batteryVoltage,
    required this.service,
    required this.parkingAddress,
    required this.parkingMeta,
    required this.parkedBy,
    this.nextTurn,
  });

  int get autonomyKm =>
      (fuelPercent / 100 * fuelCapacityLiters * kmPerLiter).round();

  double get fuelLiters => fuelPercent / 100 * fuelCapacityLiters;
}

class MemberData {
  final String initials;
  final String name;
  final Color color;
  final double fuelShare;
  final String fuelAmount;

  const MemberData({
    required this.initials,
    required this.name,
    required this.color,
    required this.fuelShare,
    required this.fuelAmount,
  });
}

class FuelSummaryData {
  final String periodLabel;
  final double liters;
  final String total;
  final List<MemberData> members;

  const FuelSummaryData({
    required this.periodLabel,
    required this.liters,
    required this.total,
    required this.members,
  });
}

class ScheduleSlot {
  final String day;
  final String time;
  final String person;
  final Color color;
  final bool available;

  const ScheduleSlot({
    required this.day,
    required this.time,
    required this.person,
    required this.color,
    this.available = false,
  });
}

class TripData {
  final String day;
  final String route;
  final String distance;
  final String duration;
  final List<MemberData> drivers;

  const TripData({
    required this.day,
    required this.route,
    required this.distance,
    required this.duration,
    required this.drivers,
  });
}

class ActivitySummaryData {
  final String distance;
  final String drivingTime;
  final String fuel;
  final String mostVisited;
  final List<double> consumption;
  final List<MemberDistance> driverDistances;

  const ActivitySummaryData({
    required this.distance,
    required this.drivingTime,
    required this.fuel,
    required this.mostVisited,
    required this.consumption,
    required this.driverDistances,
  });
}

class MemberDistance {
  final MemberData member;
  final String distance;

  const MemberDistance(this.member, this.distance);
}

/// ---------------------------------------------------------------------------
/// Demo data
///
/// Keeping this separate from the UI makes it easy to replace later with
/// API/database/OBD data without changing the widgets.
/// ---------------------------------------------------------------------------

class DemoData {
  static List<MemberData> get members => [
    const MemberData(
      initials: 'LM',
      name: 'Vos',
      color: AppPalette.member1,
      fuelShare: .42,
      fuelAmount: r'$35.364',
    ),
    const MemberData(
      initials: 'SM',
      name: 'Sofía',
      color: AppPalette.member2,
      fuelShare: .31,
      fuelAmount: r'$26.102',
    ),
    const MemberData(
      initials: 'MG',
      name: 'Martín',
      color: AppPalette.member3,
      fuelShare: .18,
      fuelAmount: r'$15.156',
    ),
    const MemberData(
      initials: 'PA',
      name: 'Papá',
      color: AppPalette.member4,
      fuelShare: .09,
      fuelAmount: r'$7.578',
    ),
  ];

  static const car = CarData(
    name: 'Golf GTI',
    brand: 'Volkswagen',
    plate: 'AB 123 CD',
    fuelPercent: 88,
    fuelCapacityLiters: 52,
    kmPerLiter: 9,
    batteryVoltage: 12.4,
    service: '2.100 km',
    parkingAddress: 'Av. Corrientes 1234',
    parkingMeta: 'a 600 m tuyo',
    parkedBy: 'Sofía',
    nextTurn: ScheduleSlot(
      day: 'HOY',
      time: '18:00 – 21:00',
      person: 'Vos',
      color: AppPalette.member1,
    ),
  );

  static FuelSummaryData get fuel => FuelSummaryData(
    periodLabel: 'septiembre',
    liters: 142,
    total: r'$84.200',
    members: members,
  );

  static const schedule = [
    ScheduleSlot(
      day: 'HOY',
      time: '18–21',
      person: 'Vos',
      color: AppPalette.member1,
    ),
    ScheduleSlot(
      day: 'SÁB',
      time: '09–14',
      person: 'Sofía · Pilar',
      color: AppPalette.member2,
    ),
    ScheduleSlot(
      day: 'DOM',
      time: 'todo',
      person: 'Martín',
      color: AppPalette.member3,
    ),
    ScheduleSlot(
      day: 'LUN',
      time: '—',
      person: 'Libre',
      color: AppPalette.surface2,
      available: true,
    ),
  ];

  static List<TripData> get trips {
    final people = members;

    return [
      TripData(
        day: 'Hoy',
        route: 'Casa → Trabajo',
        distance: '12,4 km',
        duration: '18 min',
        drivers: [people[0]],
      ),
      TripData(
        day: 'Ayer',
        route: 'Ruta costera',
        distance: '42,8 km',
        duration: '51 min',
        drivers: [people[1], people[0]],
      ),
      TripData(
        day: 'Dom, 31 ago',
        route: 'Centro → Norte',
        distance: '8,6 km',
        duration: '16 min',
        drivers: [people[2]],
      ),
      TripData(
        day: 'Vie, 29 ago',
        route: 'Belgrano → Palermo',
        distance: '15,7 km',
        duration: '26 min',
        drivers: [people[0], people[1]],
      ),
      TripData(
        day: 'Jue, 28 ago',
        route: 'Trabajo → Gimnasio',
        distance: '6,2 km',
        duration: '14 min',
        drivers: [people[1]],
      ),
      TripData(
        day: 'Mié, 27 ago',
        route: 'Centro → Tigre',
        distance: '31,4 km',
        duration: '43 min',
        drivers: [people[2], people[3]],
      ),
    ];
  }

  static ActivitySummaryData get activity {
    final people = members;

    return ActivitySummaryData(
      distance: '1.284',
      drivingTime: '38 h 20 min',
      fuel: '142',
      mostVisited: 'Centro',
      consumption: [38, 52, 31, 64, 47, 80, 36, 27, 57, 69, 44, 100, 61, 49],
      driverDistances: [
        MemberDistance(people[0], '539 km'),
        MemberDistance(people[1], '398 km'),
        MemberDistance(people[2], '231 km'),
        MemberDistance(people[3], '116 km'),
      ],
    );
  }
}

/// ---------------------------------------------------------------------------
/// Reusable icon vocabulary
/// ---------------------------------------------------------------------------

abstract final class AppIcons {
  static const car = Icons.directions_car_rounded;
  static const shared = Icons.people_alt_rounded;
  static const activity = Icons.insights_rounded;
  static const profile = Icons.person_rounded;
  static const notification = Icons.notifications_none_rounded;
  static const location = Icons.location_on_outlined;
  static const calendar = Icons.calendar_month_rounded;
  static const fuel = Icons.local_gas_station_rounded;
  static const battery = Icons.battery_5_bar_rounded;
  static const service = Icons.build_circle_outlined;
  static const bluetooth = Icons.bluetooth_rounded;
  static const trip = Icons.route_rounded;
  static const clock = Icons.schedule_rounded;
  static const link = Icons.link_rounded;
  static const qr = Icons.qr_code_2_rounded;
  static const invite = Icons.person_add_alt_1_rounded;
  static const send = Icons.send_rounded;
  static const refresh = Icons.refresh_rounded;
  static const settings = Icons.tune_rounded;
  static const logout = Icons.logout_rounded;
  static const arrow = Icons.chevron_right_rounded;
}

/// ---------------------------------------------------------------------------
/// Main screen
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

class _MainScreenState extends State<MainScreen> implements ObdFlutterApi{
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
      if (_speed > 0 || _rpm > 0) {
         _connectionStatus = 'Conectado';
      }
    });
  }

  /// -------------------------------------------------------------------------
  /// Actions
  /// -------------------------------------------------------------------------

  void _showMessage(String message) {
    if (!mounted) return;

    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  void _settleUp() {
    _showMessage('Pago registrado. Actualizaremos el saldo del grupo.');
  }

  void _inviteMember() {
    showDialog<void>(
      context: context,
      builder: (dialogContext) {
        final t = dialogContext.tokens;

        return AlertDialog(
          title: const Text('Invitar al grupo'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(AppIcons.qr, size: 128, color: t.text),
              const SizedBox(height: 16),
              const Text(
                'Escaneá este código para unirte al auto, '
                'o enviá una invitación directa.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 20),
              TextField(
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                  labelText: 'Correo electrónico',
                  prefixIcon: Icon(Icons.email_outlined),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () {
                        Navigator.pop(dialogContext);
                        _showMessage('Enlace copiado al portapapeles');
                      },
                      icon: const Icon(AppIcons.link),
                      label: const Text('Copiar enlace'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    tooltip: 'Enviar invitación',
                    onPressed: () {
                      Navigator.pop(dialogContext);
                      _showMessage('Invitación enviada');
                    },
                    icon: const Icon(AppIcons.send),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  void _showStartJourneySheet() {
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
              const _LocationField(
                label: 'Punto de partida',
                hint: 'Ubicación actual',
                icon: Icons.my_location_rounded,
              ),
              const SizedBox(height: 12),
              const _LocationField(
                label: 'Destino',
                hint: 'Ej. Av. Corrientes 980',
                icon: Icons.flag_outlined,
              ),
              const SizedBox(height: 18),
              _ActionTile(
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

  /// -------------------------------------------------------------------------
  /// Navigation / pages
  /// -------------------------------------------------------------------------


  @override
  Widget build(BuildContext context) {
    final pages = [
      _buildCarPage(),
      _buildSharedPage(),
      _buildActivityPage(),
      _buildProfilePage(),
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

  Widget _page({
    required Key key,
    required String title,
    String? subtitle,
    Widget? trailing,
    required List<Widget> children,
  }) {
    return ListView(
      key: key,
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
      children: [
        PageHeader(title: title, subtitle: subtitle, trailing: trailing),
        const SizedBox(height: 18),
        ...children,
      ],
    );
  }

  /// -------------------------------------------------------------------------
  /// Auto
  /// -------------------------------------------------------------------------

  Widget _buildCarPage() {
    return _page(
      key: const ValueKey('car'),
      title: _carData.name,
      subtitle: '${_carData.brand} · ${_carData.plate}',
      trailing: _ProfileAvatar(
        initials: _initials,
        onTap: () => setState(() => _tab = 3),
      ),
      children: [
        _CarHero(
          fuelPercent: _fuel,
          fuelCapacityLiters: _carData.fuelCapacityLiters,
          autonomyKm:
              (_fuel / 100 * _carData.fuelCapacityLiters * _carData.kmPerLiter)
                  .round(),
          fuelLiters: _fuel / 100 * _carData.fuelCapacityLiters,
        ),
        const SizedBox(height: 10),
        _VitalGrid(
          items: [
            VitalData(
              label: 'Batería',
              value: '${_carData.batteryVoltage.toStringAsFixed(1)} V',
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
              value: _carData.service,
              icon: AppIcons.service,
              valueColor: AppColors.warning,
            ),
          ],
        ),
        const SizedBox(height: 10),
        _ParkingCard(car: _carData),
        if (_carData.nextTurn != null) ...[
          const SizedBox(height: 10),
          _NextTurnCard(
            slot: _carData.nextTurn!,
            onTap: () => setState(() => _tab = 1),
          ),
        ],
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: _showStartJourneySheet,
          icon: const Icon(Icons.play_arrow_rounded),
          label: const Text('Iniciar viaje'),
        ),
        const SizedBox(height: 16),
        _TelemetryCard(
          speed: _speed,
          rpm: _rpm,
          fuel: _fuel,
          status: _connectionStatus,
          onFuelChanged: (value) => setState(() => _fuel = value),
        ),
      ],
    );
  }

  /// -------------------------------------------------------------------------
  /// Shared
  /// -------------------------------------------------------------------------

  Widget _buildSharedPage() {
    return _page(
      key: const ValueKey('shared'),
      title: 'Compartido',
      subtitle: 'Familia · ${_members.length} miembros',
      trailing: IconButton(
        tooltip: 'Invitar miembro',
        onPressed: _inviteMember,
        icon: const Icon(AppIcons.invite),
      ),
      children: [
        _MemberHeader(members: _members, onInvite: _inviteMember),
        const SizedBox(height: 16),
        _FuelSummaryCard(data: _fuelData),
        const SizedBox(height: 10),
        _BalanceCard(onSettle: _settleUp),
        const SizedBox(height: 10),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _SectionHeader(
                title: 'Esta semana',
                actionLabel: 'Reservar',
                actionIcon: AppIcons.calendar,
                onAction: () {},
              ),
              const SizedBox(height: 4),
              ..._schedule.map(
                (slot) => Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: _ScheduleSlotView(slot: slot),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// -------------------------------------------------------------------------
  /// Activity
  /// -------------------------------------------------------------------------

  Widget _buildActivityPage() {
    return _page(
      key: const ValueKey('activity'),
      title: 'Actividad',
      subtitle: '${_carData.name} · últimos 30 días',
      children: [
        _SegmentedControl(
          labels: const ['Viajes', 'Resumen'],
          selectedIndex: _showSummary ? 1 : 0,
          onChanged: (index) {
            setState(() => _showSummary = index == 1);
          },
        ),
        if (_showSummary) ...[
          const SizedBox(height: 12),
          _PeriodPicker(
            value: _period,
            onChanged: (value) {
              setState(() => _period = value);
            },
          ),
          const SizedBox(height: 16),
          _SummaryContent(data: _activityData),
        ] else ...[
          const SizedBox(height: 18),
          _TripsContent(trips: _trips),
        ],
      ],
    );
  }

  /// -------------------------------------------------------------------------
  /// Profile
  /// -------------------------------------------------------------------------

  Widget _buildProfilePage() {
    return _page(
      key: const ValueKey('profile'),
      title: 'Perfil',
      subtitle: widget.nombreUsuario,
      children: [
        SectionCard(
          child: Row(
            children: [
              _ProfileAvatar(initials: _initials, radius: 27),
              const SizedBox(width: 12),
              Expanded(
                child: _TextStack(
                  title: widget.nombreUsuario,
                  subtitle: 'Plan personal',
                ),
              ),
              const Icon(AppIcons.arrow),
            ],
          ),
        ),
        const SizedBox(height: 18),
        const SectionLabel('Vehículo y dispositivo'),
        const SizedBox(height: 8),
        SectionCard(
          padding: EdgeInsets.zero,
          child: Column(
            children: [
              _SettingTile(
                icon: AppIcons.bluetooth,
                title: 'Conexión OBD',
                subtitle: _connectionStatus,
                onTap: () async {
                  await NativeBleBridge.iniciarVinculacion();
                },
              ),
              const Divider(height: 1),
              _SettingTile(
                icon: AppIcons.car,
                title: 'Vehículo',
                subtitle: '${_carData.brand} ${_carData.name}',
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        const SectionLabel('Apariencia'),
        const SizedBox(height: 8),
        ValueListenableBuilder<ThemeMode>(
          valueListenable: themeModeNotifier,
          builder: (_, mode, __) {
            return SectionCard(
              padding: EdgeInsets.zero,
              child: SwitchListTile(
                value: mode == ThemeMode.dark,
                onChanged: (enabled) {
                  themeModeNotifier.value = enabled
                      ? ThemeMode.dark
                      : ThemeMode.light;
                },
                secondary: const Icon(Icons.dark_mode_outlined),
                title: const Text('Modo oscuro'),
                subtitle: const Text('Usar la interfaz oscura'),
              ),
            );
          },
        ),
        const SizedBox(height: 18),
        const SectionLabel('Alertas'),
        const SizedBox(height: 8),
        SectionCard(
          padding: EdgeInsets.zero,
          child: Column(
            children: [
              _SwitchTile(
                title: 'Mantenimiento',
                subtitle: 'Service y fallas del vehículo',
                value: _maintenanceAlerts,
                onChanged: (value) {
                  setState(() => _maintenanceAlerts = value);
                },
              ),
              const Divider(height: 1),
              _SwitchTile(
                title: 'Viajes',
                subtitle: 'Inicio y fin de cada recorrido',
                value: _tripAlerts,
                onChanged: (value) {
                  setState(() => _tripAlerts = value);
                },
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),
        Builder(
          builder: (context) {
            final t = context.tokens;

            return OutlinedButton.icon(
              onPressed: () => Navigator.pushReplacement(
                context,
                MaterialPageRoute(builder: (_) => const LoginScreen()),
              ),
              icon: const Icon(AppIcons.logout),
              label: const Text('Cerrar sesión'),
              style: OutlinedButton.styleFrom(foregroundColor: t.danger),
            );
          },
        ),
      ],
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

/// ---------------------------------------------------------------------------
/// Reusable cards / widgets
/// ---------------------------------------------------------------------------

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

class _CarHero extends StatelessWidget {
  final double fuelPercent;
  final double fuelCapacityLiters;
  final int autonomyKm;
  final double fuelLiters;

  const _CarHero({
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
                child: _TextStack(
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
              _TextStack(
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

class _VitalGrid extends StatelessWidget {
  final List<VitalData> items;

  const _VitalGrid({required this.items});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (var i = 0; i < items.length; i++) ...[
          if (i > 0) const SizedBox(width: 8),
          Expanded(child: _VitalCard(data: items[i])),
        ],
      ],
    );
  }
}

class _VitalCard extends StatelessWidget {
  final VitalData data;

  const _VitalCard({required this.data});

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

class _ParkingCard extends StatelessWidget {
  final CarData car;

  const _ParkingCard({required this.car});

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
            child: _TextStack(
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

class _NextTurnCard extends StatelessWidget {
  final ScheduleSlot slot;
  final VoidCallback onTap;

  const _NextTurnCard({required this.slot, required this.onTap});

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
                child: _TextStack(
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

class _MemberHeader extends StatelessWidget {
  final List<MemberData> members;
  final VoidCallback onInvite;

  const _MemberHeader({required this.members, required this.onInvite});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _AvatarStack(members: members)),
        OutlinedButton.icon(
          onPressed: onInvite,
          icon: const Icon(AppIcons.invite, size: 16),
          label: const Text('Invitar'),
        ),
      ],
    );
  }
}

class _AvatarStack extends StatelessWidget {
  final List<MemberData> members; // 1. Change the type here

  const _AvatarStack({super.key, required this.members});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 24.0 + (members.length - 1) * 16.0,
      height: 24.0,
      child: Stack(
        children: [
          for (int i = 0; i < members.length; i++)
            Positioned(
              left: i * 16.0,
              child: Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color:
                      members[i].color, // 2. Access the color from MemberData
                  border: Border.all(color: context.tokens.surface, width: 2),
                ),
                alignment: Alignment.center,
                child: Text(
                  members[i].initials, // 3. Access the initials from MemberData
                  style: const TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  final String initials;
  final Color color;
  final double size;

  const _Avatar({required this.initials, required this.color, this.size = 34});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        border: Border.all(color: t.surface, width: size >= 30 ? 2 : 1.5),
      ),
      child: Text(
        initials,
        style: TextStyle(
          color: Colors.white,
          fontSize: size * .30,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class _FuelSummaryCard extends StatelessWidget {
  final FuelSummaryData data;

  const _FuelSummaryCard({required this.data});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'NAFTA · ${data.periodLabel.toUpperCase()}',
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  letterSpacing: .7,
                  color: t.muted,
                ),
              ),
              Text(
                '${data.liters.toStringAsFixed(0)} L',
                style: TextStyle(fontSize: 11, color: t.muted),
              ),
            ],
          ),
          const SizedBox(height: 7),
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                data.total,
                style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -1,
                  color: t.text,
                ),
              ),
              const SizedBox(width: 7),
              Text(
                'total del grupo',
                style: TextStyle(fontSize: 11, color: t.muted),
              ),
            ],
          ),
          const SizedBox(height: 11),
          ClipRRect(
            borderRadius: BorderRadius.circular(99),
            child: SizedBox(
              height: 9,
              child: Row(
                children: [
                  for (final member in data.members)
                    if (member.fuelShare > 0) // Guard against flex: 0
                      Expanded(
                        flex: (member.fuelShare * 100).round(),
                        child: ColoredBox(color: member.color),
                      ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 13),
          for (final member in data.members)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  _Avatar(
                    initials: member.initials,
                    color: member.color,
                    size: 27,
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      member.name,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: t.text,
                      ),
                    ),
                  ),
                  Text(
                    '${(member.fuelShare * 100).round()}%',
                    style: TextStyle(fontSize: 11, color: t.muted),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    member.fuelAmount,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      color: t.text,
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _BalanceCard extends StatelessWidget {
  final VoidCallback onSettle;

  const _BalanceCard({required this.onSettle});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      padding: const EdgeInsets.all(15),
      child: Row(
        children: [
          Expanded(
            child: _TextStack(
              title: 'Tu saldo pendiente',
              subtitle: 'Debés \$12.400',
              titleStyle: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: t.muted,
              ),
              subtitleStyle: TextStyle(
                fontSize: 21,
                fontWeight: FontWeight.w800,
                color: t.accent2,
              ),
              spacing: 4,
            ),
          ),
          FilledButton(onPressed: onSettle, child: const Text('Saldar')),
        ],
      ),
    );
  }
}

class _ScheduleSlotView extends StatelessWidget {
  final ScheduleSlot slot;

  const _ScheduleSlotView({required this.slot});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final textColor = slot.available ? t.muted : Colors.white;

    return Row(
      children: [
        SizedBox(
          width: 52,
          child: Text(
            '${slot.day}\n${slot.time}',
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w800,
              color: t.muted,
              height: 1.25,
            ),
          ),
        ),
        Expanded(
          child: Container(
            height: 31,
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: slot.color,
              borderRadius: BorderRadius.circular(9),
            ),
            child: Text(
              slot.person,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w800,
                color: textColor,
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// ---------------------------------------------------------------------------
/// Activity widgets
/// ---------------------------------------------------------------------------

class _SegmentedControl extends StatelessWidget {
  final List<String> labels;
  final int selectedIndex;
  final ValueChanged<int> onChanged;

  const _SegmentedControl({
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

class _PeriodPicker extends StatelessWidget {
  final String value;
  final ValueChanged<String> onChanged;

  const _PeriodPicker({required this.value, required this.onChanged});

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

class _TripsContent extends StatelessWidget {
  final List<TripData> trips;

  const _TripsContent({required this.trips});

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
          child: Column(
            children: [
              for (var i = 0; i < trips.length; i++) ...[
                _TripRow(trip: trips[i]),
                if (i < trips.length - 1) const Divider(height: 18),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _TripRow extends StatelessWidget {
  final TripData trip;

  const _TripRow({required this.trip});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Row(
      children: [
        SizedBox(
          width: 73,
          child: Text(trip.day, style: TextStyle(fontSize: 11, color: t.muted)),
        ),
        _AvatarStack(members: trip.drivers),
        const SizedBox(width: 6),
        Expanded(
          child: _TextStack(
            title: trip.route,
            subtitle: trip.duration,
            titleStyle: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: t.text,
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
      ],
    );
  }
}

class _SummaryContent extends StatelessWidget {
  final ActivitySummaryData data;

  const _SummaryContent({required this.data});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: _StatCard(
                icon: AppIcons.trip,
                label: 'Distancia',
                value: data.distance,
                unit: 'km',
                delta: '↑ 12% vs. agosto',
                deltaColor: t.success,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: _StatCard(
                icon: AppIcons.clock,
                label: 'Al volante',
                value: data.drivingTime,
                unit: '',
                delta: '↑ 8%',
                deltaColor: t.success,
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: _StatCard(
                icon: AppIcons.fuel,
                label: 'Nafta',
                value: data.fuel,
                unit: 'L · 11,1 L/100',
                delta: '↓ 6% de consumo',
                deltaColor: t.success,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: _StatCard(
                icon: AppIcons.location,
                label: 'Más visitado',
                value: data.mostVisited,
                unit: '',
                delta: '8 viajes · 96 km',
                deltaColor: t.muted,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _ConsumptionChart(values: data.consumption),
        const SizedBox(height: 12),
        _DriverBreakdown(data: data.driverDistances),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final String unit;
  final String delta;
  final Color deltaColor;

  const _StatCard({
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

class _ConsumptionChart extends StatelessWidget {
  final List<double> values;

  const _ConsumptionChart({required this.values});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final maxValue = values.isEmpty
        ? 1.0
        : values.reduce((a, b) => a > b ? a : b);

    // Prevent 0.0 / 0.0 NaN exceptions
    final safeDivisor = maxValue > 0 ? maxValue : 1.0;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _SectionHeader(
            title: 'Consumo por día',
            trailingText: 'pico: sáb 14',
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
                      padding: const EdgeInsets.symmetric(horizontal: 2),
                      child: FractionallySizedBox(
                        heightFactor:
                            value / safeDivisor, // Use the safe divisor here
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
              Text('1 sep', style: TextStyle(fontSize: 10, color: t.muted)),
              Text('15 sep', style: TextStyle(fontSize: 10, color: t.muted)),
              Text('30 sep', style: TextStyle(fontSize: 10, color: t.muted)),
            ],
          ),
        ],
      ),
    );
  }
}

class _DriverBreakdown extends StatelessWidget {
  final List<MemberDistance> data;

  const _DriverBreakdown({required this.data});

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
                    values: data
                        .map((item) => _parseKm(item.distance))
                        .toList(),
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
                      _LegendRow(
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
    final normalized = value
        .replaceAll('.', '')
        .replaceAll('km', '')
        .replaceAll(',', '.')
        .trim();

    return double.tryParse(normalized) ?? 0;
  }
}

/// ---------------------------------------------------------------------------
/// BLE card
/// ---------------------------------------------------------------------------

class _TelemetryCard extends StatelessWidget {
  final int speed;
  final int rpm;
  final double fuel;
  final String status;
  final ValueChanged<double> onFuelChanged;

  const _TelemetryCard({
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
              _MetricPill(label: 'Velocidad', value: '$speed km/h'),
              const SizedBox(width: 8),
              _MetricPill(label: 'RPM', value: '$rpm'),
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

class _MetricPill extends StatelessWidget {
  final String label;
  final String value;

  const _MetricPill({required this.label, required this.value});

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

/// ---------------------------------------------------------------------------
/// Generic reusable building blocks
/// ---------------------------------------------------------------------------

class SectionCard extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;

  const SectionCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(14),
    this.radius = 16,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Container(
      decoration: BoxDecoration(
        color: t.surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: t.border),
      ),
      padding: padding,
      child: child,
    );
  }
}

class IconBadge extends StatelessWidget {
  final IconData icon;
  final Color color;

  const IconBadge({super.key, required this.icon, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 38,
      height: 38,
      decoration: BoxDecoration(
        color: color.withValues(alpha: .10),
        shape: BoxShape.circle,
      ),
      child: Icon(icon, size: 19, color: color),
    );
  }
}

class _TextStack extends StatelessWidget {
  final String title;
  final String? subtitle;
  final TextStyle? titleStyle;
  final TextStyle? subtitleStyle;
  final CrossAxisAlignment crossAxisAlignment;
  final double spacing;

  const _TextStack({
    required this.title,
    this.subtitle,
    this.titleStyle,
    this.subtitleStyle,
    this.crossAxisAlignment = CrossAxisAlignment.start,
    this.spacing = 3,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Column(
      crossAxisAlignment: crossAxisAlignment,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style:
              titleStyle ??
              TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: t.text,
              ),
        ),
        if (subtitle != null) ...[
          SizedBox(height: spacing),
          Text(
            subtitle!,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: subtitleStyle ?? TextStyle(fontSize: 11, color: t.muted),
          ),
        ],
      ],
    );
  }
}

class SectionLabel extends StatelessWidget {
  final String text;

  const SectionLabel(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Text(
      text.toUpperCase(),
      style: TextStyle(
        fontSize: 10,
        fontWeight: FontWeight.w800,
        letterSpacing: .8,
        color: t.muted,
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  final String? actionLabel;
  final IconData? actionIcon;
  final VoidCallback? onAction;
  final String? trailingText;

  const _SectionHeader({
    required this.title,
    this.actionLabel,
    this.actionIcon,
    this.onAction,
    this.trailingText,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w800,
              color: t.text,
            ),
          ),
        ),
        if (trailingText != null)
          Text(trailingText!, style: TextStyle(fontSize: 10, color: t.muted)),
        if (actionLabel != null)
          TextButton.icon(
            onPressed: onAction,
            icon: Icon(actionIcon, size: 15),
            label: Text(actionLabel!),
            style: TextButton.styleFrom(
              padding: EdgeInsets.zero,
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
          ),
      ],
    );
  }
}

class _LegendRow extends StatelessWidget {
  final Color color;
  final String name;
  final String value;

  const _LegendRow({
    required this.color,
    required this.name,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Row(
      children: [
        Container(
          width: 8,
          height: 8,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 7),
        Expanded(
          child: Text(
            name,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: t.text,
            ),
          ),
        ),
        Text(
          value,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w800,
            color: t.text,
          ),
        ),
      ],
    );
  }
}

class _SettingTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  const _SettingTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return ListTile(
      leading: Icon(icon, color: t.muted),
      title: Text(
        title,
        style: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w700,
          color: t.text,
        ),
      ),
      subtitle: Text(
        subtitle,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11, color: t.muted),
      ),
      onTap: onTap,
      trailing: Icon(AppIcons.arrow, color: t.muted),
    );
  }
}

class _SwitchTile extends StatelessWidget {
  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  const _SwitchTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      value: value,
      onChanged: onChanged,
      title: Text(title),
      subtitle: Text(subtitle),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 3),
    );
  }
}

class _ProfileAvatar extends StatelessWidget {
  final String initials;
  final VoidCallback? onTap;
  final double radius;

  const _ProfileAvatar({required this.initials, this.onTap, this.radius = 20});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final avatar = CircleAvatar(
      radius: radius,
      backgroundColor: t.accent,
      child: Text(
        initials,
        style: TextStyle(
          color: Colors.white,
          fontSize: radius * .55,
          fontWeight: FontWeight.w800,
        ),
      ),
    );

    return onTap == null
        ? avatar
        : Semantics(
            button: true,
            label: 'Abrir perfil',
            child: GestureDetector(onTap: onTap, child: avatar),
          );
  }
}

class _LocationField extends StatelessWidget {
  final String label;
  final String hint;
  final IconData icon;

  const _LocationField({
    required this.label,
    required this.hint,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return TextField(
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        prefixIcon: Icon(icon, color: t.accent),
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  const _ActionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Material(
      color: t.accent.withValues(alpha: .08),
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.all(13),
          child: Row(
            children: [
              IconBadge(icon: icon, color: t.accent),
              const SizedBox(width: 11),
              Expanded(
                child: _TextStack(title: title, subtitle: subtitle),
              ),
              Icon(AppIcons.arrow, color: t.muted),
            ],
          ),
        ),
      ),
    );
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
