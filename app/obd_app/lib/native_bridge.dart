import 'package:flutter/services.dart';

class NativeBleBridge {
  // 1. Sintonizamos exactamente la misma frecuencia que pusimos en Kotlin
  static const platform = MethodChannel('com.example.obd_app/ble_channel');

  // 2. Función estática que podremos llamar desde cualquier botón
  static Future<void> iniciarVinculacion() async {
    try {
      // Le mandamos el mensaje "iniciarVinculacion" a Android
      final String result = await platform.invokeMethod('iniciarVinculacion');
      print('Respuesta nativa: $result');
    } on PlatformException catch (e) {
      print('Fallo al llamar al código nativo: ${e.message}');
    }
  }
}