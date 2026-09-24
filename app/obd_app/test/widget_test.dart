// Smoke test del arranque de la app.
//
// Antes verificaba el dashboard porque `main.dart` entraba directo a
// MainScreen con un usuario de prueba. Ahora la app arranca en el login, así
// que lo que se verifica es eso: que la primera pantalla sea el formulario de
// ingreso y que no haya datos de ningún auto antes de autenticarse.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:obd_app/main.dart';
import 'package:obd_app/ui/screens/auth/login_screen.dart';

void main() {
  testWidgets('La app arranca en el login', (WidgetTester tester) async {
    await tester.pumpWidget(const OBDCApp());

    expect(find.byType(LoginScreen), findsOneWidget);
    expect(find.text('Bienvenido a OBD-C'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, 'INGRESAR'), findsOneWidget);

    // Nada del auto se muestra antes de iniciar sesión.
    expect(find.text('Golf GTI'), findsNothing);
  });

  testWidgets('Desde el login se puede ir al registro', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const OBDCApp());

    await tester.tap(find.text('¿No tenés cuenta? Registrate acá'));
    await tester.pumpAndSettle();

    expect(find.text('Crear Cuenta'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, 'REGISTRARSE'), findsOneWidget);
  });

  testWidgets('El login valida el formato antes de llamar a la API', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const OBDCApp());

    // Con los campos vacíos no se dispara ninguna request: los validators
    // cortan antes, así que el test no necesita un servidor.
    await tester.tap(find.widgetWithText(ElevatedButton, 'INGRESAR'));
    await tester.pump();

    expect(find.text('Por favor, ingresá un correo'), findsOneWidget);
    expect(
      find.text('La contraseña debe tener al menos 8 caracteres'),
      findsOneWidget,
    );
  });
}
