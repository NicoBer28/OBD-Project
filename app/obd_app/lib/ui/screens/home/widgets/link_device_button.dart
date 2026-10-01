import 'package:flutter/material.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/data/device_link_service.dart';

/// Botón para vincular el ESP32 de [car]. Muestra en qué está ese auto: sin vincular,
/// vinculando (cargando) o ya vinculado (gris).
///
/// "Vinculado" es que este celular tiene un ESP32 guardado para ese auto, no que el auto
/// esté encendido ahora.
class LinkDeviceButton extends StatelessWidget {
  final Car? car;

  const LinkDeviceButton({super.key, required this.car});

  Future<void> _vincular(BuildContext context, Car car) async {
    final messenger = ScaffoldMessenger.of(context);
    final vinculacion = await DeviceLinkService.instance.vincular(carId: car.id, carName: car.name);

    final mensaje = switch (vinculacion.resultado) {
      ResultadoVinculacion.cancelado => null,
      ResultadoVinculacion.error => 'No se pudo vincular. Probá de nuevo.',
      ResultadoVinculacion.vinculado =>
        vinculacion.reemplazoCarId == null
            ? 'ESP32 vinculado a ${car.name}'
            : 'Este ESP32 estaba vinculado a otro auto; ahora quedó en ${car.name}',
    };
    if (mensaje != null) messenger.showSnackBar(SnackBar(content: Text(mensaje)));
  }

  Future<void> _desvincular(BuildContext context, Car car) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Desvincular ESP32'),
        content: Text('Este celular dejará de registrar los viajes de ${car.name}.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Desvincular'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await DeviceLinkService.instance.desvincular(car.id);
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final links = DeviceLinkService.instance;
    final azul = Colors.blue.shade700;

    return ListenableBuilder(
      listenable: links,
      builder: (context, _) {
        final car = this.car;
        final vinculando = car != null && links.estaVinculando(car.id);
        final vinculado = car != null && !vinculando && links.estaVinculado(car.id);

        final (Widget icono, String texto) = switch ((car, vinculando, vinculado)) {
          (null, _, _) => (const Icon(Icons.bluetooth_disabled), 'Elegí un auto primero'),
          (_, true, _) => (
            const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
            ),
            'Vinculando…',
          ),
          (_, _, true) => (const Icon(Icons.bluetooth_connected), 'ESP32 vinculado'),
          _ => (const Icon(Icons.bluetooth_searching), 'Vincular ESP32'),
        };

        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                icon: icono,
                label: Text(texto),
                style: ElevatedButton.styleFrom(
                  backgroundColor: azul,
                  foregroundColor: Colors.white,
                  // Deshabilitado: azul atenuado mientras vincula, gris en los demás casos.
                  disabledBackgroundColor: vinculando ? azul.withValues(alpha: .6) : t.surface2,
                  disabledForegroundColor: vinculando ? Colors.white : t.muted,
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
                onPressed: car == null || vinculando || vinculado ? null : () => _vincular(context, car),
              ),
            ),
            if (car != null && vinculado)
              TextButton(
                onPressed: () => _desvincular(context, car),
                style: TextButton.styleFrom(
                  foregroundColor: t.muted,
                  textStyle: const TextStyle(fontSize: 12),
                  visualDensity: VisualDensity.compact,
                ),
                child: const Text('Desvincular'),
              ),
          ],
        );
      },
    );
  }
}
