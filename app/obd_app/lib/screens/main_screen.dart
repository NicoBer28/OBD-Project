import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../src/generated/obd_api.g.dart'; // Importamos el código generado

import './login_screen.dart';
import '../native_bridge.dart';

import 'package:permission_handler/permission_handler.dart';


// UUIDs del Nordic UART Service (NUS) que implementa la ESP32.
// Se mantienen constantes porque todos los dispositivos del mismo modelo
// comparten el mismo contrato GATT; no identifican a un dispositivo individual.
const _uartServiceUuid = '6E400001-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartWriteUuid = '6E400002-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartReadUuid = '6E400003-B5A3-F393-E0A9-E50E24DCCA9E';

// Panel principal: muestra la nafta y administra la comunicación GATT.
class MainScreen extends StatefulWidget {
  final String nombreUsuario;

  const MainScreen({super.key, required this.nombreUsuario});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> implements ObdFlutterApi{
  int _selectedTab = 1;

  // Valor inicial usado por el simulador cuando no hay ESP32 conectada.
  double _nivelNafta = 75.0;
  int _velocidad = 0;
  int _rpm = 0;

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
    // 2. Registramos esta pantalla como la que va a recibir los datos de Kotlin
    ObdFlutterApi.setUp(this);
    _solicitarPermisos();
  }

  Future<void> _solicitarPermisos() async {
    await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.location,
      Permission.notification,
      // Si usás Android 13+, también deberías pedir permission.notification
    ].request();
  }

  @override
  void dispose() {
    // Nos desuscribimos al cerrar la pantalla
    ObdFlutterApi.setUp(null);
    super.dispose();
  }

  // 3. ¡Acá llega el dato directo desde el background service nativo!
  @override
  void onTelemetryUpdated(TelemetryEvent event) {
    if (!mounted) return;
    
    // Simplemente actualizamos la UI. Pigeon ya nos da un objeto con variables tipadas
    setState(() {
      _velocidad = event.speed ?? 0;
      _rpm = event.rpm ?? 0;
      _nivelNafta = (event.fuel ?? 0).toDouble();
      // También tenés event.lat y event.lng si querés actualizar un mapa en vivo
    });
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
    final connected =_connectionStatus.toLowerCase().contains('conectado');
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

  // (Borrá _isSending y _sendController de arriba)

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
                // Como todavía no trajimos el estado real desde Kotlin, 
                // lo simulamos sabiendo que si hay velocidad, hay conexión.
                color: _velocidad > 0 || _rpm > 0
                    ? Colors.greenAccent
                    : Colors.orangeAccent,
              ),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  "Agente BLE (Kotlin Background)",
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          
          // ESTE ES EL BOTÓN CRÍTICO PARA EL PASO CERO
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              icon: const Icon(Icons.bluetooth_searching),
              label: const Text('Vincular ESP32 (Fondo)'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.blue.shade700,
                padding: const EdgeInsets.symmetric(vertical: 12),
              ),
              onPressed: () async {
                // Al tocar acá, se levanta la ventana nativa de Android.
                // ¡A partir de ahí, Kotlin toma el control de tu vida!
                await NativeBleBridge.iniciarVinculacion();
              },
            ),
          ),
          
          const SizedBox(height: 16),
          Text(
            'Simulador de nafta UI',
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
