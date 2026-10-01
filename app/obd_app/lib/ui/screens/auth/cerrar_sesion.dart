import 'package:flutter/material.dart';
import 'package:obd_app/data/device_link_service.dart';

/// Cierra la sesión desde cualquier pantalla: revoca la sesión en el servidor
/// (`POST /api/v1/auth/logout`) y desvincula los ESP32 de este celular. La vuelta al login
/// la hace `MainScreen`, que escucha la sesión.
///
/// Si hay registros de viajes guardados sin subir, avisa que se van a perder y pide
/// confirmación. Con [confirmar] pregunta siempre, aunque no haya nada pendiente.
Future<void> confirmarYCerrarSesion(BuildContext context, {bool confirmar = false}) async {
  final pendientes = await DeviceLinkService.instance.pendientesDeSubir();

  if (pendientes > 0 || confirmar) {
    if (!context.mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cerrar sesión'),
        content: Text(
          pendientes > 0
              ? 'Hay $pendientes registros de viajes sin subir. Si cerrás sesión se van a perder.'
              : 'Vas a tener que ingresar de nuevo con tu contraseña.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(pendientes > 0 ? 'Cancelar' : 'Quedarme'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(pendientes > 0 ? 'Cerrar sesión igual' : 'Cerrar sesión'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
  }

  await DeviceLinkService.instance.cerrarSesion(descartarPendientes: pendientes > 0);
}
