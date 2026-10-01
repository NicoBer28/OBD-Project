import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/device_link_service.dart';
import 'package:obd_app/ui/screens/auth/cerrar_sesion.dart';
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

  /// Vincula el ESP32 del auto elegido (lo mismo que el botón de la pestaña Auto).
  Future<void> _vincular(BuildContext context, String carId, String carName) async {
    final messenger = ScaffoldMessenger.of(context);
    final vinculacion = await DeviceLinkService.instance.vincular(carId: carId, carName: carName);

    final mensaje = switch (vinculacion.resultado) {
      ResultadoVinculacion.cancelado => null,
      ResultadoVinculacion.error => 'No se pudo vincular. Probá de nuevo.',
      ResultadoVinculacion.vinculado =>
        vinculacion.reemplazoCarId == null
            ? 'ESP32 vinculado a $carName'
            : 'Este ESP32 estaba vinculado a otro auto; ahora quedó en $carName',
    };
    if (mensaje != null) messenger.showSnackBar(SnackBar(content: Text(mensaje)));
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final me = controller.me;
        final car = controller.car;
        final group = controller.group;
        final nombre = me?.fullName.isNotEmpty == true ? me!.fullName : nombreUsuario;

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
                  // El ESP32 del auto elegido: cada auto tiene el suyo.
                  ListenableBuilder(
                    listenable: DeviceLinkService.instance,
                    builder: (context, _) {
                      final links = DeviceLinkService.instance;
                      final vinculando = car != null && links.estaVinculando(car.id);
                      final asociacion = car == null ? null : links.asociacionDe(car.id);

                      return SettingTile(
                        icon: AppIcons.bluetooth,
                        title: 'Conexión OBD',
                        subtitle: vinculando
                            ? 'Vinculando…'
                            : asociacion != null
                            ? 'Vinculado · ${asociacion.mac}'
                            : connectionStatus,
                        onTap: car == null || vinculando || asociacion != null
                            ? null
                            : () => _vincular(context, car.id, car.name),
                      );
                    },
                  ),
                  const Divider(height: 1),
                  SettingTile(
                    icon: AppIcons.car,
                    title: controller.cars.length > 1 ? 'Mis autos (${controller.cars.length})' : 'Vehículo',
                    subtitle: car == null ? 'Todavía no cargaste ninguno' : '${car.model.label} · ${car.name}',
                    onTap: () => MyCarsScreen.show(context, controller),
                  ),
                  const Divider(height: 1),
                  SettingTile(
                    icon: AppIcons.dongle,
                    title: 'Dongle OBD',
                    subtitle: controller.device == null ? 'Sin vincular a este auto' : controller.device!.serial,
                    onTap: car == null ? null : () => CarActionsSheet.show(context, controller, car),
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
                      themeModeNotifier.value = enabled ? ThemeMode.dark : ThemeMode.light;
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
              onPressed: () => confirmarYCerrarSesion(context, confirmar: true),
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
