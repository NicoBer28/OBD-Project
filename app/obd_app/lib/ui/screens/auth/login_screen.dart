import 'package:flutter/material.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/auth/register_screen.dart';
import 'package:obd_app/ui/screens/home/main_screen.dart';

/// Login: la primera pantalla de la app.
///
/// Valida el formato localmente y después autentica contra la API
/// (`POST /api/v1/auth/login`). Si el login sale bien, el access token y la
/// cookie de refresh quedan guardados en `ObdApi.instance.session` y el resto
/// de las pantallas ya pueden llamar a cualquier endpoint.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  // Permite ejecutar todos los validators del Form en una sola operación.
  final _formKey = GlobalKey<FormState>();

  // Los controllers permiten leer el contenido de los campos de texto.
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();

  // Mientras hay una request en vuelo el botón se bloquea, así un doble tap
  // no dispara dos logins.
  bool _cargando = false;

  // Los controllers deben liberarse cuando el State deja de existir.
  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _ingresar() async {
    if (_cargando || !_formKey.currentState!.validate()) return;

    final api = ObdApi.instance;
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);

    setState(() => _cargando = true);

    try {
      await api.auth.login(
        userEmail: _emailController.text.trim(),
        userPassword: _passwordController.text,
      );

      // Con la sesión abierta pedimos el perfil real en vez de mostrar el
      // correo: `userName` es el nombre de pila que el usuario cargó al
      // registrarse.
      var nombre = api.session.email ?? _emailController.text.trim();
      try {
        final perfil = await api.users.me();
        if (perfil.userName.isNotEmpty) nombre = perfil.userName;
      } on ObdApiException {
        // El perfil es un lujo, no un requisito: si falla seguimos con el mail.
      }

      if (!mounted) return;
      navigator.pushReplacement(
        MaterialPageRoute(builder: (_) => MainScreen(nombreUsuario: nombre)),
      );
    } on ApiException catch (error) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(_mensajeDeError(error)),
          backgroundColor: error.isUnauthorized ? Colors.red : Colors.orange,
        ),
      );
    } on NetworkException {
      messenger.showSnackBar(
        const SnackBar(
          content: Text('No se pudo conectar. Revisá tu conexión Wi-Fi.'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      if (mounted) setState(() => _cargando = false);
    }
  }

  /// Traduce los códigos que devuelve la API a algo legible.
  String _mensajeDeError(ApiException error) {
    if (error.isUnauthorized) return 'Correo o contraseña incorrectos';
    if (error.isValidation) return 'Revisá los datos ingresados';
    if (error.isServerError) {
      return 'El servidor tuvo un problema. Probá de nuevo.';
    }
    return 'Error del servidor (${error.statusCode})';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
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
                ),
                const SizedBox(height: 20),
                const Text(
                  'Bienvenido a OBD-C',
                  style: TextStyle(
                    fontSize: 27,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.5,
                  ),
                ),
                const SizedBox(height: 6),
                const Text(
                  'Ingresá para ver cómo está tu auto.',
                  style: TextStyle(color: AppColors.muted),
                ),
                const SizedBox(height: 28),

                // Campo: Correo (userEmail)
                TextFormField(
                  controller: _emailController,
                  enabled: !_cargando,
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: 'Correo electronico',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.email),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, ingresá un correo';
                    }
                    if (!value.contains('@')) {
                      return 'El formato del correo no es válido';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Campo: Contraseña (userPassword)
                TextFormField(
                  controller: _passwordController,
                  enabled: !_cargando,
                  obscureText: true, // Oculta la contraseña con puntitos
                  onFieldSubmitted: (_) => _ingresar(),
                  decoration: const InputDecoration(
                    labelText: 'Contraseña',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.lock),
                  ),
                  validator: (value) {
                    // La API exige entre 8 y 72 caracteres
                    if (value == null || value.length < 8) {
                      return 'La contraseña debe tener al menos 8 caracteres';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 28),

                // Botón de Ingreso
                SizedBox(
                  width: double.infinity,
                  height: 52,
                  child: ElevatedButton(
                    onPressed: _cargando ? null : _ingresar,
                    child: _cargando
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text(
                            'INGRESAR',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                  ),
                ),

                const SizedBox(height: 16),
                // Botón para ir a Registro
                TextButton(
                  onPressed: _cargando
                      ? null
                      : () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => const RegisterScreen(),
                            ),
                          );
                        },
                  child: const Text(
                    '¿No tenés cuenta? Registrate acá',
                    style: TextStyle(fontSize: 16),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
