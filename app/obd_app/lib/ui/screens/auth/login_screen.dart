import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/ui/screens/auth/register_screen.dart';
import 'package:obd_app/ui/screens/home/main_screen.dart';

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
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();

  // Los controllers deben liberarse cuando el State deja de existir.
  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _ingresar() async {
    // Si la validación local de formato pasa, navegamos directo sin llamar al backend
    if (_formKey.currentState!.validate()) {
      final userEmail = _emailController.text.trim();
      final userPassword = _passwordController.text;

      final navigator = Navigator.of(context);
      final scaffoldMessenger = ScaffoldMessenger.of(context);

      final url = Uri.parse('http://192.168.1.19:8080/api/v1/auth/login');

      try {
        // Disparamos la petición a la API
        final response = await http.post(
          url,
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({
            'userEmail': userEmail,
            'userPassword': userPassword,
          }),
        );

        // Si las credenciales coinciden en la base de datos, el 200 es que salio todo bien
        if (response.statusCode == 200) {
          // Primero se busca el dispositivo OBD antes de mostrar el panel.
          navigator.pushReplacement(
            MaterialPageRoute(
              builder: (context) => MainScreen(nombreUsuario: userEmail),
            ),
          );
        } else if (response.statusCode == 401) {
          // 401 Unauthorized: email o contraseña incorrectos
          scaffoldMessenger.showSnackBar(
            const SnackBar(
              content: Text('Correo o contraseña incorrectos'),
              backgroundColor: Colors.red,
            ),
          );
        } else {
          // eror del servidor
          scaffoldMessenger.showSnackBar(
            SnackBar(
              content: Text('Error del servidor (${response.statusCode})'),
              backgroundColor: Colors.orange,
            ),
          );
        }
      } catch (error) {
        // problema de wifi o servidor apagado
        scaffoldMessenger.showSnackBar(
          const SnackBar(
            content: Text('No se pudo conectar. Revisá tu conexión Wi-Fi.'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
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

                // Input de Email
                TextFormField(
                  controller: _emailController,
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
                    return null;
                  },
                ),
                const SizedBox(height: 28),
                // Botón de Ingreso
                SizedBox(
                  width: double.infinity,
                  height: 52,
                  child: ElevatedButton(
                    onPressed: _ingresar,
                    child: const Text(
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
