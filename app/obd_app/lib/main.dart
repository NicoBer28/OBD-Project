import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import 'screens/bluetooth_scanner_screen.dart';

const _uartServiceUuid = '6E400001-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartWriteUuid = '6E400002-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartReadUuid = '6E400003-B5A3-F393-E0A9-E50E24DCCA9E';

void main() {
  runApp(const OBDCApp());
}

// 1. EL NÚCLEO DE LA APP (Configuración global)
class OBDCApp extends StatelessWidget {
  const OBDCApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'OBD-C App',
      debugShowCheckedModeBanner: false,
      // Tema global: colores oscuros para entorno automotriz
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blueAccent,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const LoginScreen(),
    );
  }
}

// 2. PANTALLA DE LOGIN (Manejo de estado de texto y validaciones)
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  // Clave global para validar el formulario completo
  final _formKey = GlobalKey<FormState>();

  // Controladores para leer lo que escribe el usuario
  final _userController = TextEditingController();
  final _passwordController = TextEditingController();

  // Buena práctica: Limpiar la memoria cuando la pantalla se cierra
  @override
  void dispose() {
    _userController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _ingresar() {
    // Si la validación básica es correcta
    if (_formKey.currentState!.validate()) {
      final nombreUsuario = _userController.text.trim();
      final navigator = Navigator.of(context);

      // Primero buscamos el dispositivo OBD antes de mostrar el panel.
      navigator.pushReplacement(
        MaterialPageRoute(
          builder: (context) => BluetoothScannerScreen(
            nombreUsuario: nombreUsuario,
            onConnected: (device) {
              navigator.pushReplacement(
                MaterialPageRoute(
                  builder: (context) =>
                      MainScreen(nombreUsuario: nombreUsuario, device: device),
                ),
              );
            },
            onContinueWithoutConnection: () {
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
                      'INGRESAR',
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

// 3. PANTALLA PRINCIPAL (Manejo de estado interactivo)
class MainScreen extends StatefulWidget {
  final String nombreUsuario;
  final BluetoothDevice? device;

  const MainScreen({super.key, required this.nombreUsuario, this.device});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  // Estado inicial de la nafta
  double _nivelNafta = 75.0;
  BluetoothCharacteristic? _writeCharacteristic;
  BluetoothCharacteristic? _readCharacteristic;
  BluetoothCharacteristic? _notifyCharacteristic;
  StreamSubscription<List<int>>? _receiveSubscription;
  StreamSubscription<BluetoothConnectionState>? _connectionSubscription;
  String _connectionStatus = 'Modo demo: sin conexión BLE';
  String _receivedData = 'Sin datos recibidos';
  bool _isSending = false;
  final _sendController = TextEditingController();

  @override
  void initState() {
    super.initState();
    final device = widget.device;
    if (device != null) {
      _connectionSubscription = device.connectionState.listen((state) {
        if (!mounted) return;
        setState(() {
          _connectionStatus = state == BluetoothConnectionState.connected
              ? 'Conectado'
              : 'Desconectado';
        });
      });
      _prepareBleConnection();
    }
  }

  Future<void> _prepareBleConnection() async {
    final device = widget.device!;
    try {
      if (!device.isConnected) {
        await device.connect(license: License.nonprofit, autoConnect: false);
      }
      final services = await device.discoverServices();
      _writeCharacteristic = null;
      _readCharacteristic = null;
      _notifyCharacteristic = null;
      final uartService = services.cast<BluetoothService?>().firstWhere(
        (service) => service!.uuid == Guid(_uartServiceUuid),
        orElse: () => null,
      );

      if (uartService == null) {
        throw StateError('No se encontró el servicio UART de la ESP32');
      }

      for (final characteristic in uartService.characteristics) {
        if (characteristic.uuid == Guid(_uartWriteUuid)) {
          _writeCharacteristic = characteristic;
        } else if (characteristic.uuid == Guid(_uartReadUuid)) {
          _readCharacteristic = characteristic;
          _notifyCharacteristic = characteristic;
        }
      }

      final receiveCharacteristic = _notifyCharacteristic;
      if (receiveCharacteristic != null &&
          (receiveCharacteristic.properties.notify ||
              receiveCharacteristic.properties.indicate)) {
        await receiveCharacteristic.setNotifyValue(true);
        _receiveSubscription = receiveCharacteristic.onValueReceived.listen((
          value,
        ) {
          if (!mounted) return;
          setState(() {
            _receivedData = utf8.decode(value, allowMalformed: true);
          });
        });
      }

      if (!mounted) return;
      setState(() {
        _connectionStatus = _writeCharacteristic == null
            ? 'Conectado, pero no hay característica escribible'
            : 'Conectado a ${device.advName.isEmpty ? device.remoteId : device.advName}';
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _connectionStatus = 'Error preparando BLE: $error');
    }
  }

  Future<void> _sendData() async {
    final text = _sendController.text;
    final device = widget.device;
    if (device == null || text.trim().isEmpty || _isSending) return;

    setState(() => _isSending = true);
    try {
      if (!device.isConnected) {
        await _prepareBleConnection();
      }
      final activeCharacteristic = _writeCharacteristic;
      if (activeCharacteristic == null || !device.isConnected) {
        throw StateError('La ESP32 no está conectada o no acepta escritura');
      }
      await activeCharacteristic.write(
        utf8.encode(text),
        withoutResponse:
            activeCharacteristic.properties.writeWithoutResponse &&
            !activeCharacteristic.properties.write,
      );
      _sendController.clear();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('No se pudo enviar: $error')));
    } finally {
      if (mounted) setState(() => _isSending = false);
    }
  }

  Future<void> _readData() async {
    final characteristic = _readCharacteristic;
    if (characteristic == null || !characteristic.properties.read) return;

    try {
      final value = await characteristic.read();
      if (!mounted) return;
      setState(() {
        _receivedData = utf8.decode(value, allowMalformed: true);
      });
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('No se pudo leer: $error')));
    }
  }

  @override
  void dispose() {
    _receiveSubscription?.cancel();
    _connectionSubscription?.cancel();
    _sendController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Panel de Control'),
        // Botón para cerrar sesión
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            onPressed: () {
              Navigator.pushReplacement(
                context,
                MaterialPageRoute(builder: (context) => const LoginScreen()),
              );
            },
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Saludo personalizado
            Text(
              'Hola, ${widget.nombreUsuario} 👋',
              style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 40),

            // Indicador central de Nafta
            Center(
              child: Column(
                children: [
                  const Icon(
                    Icons.local_gas_station,
                    size: 60,
                    color: Colors.green,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    '${_nivelNafta.toInt()}%',
                    style: const TextStyle(
                      fontSize: 72,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const Text(
                    'Nivel de Nafta actual',
                    style: TextStyle(fontSize: 16, color: Colors.grey),
                  ),
                ],
              ),
            ),

            const Spacer(),

            // Simulador de datos modificable
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white10,
                borderRadius: BorderRadius.circular(16),
              ),
              child: Column(
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      _connectionStatus,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Recibido: $_receivedData'),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _sendController,
                          enabled: _writeCharacteristic != null,
                          decoration: const InputDecoration(
                            labelText: 'Dato para enviar',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                          onSubmitted: (_) => _sendData(),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Enviar dato',
                        onPressed: _writeCharacteristic == null
                            ? null
                            : _sendData,
                        icon: const Icon(Icons.send),
                      ),
                      IconButton(
                        tooltip: 'Leer dato de la ESP32',
                        onPressed: _readCharacteristic?.properties.read == true
                            ? _readData
                            : null,
                        icon: const Icon(Icons.download),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Simulador de nafta'),
                  ),
                  Slider(
                    value: _nivelNafta,
                    min: 0,
                    max: 100,
                    divisions: 100,
                    activeColor: _nivelNafta < 20
                        ? Colors.red
                        : Colors.blueAccent,
                    onChanged: (nuevoValor) {
                      // El setState obliga a Flutter a redibujar el widget con el nuevo valor
                      setState(() {
                        _nivelNafta = nuevoValor;
                      });
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
