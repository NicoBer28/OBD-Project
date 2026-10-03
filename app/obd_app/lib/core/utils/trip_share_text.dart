import 'package:obd_app/core/utils/trip_format.dart';

/// El texto de "cobro" que se muestra como QR o se copia para mandar por
/// WhatsApp, SMS o lo que sea, cuando quien debe su parte del viaje no tiene
/// la app.
///
/// A propósito es **texto plano**, no un link `obdc://` como el de
/// `QrPayload`: ese esquema propio solo abre algo si quien escanea ya tiene
/// la app instalada, que es exactamente el caso que este texto existe para
/// cubrir. Cualquier lector de QR de cualquier teléfono puede mostrar texto
/// plano; la persona paga por el medio que prefiera con el dato de cuánto y
/// a quién, y no es la app la que mueve la plata — acá no hay ninguna
/// pasarela de pago integrada.
abstract final class TripShareText {
  static String build({
    required String driverName,
    required String tripLabel,
    required int amount,
    String? payTo,
  }) {
    final buffer = StringBuffer()
      ..writeln('$driverName te pasó tu parte de $tripLabel')
      ..writeln('Monto: ${TripFormat.money(amount)}');

    final trimmed = payTo?.trim();
    if (trimmed != null && trimmed.isNotEmpty) {
      buffer.writeln('Transferir a: $trimmed');
    }

    buffer.write('— OBD-C');
    return buffer.toString();
  }
}
