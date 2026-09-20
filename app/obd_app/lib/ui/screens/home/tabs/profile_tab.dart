import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/native_bridge.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/auth/login_screen.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Profile
/// ---------------------------------------------------------------------------

class ProfileTab extends StatelessWidget {
  final String nombreUsuario;
  final String initials;
  final CarData carData;
  final String connectionStatus;
  final bool maintenanceAlerts;
  final bool tripAlerts;
  final ValueChanged<bool> onMaintenanceAlertsChanged;
  final ValueChanged<bool> onTripAlertsChanged;

  const ProfileTab({
    super.key,
    required this.nombreUsuario,
    required this.initials,
    required this.carData,
    required this.connectionStatus,
    required this.maintenanceAlerts,
    required this.tripAlerts,
    required this.onMaintenanceAlertsChanged,
    required this.onTripAlertsChanged,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      key: const ValueKey('profile'),
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
      children: [
        PageHeader(title: 'Perfil', subtitle: nombreUsuario),
        const SizedBox(height: 18),
        SectionCard(
          child: Row(
            children: [
              ProfileAvatar(initials: initials, radius: 27),
              const SizedBox(width: 12),
              Expanded(
                child: TextStack(
                  title: nombreUsuario,
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
              SettingTile(
                icon: AppIcons.bluetooth,
                title: 'Conexión OBD',
                subtitle: connectionStatus,
                onTap: () async {
                  await NativeBleBridge.iniciarVinculacion();
                },
              ),
              const Divider(height: 1),
              SettingTile(
                icon: AppIcons.car,
                title: 'Vehículo',
                subtitle: '${carData.brand} ${carData.name}',
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        const SectionLabel('Apariencia'),
        const SizedBox(height: 8),
        ValueListenableBuilder<ThemeMode>(
          valueListenable: themeModeNotifier,
          builder: (_, mode, _) {
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
              SwitchTile(
                title: 'Mantenimiento',
                subtitle: 'Service y fallas del vehículo',
                value: maintenanceAlerts,
                onChanged: onMaintenanceAlertsChanged,
              ),
              const Divider(height: 1),
              SwitchTile(
                title: 'Viajes',
                subtitle: 'Inicio y fin de cada recorrido',
                value: tripAlerts,
                onChanged: onTripAlertsChanged,
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),
        OutlinedButton.icon(
          onPressed: () => Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (_) => const LoginScreen()),
          ),
          icon: const Icon(AppIcons.logout),
          label: const Text('Cerrar sesión'),
          style: OutlinedButton.styleFrom(foregroundColor: context.tokens.danger),
        ),
      ],
    );
  }
}
