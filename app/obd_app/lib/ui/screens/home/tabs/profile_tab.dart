import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/native_bridge.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/cars/my_cars_screen.dart';
import 'package:obd_app/ui/screens/groups/group_sheets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Profile
/// ---------------------------------------------------------------------------

class ProfileTab extends StatelessWidget {
  final HomeController controller;

  /// Nombre a mostrar mientras `GET /users/me` no respondió.
  final String nombreUsuario;
  final String initials;
  final String connectionStatus;
  final bool maintenanceAlerts;
  final bool tripAlerts;
  final ValueChanged<bool> onMaintenanceAlertsChanged;
  final ValueChanged<bool> onTripAlertsChanged;

  const ProfileTab({
    super.key,
    required this.controller,
    required this.nombreUsuario,
    required this.initials,
    required this.connectionStatus,
    required this.maintenanceAlerts,
    required this.tripAlerts,
    required this.onMaintenanceAlertsChanged,
    required this.onTripAlertsChanged,
  });

  /// Revoca la sesión en el servidor (`POST /api/v1/auth/logout`, que invalida
  /// todos los refresh tokens del usuario). La vuelta al login la hace
  /// `MainScreen`, que escucha la sesión: así también se vuelve al login
  /// cuando el refresh token vence solo.
  ///
  /// Si la llamada falla igual se limpia la sesión local: los tokens que
  /// quedaron no le sirven a esta app.
  Future<void> _cerrarSesion(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cerrar sesión'),
        content: const Text(
          'Vas a tener que ingresar de nuevo con tu contraseña.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Quedarme'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Cerrar sesión'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await ObdApi.instance.auth.logout();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final me = controller.me;
        final car = controller.car;
        final group = controller.group;
        final nombre = me?.fullName.isNotEmpty == true
            ? me!.fullName
            : nombreUsuario;

        return ListView(
          key: const ValueKey('profile'),
          padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
          children: [
            PageHeader(title: 'Perfil', subtitle: me?.userEmail ?? nombre),
            const SizedBox(height: 18),
            SectionCard(
              child: Row(
                children: [
                  ProfileAvatar(initials: me?.initials ?? initials, radius: 27),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextStack(
                      title: nombre,
                      subtitle: me?.userPhone?.isNotEmpty == true
                          ? me!.userPhone!
                          : (me?.userEmail ?? 'Cargando perfil…'),
                    ),
                  ),
                  if (me != null)
                    IconButton(
                      tooltip: 'Mi código QR',
                      onPressed: () => MyQrSheet.show(context, me),
                      icon: const Icon(AppIcons.qr),
                    ),
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
                    title: controller.cars.length > 1
                        ? 'Mis autos (${controller.cars.length})'
                        : 'Vehículo',
                    subtitle: car == null
                        ? 'Todavía no cargaste ninguno'
                        : '${car.model.label} · ${car.name}',
                    onTap: () => MyCarsScreen.show(context, controller),
                  ),
                  const Divider(height: 1),
                  SettingTile(
                    icon: AppIcons.dongle,
                    title: 'Dongle OBD',
                    subtitle: controller.device == null
                        ? 'Sin vincular a este auto'
                        : controller.device!.serial,
                    onTap: car == null
                        ? null
                        : () => CarActionsSheet.show(context, controller, car),
                  ),
                  const Divider(height: 1),
                  SettingTile(
                    icon: AppIcons.shared,
                    title: 'Grupo',
                    subtitle: group == null
                        ? 'No estás en ningún grupo'
                        : '${group.name} · ${group.callerIsAdmin ? 'administrás' : 'miembro'}',
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
              onPressed: () => _cerrarSesion(context),
              icon: const Icon(AppIcons.logout),
              label: const Text('Cerrar sesión'),
              style: OutlinedButton.styleFrom(
                foregroundColor: context.tokens.danger,
              ),
            ),
          ],
        );
      },
    );
  }
}
