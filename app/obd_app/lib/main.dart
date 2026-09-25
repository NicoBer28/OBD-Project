import 'package:flutter/material.dart';

import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/auth/auth_gate.dart';

Future<void> main() async {
  // Punto de entrada de Dart. Hace falta antes de tocar el keystore, que se
  // habla con la plataforma por un canal.
  WidgetsFlutterBinding.ensureInitialized();

  // Recupera el refresh token que dejó la corrida anterior. Es una lectura
  // local (milisegundos), no una llamada de red: la red la hace AuthGate ya
  // con la app en pantalla.
  await ObdApi.instance.session.restore();

  // Flutter comienza construyendo OBDCApp.
  runApp(const OBDCApp());
}

// Núcleo de la aplicación: configura el tema y la primera pantalla.
class OBDCApp extends StatelessWidget {
  const OBDCApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: themeModeNotifier,
      builder: (_, mode, _) => MaterialApp(
        title: 'OBD-C App',
        debugShowCheckedModeBanner: false,
        theme: buildAppTheme(),
        darkTheme: buildAppTheme(Brightness.dark),
        themeMode: mode,
        // AuthGate decide la primera pantalla: si quedó una sesión guardada la
        // recupera y entra directo a MainScreen; si no, muestra el login.
        // MainScreen se construye recién cuando hay una sesión válida, sea
        // por `POST /api/v1/auth/refresh` o por login / registro.
        home: const AuthGate(),
      ),
    );
  }
}
