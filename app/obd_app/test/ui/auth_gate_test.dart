import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/auth/auth_gate.dart';
import 'package:obd_app/ui/screens/auth/login_screen.dart';

/// Qué pantalla elige `AuthGate` al arrancar según lo que haya guardado y lo
/// que conteste el servidor. El camino feliz (refresh OK → MainScreen) no se
/// prueba acá porque MainScreen arrastra BLE y permisos nativos; el transporte
/// de ese refresh ya está cubierto en `session_persistence_test.dart`.

const _config = ApiConfig(baseUrl: 'http://test.local');

/// Una API cuya sesión ya "recuperó" un refresh token del disco.
ObdApi _apiWithSavedToken(MockClient client) {
  final session = ObdSession()..rememberRefreshToken('refresh-1');
  return ObdApi(config: _config, httpClient: client, session: session);
}

void main() {
  testWidgets('sin sesión guardada el login aparece en el primer frame', (tester) async {
    final api = ObdApi(config: _config, httpClient: MockClient((_) async => http.Response('', 500)));

    await tester.pumpWidget(MaterialApp(home: AuthGate(api: api)));

    expect(find.byType(LoginScreen), findsOneWidget);
    expect(find.text('Retomando tu sesión…'), findsNothing);
  });

  testWidgets('con un token guardado espera al refresh antes de decidir', (tester) async {
    final api = _apiWithSavedToken(MockClient((_) async => http.Response('{"status":401}', 401)));

    await tester.pumpWidget(MaterialApp(home: AuthGate(api: api)));

    expect(find.text('Retomando tu sesión…'), findsOneWidget);
    expect(find.byType(LoginScreen), findsNothing);

    await tester.pumpAndSettle();
  });

  testWidgets('si el servidor rechaza el token va al login', (tester) async {
    final api = _apiWithSavedToken(MockClient((_) async => http.Response('{"status":401}', 401)));

    await tester.pumpWidget(MaterialApp(home: AuthGate(api: api)));
    await tester.pumpAndSettle();

    expect(find.byType(LoginScreen), findsOneWidget);
    expect(api.session.refreshToken, isNull);
  });

  testWidgets('si el servidor falla ofrece reintentar y conserva el token', (tester) async {
    final api = _apiWithSavedToken(MockClient((_) async => http.Response('bad gateway', 503)));

    await tester.pumpWidget(MaterialApp(home: AuthGate(api: api)));
    await tester.pumpAndSettle();

    expect(find.text('No pudimos conectar con el servidor'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, 'REINTENTAR'), findsOneWidget);
    expect(find.byType(LoginScreen), findsNothing);
    expect(api.session.refreshToken, 'refresh-1');
  });

  testWidgets('desde el aviso de conexión se puede entrar con otra cuenta', (tester) async {
    final api = _apiWithSavedToken(MockClient((_) async => http.Response('bad gateway', 503)));

    await tester.pumpWidget(MaterialApp(home: AuthGate(api: api)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ingresar con otra cuenta'));
    await tester.pumpAndSettle();

    expect(find.byType(LoginScreen), findsOneWidget);
    expect(api.session.refreshToken, isNull);
  });
}
