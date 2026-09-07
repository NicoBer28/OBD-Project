import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import './login_screen.dart';

// UUIDs del Nordic UART Service (NUS) que implementa la ESP32.
// Se mantienen constantes porque todos los dispositivos del mismo modelo
// comparten el mismo contrato GATT; no identifican a un dispositivo individual.
const _uartServiceUuid = '6E400001-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartWriteUuid = '6E400002-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartReadUuid = '6E400003-B5A3-F393-E0A9-E50E24DCCA9E';

// Panel principal: muestra la nafta y administra la comunicación GATT.
class MainScreen extends StatefulWidget {
  final String nombreUsuario;
  final BluetoothDevice? device;

  const MainScreen({super.key, required this.nombreUsuario, this.device});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  int _selectedTab = 1;

  // Valor inicial usado por el simulador cuando no hay ESP32 conectada.
  double _nivelNafta = 75.0;
  int _velocidad = 0;
  int _rpm = 0;

  // Característica 6E400002: canal de escritura app -> ESP32.
  BluetoothCharacteristic? _writeCharacteristic;

  // Característica 6E400003 si permite lectura explícita.
  BluetoothCharacteristic? _readCharacteristic;

  // Característica 6E400003 si permite notificaciones ESP32 -> app.
  BluetoothCharacteristic? _notifyCharacteristic;

  // Suscripción que recibe automáticamente las notificaciones de la ESP32.
  StreamSubscription<List<int>>? _receiveSubscription;

  // Suscripción usada para reflejar conexiones y desconexiones en la UI.
  StreamSubscription<BluetoothConnectionState>? _connectionSubscription;

  // Textos de diagnóstico visibles durante el desarrollo.
  String _connectionStatus = 'Modo demo: sin conexión BLE';
  String _receivedData = 'Sin datos recibidos';

  // Impide iniciar dos escrituras simultáneas sobre la misma característica.
  bool _isSending = false;

  // Campo de texto cuyo contenido se convierte a bytes UTF-8 al enviar.
  final _sendController = TextEditingController();

  @override
  void initState() {
    super.initState();
    final device = widget.device;
    if (device != null) {
      // El estado puede cambiar después de abandonar el escáner, por eso se
      // observa el stream del dispositivo en lugar de asumir que la conexión
      // permanecerá activa durante toda la pantalla.
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
      // Si Android perdió la conexión, se intenta restablecer antes de consultar
      // servicios. Después de reconectar hay que descubrirlos nuevamente.
      if (!device.isConnected) {
        await device.connect(license: License.nonprofit, autoConnect: false);
      }
      final services = await device.discoverServices();
      _writeCharacteristic = null;
      _readCharacteristic = null;
      _notifyCharacteristic = null;
      // Se busca el servicio por UUID, no por posición en la lista.
      final uartService = services.cast<BluetoothService?>().firstWhere(
        (service) => service!.uuid == Guid(_uartServiceUuid),
        orElse: () => null,
      );

      if (uartService == null) {
        throw StateError('No se encontró el servicio UART de la ESP32');
      }

      for (final characteristic in uartService.characteristics) {
        // La dirección de datos la determina el UUID y las propiedades GATT.
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
        // READ requiere una acción manual; NOTIFY permite que la ESP32 envíe
        // datos espontáneamente. Activamos la suscripción solo si está soportada.
        await receiveCharacteristic.setNotifyValue(true);
        _receiveSubscription = receiveCharacteristic.onValueReceived.listen((
          value,
        ) {
          if (!mounted|| value.isEmpty) return;

          final bytes = Uint8List.fromList(value);
          final byteData = ByteData.sublistView(bytes);
          final id = byteData.getUint8(0);

          setState(() {
            if (id == 0x01 && bytes.length >= 4) {
              // Struct SpeedRpmPacket: id (1 byte), speed (1 byte), rpm (2 bytes)
              _velocidad = byteData.getUint8(1);
              _rpm = byteData.getUint16(2, Endian.little); // ESP32 usa Little Endian
              _receivedData = 'Paquete 0x01 - Vel: $_velocidad km/h | RPM: $_rpm';
            } 
            else if (id == 0x02 && bytes.length >= 3) {
              // Struct EngTempFuelPacket: id (1 byte), temp (1 byte), fuel (1 byte)
              final temp = byteData.getUint8(1);
              _nivelNafta = byteData.getUint8(2).toDouble();
              _receivedData = 'Paquete 0x02 - Temp: $temp°C | Nafta: ${_nivelNafta.toInt()}%';
            }
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

    // La escritura se serializa para evitar operaciones GATT simultáneas.
    setState(() => _isSending = true);
    try {
      if (!device.isConnected) {
        await _prepareBleConnection();
      }
      final activeCharacteristic = _writeCharacteristic;
      if (activeCharacteristic == null || !device.isConnected) {
        throw StateError('La ESP32 no está conectada o no acepta escritura');
      }
      // El texto se transmite como bytes UTF-8. El firmware de la ESP32 debe
      // interpretar esos bytes con el mismo formato y protocolo de mensajes.
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
      // Esta lectura es bajo demanda: no reemplaza las notificaciones.
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
    // Se cancelan streams y controllers para evitar fugas y callbacks sobre una
    // pantalla que ya no existe. La conexión del dispositivo puede gestionarse
    // aparte según la política de reconexión de la aplicación.
    _receiveSubscription?.cancel();
    _connectionSubscription?.cancel();
    _sendController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      _buildTripsPage(context),
      _buildCarPage(context),
      _buildSettingsPage(context),
    ];

    return Scaffold(
      appBar: AppBar(
        title: Text(
          _selectedTab == 0
              ? 'Tus viajes'
              : _selectedTab == 1
              ? 'Mi coche'
              : 'Ajustes',
        ),
        centerTitle: false,
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Center(
              child: Text(
                widget.nombreUsuario,
                style: TextStyle(color: Colors.grey.shade400),
              ),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 220),
          child: pages[_selectedTab],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedTab,
        onDestinationSelected: (index) => setState(() => _selectedTab = index),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.route_outlined),
            selectedIcon: Icon(Icons.route),
            label: 'Viajes',
          ),
          NavigationDestination(
            icon: Icon(Icons.directions_car_outlined),
            selectedIcon: Icon(Icons.directions_car),
            label: 'Coche',
          ),
          NavigationDestination(
            icon: Icon(Icons.tune_outlined),
            selectedIcon: Icon(Icons.tune),
            label: 'Ajustes',
          ),
        ],
      ),
    );
  }

  Widget _buildCarPage(BuildContext context) {
    final connected =
        widget.device != null &&
        _connectionStatus.toLowerCase().contains('conectado');
    return ListView(
      key: const ValueKey('car'),
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      children: [
        Text(
          'Hola, ${widget.nombreUsuario}',
          style: Theme.of(context).textTheme.headlineMedium
              ?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 4),
        Text(
          connected ? 'Telemetría en directo' : 'Resumen del vehículo',
          style: TextStyle(color: Colors.grey.shade400),
        ),
        const SizedBox(height: 20),
        Container(
          padding: const EdgeInsets.all(22),
          decoration: BoxDecoration(
            color: Colors.blue.shade900.withValues(alpha: 0.55),
            borderRadius: BorderRadius.circular(24),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('OBD-C · Demo'),
                      SizedBox(height: 6),
                      Text(
                        'Volkswagen Golf GTI',
                        style: TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                  Icon(
                    Icons.directions_car,
                    size: 48,
                    color: Colors.lightBlue.shade200,
                  ),
                ],
              ),
              const SizedBox(height: 28),
              Row(
                children: [
                  Expanded(
                    child: _metric(
                      'Nafta',
                      '${_nivelNafta.toInt()}%',
                      Icons.local_gas_station,
                    ),
                  ),
                  Expanded(
                    child: _metric('Kilometraje', '48.320 km', Icons.speed),
                  ),
                  Expanded(child: _metric('Estado', 'Bueno', Icons.favorite)),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: _infoTile('Velocidad', '$_velocidad km/h', Icons.speed),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _infoTile(
                'RPM',
                '$_rpm',
                Icons.rotate_right,
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        _buildBlePanel(),
      ],
    );
  }

  Widget _buildTripsPage(BuildContext context) {
    return ListView(
      key: const ValueKey('trips'),
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      children: [
        Text(
          'Tu año al volante',
          style: Theme.of(context).textTheme.headlineMedium
              ?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 4),
        Text(
          'Una mirada rápida a tus rutas recientes',
          style: TextStyle(color: Colors.grey.shade400),
        ),
        const SizedBox(height: 22),
        Container(
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: Colors.teal.shade900.withValues(alpha: 0.6),
            borderRadius: BorderRadius.circular(24),
          ),
          child: const Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Tu número estrella'),
              SizedBox(height: 8),
              Text(
                '1.284 km',
                style: TextStyle(fontSize: 42, fontWeight: FontWeight.w900),
              ),
              SizedBox(height: 4),
              Text('recorridos en 37 viajes'),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: _infoTile(
                'Viaje más largo',
                '86 km',
                Icons.wb_sunny_outlined,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _infoTile('Tiempo conduciendo', '28 h', Icons.schedule),
            ),
          ],
        ),
        const SizedBox(height: 24),
        Text(
          'Últimos viajes',
          style: Theme.of(context).textTheme.titleLarge
              ?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        _tripRow('Casa → Trabajo', 'Hoy · 12,4 km', '18 min'),
        _tripRow('Ruta costera', 'Ayer · 42,8 km', '51 min'),
        _tripRow('Centro → Norte', 'Dom, 31 ago · 8,6 km', '16 min'),
      ],
    );
  }

  Widget _buildSettingsPage(BuildContext context) {
    return ListView(
      key: const ValueKey('settings'),
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      children: [
        Text(
          'Ajustes',
          style: Theme.of(context).textTheme.headlineMedium
              ?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 20),
        ListTile(
          leading: const Icon(Icons.bluetooth),
          title: const Text('Conexión OBD'),
          subtitle: Text(_connectionStatus),
          trailing: const Icon(Icons.chevron_right),
        ),
        const Divider(),
        const ListTile(
          leading: Icon(Icons.directions_car_outlined),
          title: Text('Vehículo'),
          subtitle: Text('Volkswagen Golf GTI'),
          trailing: Icon(Icons.chevron_right),
        ),
        const Divider(),
        const ListTile(
          leading: Icon(Icons.notifications_outlined),
          title: Text('Notificaciones'),
          subtitle: Text('Alertas de mantenimiento'),
          trailing: Icon(Icons.chevron_right),
        ),
        const SizedBox(height: 24),
        FilledButton.tonalIcon(
          onPressed: () => Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (_) => const LoginScreen()),
          ),
          icon: const Icon(Icons.logout),
          label: const Text('Cerrar sesión'),
        ),
      ],
    );
  }

  Widget _buildBlePanel() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white10,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.circle,
                size: 10,
                color: _connectionStatus.startsWith('Conectado')
                    ? Colors.greenAccent
                    : Colors.orangeAccent,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _connectionStatus,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Último dato: $_receivedData',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _sendController,
                  enabled: _writeCharacteristic != null,
                  decoration: const InputDecoration(
                    labelText: 'Enviar comando',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  onSubmitted: (_) => _sendData(),
                ),
              ),
              IconButton(
                tooltip: 'Enviar dato',
                onPressed: _writeCharacteristic == null ? null : _sendData,
                icon: const Icon(Icons.send),
              ),
              IconButton(
                tooltip: 'Leer dato',
                onPressed: _readCharacteristic?.properties.read == true
                    ? _readData
                    : null,
                icon: const Icon(Icons.download),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Simulador de nafta',
            style: TextStyle(color: Colors.grey.shade400),
          ),
          Slider(
            value: _nivelNafta,
            min: 0,
            max: 100,
            divisions: 100,
            activeColor: _nivelNafta < 20 ? Colors.red : Colors.blueAccent,
            onChanged: (value) => setState(() => _nivelNafta = value),
          ),
        ],
      ),
    );
  }

  Widget _metric(String label, String value, IconData icon) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: Colors.lightBlue.shade200),
        const SizedBox(height: 8),
        Text(
          value,
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 2),
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey.shade300),
        ),
      ],
    );
  }

  Widget _infoTile(String label, String value, IconData icon) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white10,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: Colors.amber.shade300),
          const SizedBox(height: 12),
          Text(
            value,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 4),
          Text(label, style: TextStyle(color: Colors.grey.shade400)),
        ],
      ),
    );
  }

  Widget _tripRow(String title, String subtitle, String duration) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const CircleAvatar(child: Icon(Icons.route)),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: Text(duration, style: TextStyle(color: Colors.grey.shade400)),
    );
  }
}
