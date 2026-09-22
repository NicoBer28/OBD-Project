import 'package:flutter/material.dart';

import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/ui/screens/auth/login_screen.dart';

void main() {
  // Punto de entrada de Dart. Flutter comienza construyendo OBDCApp.
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
        // La app arranca en el login. Antes entraba directo a MainScreen con
        // un usuario de prueba; ahora MainScreen se construye recién cuando
        // `POST /api/v1/auth/login` (o `/register`) devolvió una sesión.
        home: const LoginScreen(),
      ),
    );
  }
}
