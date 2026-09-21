import 'package:flutter/material.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/home/main_screen.dart';

/// Registro: crea la cuenta contra `POST /api/v1/auth/register`.
///
/// Ese endpoint además deja la sesión abierta (devuelve los mismos tokens que
/// el login), así que al terminar entramos directo a la app en vez de volver
/// al login a pedir la contraseña de nuevo. Si preferís el flujo anterior,
/// reemplazá el `pushAndRemoveUntil` por `navigator.pop()`.
class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  // Permite ejecutar todos los validators del Form en una sola operación.
  final _formKey = GlobalKey<FormState>();

  // Los controllers permiten leer el contenido de los campos de texto.
  final _nombreController = TextEditingController(); // Para userName
  final _apellidoController = TextEditingController(); // Para userLastName
  final _emailController = TextEditingController(); // Para userEmail
  final _passwordController = TextEditingController(); // Para userPassword
  final _telefonoController =
      TextEditingController(); // Para userPhone (Opcional)

  bool _cargando = false;

  // Los controllers deben liberarse cuando el State deja de existir.
  @override
  void dispose() {
    _nombreController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _apellidoController.dispose();
    _telefonoController.dispose();
    super.dispose();
  }

  Future<void> _registrarse() async {
    if (_cargando || !_formKey.currentState!.validate()) return;

    final api = ObdApi.instance;
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    final nombre = _nombreController.text.trim();

    setState(() => _cargando = true);

    try {
      // register crea la cuenta Y la loguea: el cliente guarda los tokens solo.
      await api.auth.register(
        userName: nombre,
        userLastName: _apellidoController.text.trim(),
        userEmail: _emailController.text.trim(),
        userPassword: _passwordController.text,
        userPhone: _telefonoController.text,
      );

      messenger.showSnackBar(
        const SnackBar(
          content: Text('¡Cuenta creada con éxito!'),
          backgroundColor: Colors.green,
        ),
      );

      if (!mounted) return;
      // Sacamos el login de la pila: ya hay sesión abierta.
      navigator.pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => MainScreen(nombreUsuario: nombre)),
        (route) => false,
      );
    } on ApiException catch (error) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(_mensajeDeError(error)),
          backgroundColor: error.isConflict ? Colors.red : Colors.orange,
        ),
      );
    } on NetworkException {
      messenger.showSnackBar(
        const SnackBar(
          content: Text('No se pudo conectar con el servidor.'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      if (mounted) setState(() => _cargando = false);
    }
  }

  /// La API devuelve 409 si el mail ya existe y 400 con un mapa campo →
  /// mensaje si algo no pasó la validación; mostramos el primero de esos.
  String _mensajeDeError(ApiException error) {
    if (error.isConflict) return 'Ese correo ya está registrado';
    if (error.isValidation) {
      if (error.fieldErrors.isNotEmpty) {
        final primero = error.fieldErrors.entries.first;
        return '${_etiquetaDe(primero.key)}: ${primero.value}';
      }
      return 'Revisá los datos ingresados';
    }
    return 'Error del servidor (${error.statusCode})';
  }

  String _etiquetaDe(String campo) => switch (campo) {
    'userName' => 'Nombre',
    'userLastName' => 'Apellido',
    'userEmail' => 'Correo',
    'userPassword' => 'Contraseña',
    'userPhone' => 'Teléfono',
    _ => campo,
  };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Crear Cuenta')),
      body: Center(
        // SingleChildScrollView evita que el teclado tape los botones al escribir.
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
          child: Form(
            key: _formKey, // Conectamos el formulario con nuestra llave maestra
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Icono decorativo al igual que en el login
                Container(
                  width: 62,
                  height: 62,
                  decoration: BoxDecoration(
                    color: AppColors.accentSubtle,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: const Icon(
                    Icons.person_add_alt_1_outlined,
                    size: 32,
                    color: AppColors.accent,
                  ),
                ),
                const SizedBox(height: 18),
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    'Creá tu cuenta',
                    style: TextStyle(
                      fontSize: 25,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.5,
                    ),
                  ),
                ),
                const SizedBox(height: 5),
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    'Empezá a conocer mejor tu auto.',
                    style: TextStyle(color: AppColors.muted),
                  ),
                ),
                const SizedBox(height: 26),

                // Campo: Nombre (userName)
                TextFormField(
                  controller: _nombreController,
                  enabled: !_cargando,
                  decoration: const InputDecoration(
                    labelText: 'Nombre',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.person),
                  ),
                  validator: (value) {
                    if (value == null || value.trim().isEmpty) {
                      return 'Por favor, ingresá tu nombre';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Campo: Apellido (userLastName)
                TextFormField(
                  controller: _apellidoController,
                  enabled: !_cargando,
                  decoration: const InputDecoration(
                    labelText: 'Apellido',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.badge),
                  ),
                  validator: (value) {
                    if (value == null || value.trim().isEmpty) {
                      return 'Por favor, ingresá tu apellido';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Campo: Correo Electrónico (userEmail)
                TextFormField(
                  controller: _emailController,
                  enabled: !_cargando,
                  keyboardType: TextInputType
                      .emailAddress, // Muestra el teclado con el "@"
                  decoration: const InputDecoration(
                    labelText: 'Correo electrónico',
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

                // Campo: Teléfono (userPhone) - Opcional según API
                TextFormField(
                  controller: _telefonoController,
                  enabled: !_cargando,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                    labelText: 'Teléfono (Opcional)',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.phone),
                  ),
                ),
                const SizedBox(height: 16),

                // Campo: Contraseña (userPassword)
                TextFormField(
                  controller: _passwordController,
                  enabled: !_cargando,
                  obscureText: true, // Oculta la contraseña con puntitos
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
                    if (value.length > 72) {
                      return 'La contraseña no puede superar los 72 caracteres';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 32),

                // Botón de Registro
                SizedBox(
                  width: double.infinity, // Ocupa todo el ancho disponible
                  height: 52,
                  child: ElevatedButton(
                    onPressed: _cargando ? null : _registrarse,
                    child: _cargando
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text(
                            'REGISTRARSE',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
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
