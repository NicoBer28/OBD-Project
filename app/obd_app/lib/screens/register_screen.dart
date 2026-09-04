import 'package:flutter/material.dart';

// Pantalla de Registro: permite al usuario crear una cuenta validando
// que los campos de nombre, correo y contraseña cumplan los requisitos básicos.
class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  // Permite ejecutar todos los validators del Form en una sola operación.
  final _formKey = GlobalKey<FormState>();

  // Los controllers permiten leer el contenido de los campos de texto.
  final _nombreController = TextEditingController();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();

  // Los controllers deben liberarse cuando el State deja de existir.
  @override
  void dispose() {
    _nombreController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _registrarse() {
    // Si la validación local es correcta (ningún validator devuelve error).
    if (_formKey.currentState!.validate()) {
      final nombre = _nombreController.text.trim();
      final email = _emailController.text.trim();
      
      // Acá a futuro iría la lógica de tu grupo para guardar en base de datos.
      print('¡Registro validado para: $nombre con email $email!');

      // Como venimos desde la pantalla de Login usando Navigator.push, 
      // Navigator.pop(context) simplemente "cierra" esta pantalla y nos devuelve al Login.
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Crear Cuenta'),
      ),
      body: Center(
        // SingleChildScrollView evita que el teclado tape los botones al escribir.
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(32.0),
          child: Form(
            key: _formKey, // Conectamos el formulario con nuestra llave maestra
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Icono decorativo al igual que en el login
                const Icon(
                  Icons.person_add,
                  size: 80,
                  color: Colors.blueAccent,
                ),
                const SizedBox(height: 32),

                // Input de Nombre Completo
                TextFormField(
                  controller: _nombreController,
                  decoration: const InputDecoration(
                    labelText: 'Nombre completo',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.badge),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, ingresá tu nombre';
                    }
                    return null; // Null significa que está todo perfecto
                  },
                ),
                const SizedBox(height: 16),

                // Input de Correo Electrónico
                TextFormField(
                  controller: _emailController,
                  keyboardType: TextInputType.emailAddress, // Muestra el teclado con el "@"
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

                // Input de Contraseña
                TextFormField(
                  controller: _passwordController,
                  obscureText: true, // Oculta la contraseña con puntitos
                  decoration: const InputDecoration(
                    labelText: 'Contraseña',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.lock),
                  ),
                  validator: (value) {
                    if (value == null || value.length < 4) {
                      return 'La contraseña debe tener al menos 6 caracteres';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 32),

                // Botón de Registro
                SizedBox(
                  width: double.infinity, // Ocupa todo el ancho disponible
                  height: 50,
                  child: ElevatedButton(
                    onPressed: _registrarse,
                    child: const Text(
                      'REGISTRARSE',
                      style: TextStyle(fontSize: 18),
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