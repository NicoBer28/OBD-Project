import 'package:flutter/material.dart';

import 'package:obd_app/screens/login_screen.dart';

void main() {
  // Punto de entrada de Dart. Flutter comienza construyendo OBDCApp.
  runApp(const OBDCApp());
}

// Núcleo de la aplicación: configura el tema y la primera pantalla.
class OBDCApp extends StatelessWidget {
  const OBDCApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'OBD-C App',
      debugShowCheckedModeBanner: false,
      // Tema global: colores oscuros para entorno automotriz
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blueAccent,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const LoginScreen(),
    );
  }
}
