import 'dart:async';

import 'package:flutter/material.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/auth/login_screen.dart';
import 'package:obd_app/ui/screens/home/main_screen.dart';

enum _Phase {
  /// Hay un refresh token guardado y lo estamos cambiando por un access token.
  restoring,

  /// El servidor no respondió. El token guardado sigue ahí: se puede reintentar.
  offline,

  /// La sesión se recuperó: directo a la app.
  signedIn,

  /// No había sesión guardada, o el servidor la rechazó: al login.
  signedOut,
}

/// Primera pantalla de la app: decide entre el login y la app misma.
///
/// `main()` ya cargó del keystore el refresh token que dejó la corrida
/// anterior (`ObdSession.restore`). Acá, si hay uno, se cambia por un access
/// token nuevo (`POST /api/v1/auth/refresh`) y se entra directo a
/// [MainScreen]; sin token guardado es el [LoginScreen] de siempre, sin
/// pantalla intermedia ni demora.
///
/// Que el refresh falle puede significar dos cosas muy distintas, y se tratan
/// distinto:
///
/// * **El servidor lo rechazó** (vencido a los 14 días, ya usado, revocado por
///   un logout en otro dispositivo): el cliente borra la sesión y se va al
///   login.
/// * **No se pudo llegar al servidor** (sin señal, servidor caído): el token
///   puede estar perfecto, así que no se toca. Se muestra un aviso con
///   "Reintentar" en lugar de mandar al usuario a escribir la contraseña por
///   un problema de red.
class AuthGate extends StatefulWidget {
  const AuthGate({super.key, this.api});

  /// Solo para tests; en la app se usa la instancia compartida.
  final ObdApi? api;

  @override
  State<AuthGate> createState() => _AuthGateState();
}

class _AuthGateState extends State<AuthGate> {
  late final ObdApi _api = widget.api ?? ObdApi.instance;

  _Phase _phase = _Phase.signedOut;
  String _nombre = '';

  @override
  void initState() {
    super.initState();
    // Sin token guardado no hay nada que esperar: el login aparece en el
    // primer frame.
    if (_api.session.refreshToken != null) {
      _phase = _Phase.restoring;
      _restore();
    }
  }

  Future<void> _restore() async {
    final restored = await _api.client.refreshSession();
    if (!mounted) return;

    if (!restored) {
      // Un rechazo del servidor borra el refresh token de la sesión; un fallo
      // de red no. Es la forma de distinguirlos (ver `refreshSession`).
      setState(() {
        _phase = _api.session.refreshToken != null ? _Phase.offline : _Phase.signedOut;
      });
      return;
    }

    // Igual que en el login: el nombre de pila viene del perfil y, si el
    // perfil falla, alcanza con el correo.
    var nombre = _api.session.email ?? '';
    try {
      final perfil = await _api.users.me();
      if (perfil.userName.isNotEmpty) nombre = perfil.userName;
    } on ObdApiException {
      // El perfil es un lujo, no un requisito.
    }

    if (!mounted) return;
    setState(() {
      _nombre = nombre;
      _phase = _Phase.signedIn;
    });
  }

  void _retry() {
    setState(() => _phase = _Phase.restoring);
    _restore();
  }

  void _useAnotherAccount() {
    _api.session.clear();
    setState(() => _phase = _Phase.signedOut);
  }

  @override
  Widget build(BuildContext context) {
    return switch (_phase) {
      _Phase.signedOut => const LoginScreen(),
      _Phase.signedIn => MainScreen(nombreUsuario: _nombre),
      _Phase.restoring => const _RestoringView(),
      _Phase.offline => _OfflineView(
        onRetry: _retry,
        onUseAnotherAccount: _useAnotherAccount,
      ),
    };
  }
}

/// Logo + spinner mientras se recupera la sesión.
class _RestoringView extends StatefulWidget {
  const _RestoringView();

  @override
  State<_RestoringView> createState() => _RestoringViewState();
}

class _RestoringViewState extends State<_RestoringView> {
  // Pasados unos segundos avisamos que el servidor puede estar despertando,
  // igual que hace el login.
  bool _lento = false;
  Timer? _avisoLento;

  @override
  void initState() {
    super.initState();
    _avisoLento = Timer(const Duration(seconds: 5), () {
      if (mounted) setState(() => _lento = true);
    });
  }

  @override
  void dispose() {
    _avisoLento?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const _Logo(),
              const SizedBox(height: 28),
              const CircularProgressIndicator(),
              const SizedBox(height: 20),
              const Text('Retomando tu sesión…'),
              if (_lento) ...[
                const SizedBox(height: 6),
                const Text(
                  'El servidor puede estar despertando, un momento.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.muted),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Aviso cuando no hubo forma de llegar al servidor.
class _OfflineView extends StatelessWidget {
  const _OfflineView({required this.onRetry, required this.onUseAnotherAccount});

  final VoidCallback onRetry;
  final VoidCallback onUseAnotherAccount;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const _Logo(),
              const SizedBox(height: 24),
              const Text(
                'No pudimos conectar con el servidor',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              const Text(
                'Tu sesión sigue guardada. Revisá tu conexión y probá de nuevo.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppColors.muted),
              ),
              const SizedBox(height: 28),
              SizedBox(
                width: double.infinity,
                height: 52,
                child: ElevatedButton(
                  onPressed: onRetry,
                  child: const Text('REINTENTAR'),
                ),
              ),
              const SizedBox(height: 8),
              TextButton(
                onPressed: onUseAnotherAccount,
                child: const Text('Ingresar con otra cuenta'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Logo extends StatelessWidget {
  const _Logo();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 68,
      height: 68,
      decoration: BoxDecoration(
        color: AppColors.accentSubtle,
        borderRadius: BorderRadius.circular(20),
      ),
      child: const Icon(
        Icons.directions_car_outlined,
        size: 36,
        color: AppColors.accent,
      ),
    );
  }
}
