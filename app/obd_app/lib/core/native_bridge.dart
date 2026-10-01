import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class NativeBleBridge {
  // sintonizamos exactamente la misma frecuencia que pusimos en Kotlin
  static const platform = MethodChannel('com.example.obd_app/ble_channel');

  // función estática que podremos llamar desde cualquier botón
  // es para iniciar la vinculación BLE desde Flutter por primera vez
  static Future<void> iniciarVinculacion() async {
    try {
      // Le mandamos el mensaje "iniciarVinculacion" a Android
      final String result = await platform.invokeMethod('iniciarVinculacion');
      debugPrint('Respuesta nativa: $result');
    } on PlatformException catch (e) {
      debugPrint('Fallo al llamar al código nativo: ${e.message}');
    }
  }

  /// Le entrega al código nativo lo que necesita para subir datos con la app cerrada.
  ///
  /// TEMPORAL (Fase 1): [accessToken] es el JWT de 15 minutos de la sesión. El nativo
  /// nunca lo renueva; si vence, guarda los datos y los sube cuando se le mande uno nuevo.
  /// En la Fase 2 se reemplaza por el token de dispositivo.
  static Future<void> enviarSesion({
    required String baseUrl,
    required String? accessToken,
    required String? carId,
    required String? userId,
  }) async {
    try {
      await platform.invokeMethod('setSession', {
        'baseUrl': baseUrl,
        'accessToken': accessToken,
        'carId': carId,
        'userId': userId,
      });
    } on PlatformException catch (e) {
      debugPrint('No se pudo entregar la sesión al nativo: ${e.message}');
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
    }
  }

  /// Logout: el nativo deja de subir. Lo ya guardado localmente no se borra.
  static Future<void> borrarSesion() async {
    try {
      await platform.invokeMethod('clearSession');
    } on PlatformException catch (e) {
      debugPrint('No se pudo borrar la sesión nativa: ${e.message}');
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
    }
  }
}
