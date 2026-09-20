import 'package:flutter/material.dart';

import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/ui/screens/home/main_screen.dart';

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
        home: const MainScreen(nombreUsuario: 'sixseven'),
      ),
    );
  }
}
