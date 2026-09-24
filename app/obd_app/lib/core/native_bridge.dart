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
      print('Respuesta nativa: $result');
    } on PlatformException catch (e) {
      print('Fallo al llamar al código nativo: ${e.message}');
    }
  }
}