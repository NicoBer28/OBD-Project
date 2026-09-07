import 'package:flutter/material.dart';

import './register_screen.dart';
import './bluetooth_scanner_screen.dart';
import './main_screen.dart';

// Login: valida las credenciales localmente y conserva el nombre de usuario.
// Actualmente no existe una autenticación contra un servidor.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  // Permite ejecutar todos los validators del Form en una sola operación.
  final _formKey = GlobalKey<FormState>();

  // Los controllers permiten leer el contenido de los campos de texto.
  final _userController = TextEditingController();
  final _passwordController = TextEditingController();

  // Los controllers deben liberarse cuando el State deja de existir.
  @override
  void dispose() {
    _userController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _ingresar() {
    // Si la validación local es correcta, se inicia el flujo BLE.
    if (_formKey.currentState!.validate()) {
      final nombreUsuario = _userController.text.trim();
      final navigator = Navigator.of(context);

      // Primero se busca el dispositivo OBD antes de mostrar el panel.
      navigator.pushReplacement(
        MaterialPageRoute(
          builder: (context) => BluetoothScannerScreen(
            nombreUsuario: nombreUsuario,
            onConnected: (device) {
              // Reemplazar la ruta evita volver al login con el botón Atrás.
              navigator.pushReplacement(
                MaterialPageRoute(
                  builder: (context) =>
                      MainScreen(nombreUsuario: nombreUsuario, device: device),
                ),
              );
            },
            onContinueWithoutConnection: () {
              // Este camino conserva el simulador para pruebas sin hardware.
              navigator.pushReplacement(
                MaterialPageRoute(
                  builder: (context) =>
                      MainScreen(nombreUsuario: nombreUsuario),
                ),
              );
            },
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(32.0),
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(
                  Icons.directions_car,
                  size: 80,
                  color: Colors.blueAccent,
                ),
                const SizedBox(height: 32),
                const Text(
                  'Bienvenido a OBD-C',
                  style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 32),

                // Input de Usuario
                TextFormField(
                  controller: _userController,
                  decoration: const InputDecoration(
                    labelText: 'Usuario',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.person),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, ingresá un nombre';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Input de Contraseña
                TextFormField(
                  controller: _passwordController,
                  obscureText: true, // Oculta los caracteres
                  decoration: const InputDecoration(
                    labelText: 'Contraseña',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.lock),
                  ),
                  validator: (value) {
                    if (value == null || value.length < 4) {
                      return 'La contraseña debe tener al menos 4 caracteres';
                    }
                    return null;
                  },
                ),
                
                const SizedBox(height: 32),
                // Botón de Ingreso
                SizedBox(
                  width: double.infinity,
                  height: 50,
                  child: ElevatedButton(
                    onPressed: _ingresar,
                    child: const Text(
                      'Ingresar',
                      style: TextStyle(fontSize: 18),
                    ),
                  ),
                ),

                const SizedBox(height: 16),
                // Botón para ir a Registro
                TextButton(
                  onPressed: () {
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