import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:obd_app/core/theme/app_theme.dart';

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
  final _nombreController = TextEditingController(); // Para userName
  final _apellidoController = TextEditingController(); // Para userLastName
  final _emailController = TextEditingController(); // Para userEMail
  final _passwordController = TextEditingController(); // Para userPassword
  final _telefonoController =
      TextEditingController(); // Para userPhone (Opcional)

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

  // le ponemos async a la funcion para que no se congele la app mientras que se complete la comunicacion
  void _registrarse() async {
    // Si la validación local es correcta (ningún validator devuelve error).
    if (_formKey.currentState!.validate()) {
      // Extraemos los valores exactos que vamos a mandar por HTTP después
      // el trim le saca los espacios de mas al principio y al final
      final userName = _nombreController.text.trim();
      final userLastName = _apellidoController.text.trim();
      final userEMail = _emailController.text.trim();
      final userPassword = _passwordController.text;
      final userPhone = _telefonoController.text.trim();

      final navigator = Navigator.of(context);
      final scaffoldMessenger = ScaffoldMessenger.of(context);

      // Armamos la URL
      final url = Uri.parse('http://192.168.1.19:8080/api/v1/auth/register');

      try {
        // 2. Disparamos la petición HTTP POST
        // el await para todo el codigo hasta que java responda
        final response = await http.post(
          url,
          headers: {
            'Content-Type': 'application/json',
          }, // Le avisamos a Java que le mandamos un JSON
          body: jsonEncode({
            'userName': userName,
            'userLastName': userLastName,
            'userEMail': userEMail,
            'userPassword': userPassword,
            // Solo mandamos el teléfono si el usuario escribió algo
            if (userPhone.isNotEmpty) 'userPhone': userPhone,
          }),
        );

        // 3. Analizamos qué nos respondió la API
        if (response.statusCode == 200) {
          // El código 200 significa "Todo OK" en la web
          scaffoldMessenger.showSnackBar(
            const SnackBar(
              content: Text('¡Cuenta creada con éxito!'),
              backgroundColor: Colors.green,
            ),
          );
          // Como venimos desde la pantalla de Login usando Navigator.push,
          // navigator.pop() cierra esta pantalla y nos devuelve al Login.
          navigator.pop();
        } else if (response.statusCode == 409) {
          // Si devuelve 409 Email ya existe
          scaffoldMessenger.showSnackBar(
            const SnackBar(
              content: Text('Ese correo ya está registrado'),
              backgroundColor: Colors.red,
            ),
          );
        } else {
          // error de validacion de api
          scaffoldMessenger.showSnackBar(
            const SnackBar(
              content: Text('Revisá los datos ingresados'),
              backgroundColor: Colors.orange,
            ),
          );
        }
      } catch (error) {
        // Esto salta si la Mac está apagada o el celu no está en el mismo Wi-Fi
        scaffoldMessenger.showSnackBar(
          const SnackBar(
            content: Text('No se pudo conectar con el servidor.'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

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
                  decoration: const InputDecoration(
                    labelText: 'Nombre',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.person),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, ingresá tu nombre';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Campo: Apellido (userLastName)
                TextFormField(
                  controller: _apellidoController,
                  decoration: const InputDecoration(
                    labelText: 'Apellido',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.badge),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, ingresá tu apellido';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Campo: Correo Electrónico (userEMail)
                TextFormField(
                  controller: _emailController,
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

                // Botón de Registro
                SizedBox(
                  width: double.infinity, // Ocupa todo el ancho disponible
                  height: 52,
                  child: ElevatedButton(
                    onPressed: _registrarse,
                    child: const Text(
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
