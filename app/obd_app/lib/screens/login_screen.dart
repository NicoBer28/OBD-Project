import 'package:flutter/material.dart';

import './register_screen.dart';
import './main_screen.dart';

import 'dart:convert';
import 'package:http/http.dart' as http;

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

  // void _ingresar() async {
  //   // Si la validación local es correcta, se inicia el flujo BLE.
  //   if (_formKey.currentState!.validate()) {
  //     final userEmail = _emailController.text.trim();
  //     final userPassword = _passwordController.text;
      
  //     final navigator = Navigator.of(context);
  //     final scaffoldMessenger = ScaffoldMessenger.of(context);

  //     final url = Uri.parse('http://192.168.1.19:8080/api/v1/auth/login');

  //     try {
  //       // Disparamos la petición a la API
  //       final response = await http.post(
  //         url,
  //         headers: {'Content-Type': 'application/json'},
  //         body: jsonEncode({
  //           'userMail': userEmail,
  //           'userPassword': userPassword,
  //         }),
  //       );

  //       // Si las credenciales coinciden en la base de datos, el 200 es que salio todo bien
  //       if (response.statusCode == 200) {
  //         print('Login exitoso! Tokens: ${response.body}');

  //         // Primero se busca el dispositivo OBD antes de mostrar el panel.
  //         navigator.pushReplacement(
  //           MaterialPageRoute(
  //             builder: (context) => MainScreen(
  //               nombreUsuario: userEmail
  //             ),
  //           ),
  //         );
  //       } else if (response.statusCode == 401) {
  //         // 401 Unauthorized: email o contraseña incorrectos
  //         scaffoldMessenger.showSnackBar(
  //           const SnackBar(
  //             content: Text('Correo o contraseña incorrectos'),
  //             backgroundColor: Colors.red,
  //           ),
  //         );
  //       } else {
  //         // eror del servidor
  //         scaffoldMessenger.showSnackBar(
  //           SnackBar(
  //             content: Text('Error del servidor (${response.statusCode})'),
  //             backgroundColor: Colors.orange,
  //           ),
  //         );
  //       }
  //     } catch (error) {
  //       // problema de wifi o servidor apagado
  //       scaffoldMessenger.showSnackBar(
  //         const SnackBar(
  //           content: Text('No se pudo conectar. Revisá tu conexión Wi-Fi.'),
  //           backgroundColor: Colors.red,
  //         ),
  //       );
  //     }
  //   }
  // }
void _ingresar() {
    // Si la validación local de formato pasa, navegamos directo sin llamar al backend
    if (_formKey.currentState!.validate()) {
      final userEmail = _emailController.text.trim();
      final navigator = Navigator.of(context);

      // Salto directo a la pantalla de escaneo Bluetooth
      navigator.pushReplacement(
        MaterialPageRoute(
          builder: (context) => MainScreen(
            nombreUsuario: userEmail
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
                const SizedBox(height: 32),
                
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