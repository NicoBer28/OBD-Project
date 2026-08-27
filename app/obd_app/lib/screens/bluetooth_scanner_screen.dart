import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';

// Esta pantalla se encarga únicamente de descubrir dispositivos BLE cercanos
// y establecer la conexión inicial. La comunicación GATT posterior ocurre en
// MainScreen, que recibe el dispositivo conectado mediante `onConnected`.
class BluetoothScannerScreen extends StatefulWidget {
  // Se conserva para que la pantalla siguiente pueda saludar al usuario.
  final String nombreUsuario;

  // Callback ejecutado cuando la conexión BLE se completa correctamente.
  // El objeto BluetoothDevice permite continuar usando el mismo dispositivo.
  final ValueChanged<BluetoothDevice> onConnected;

  // Permite entrar al panel sin dispositivo, usando el simulador de nafta.
  final VoidCallback onContinueWithoutConnection;

  const BluetoothScannerScreen({
    super.key,
    required this.nombreUsuario,
    required this.onConnected,
    required this.onContinueWithoutConnection,
  });

  @override
  State<BluetoothScannerScreen> createState() => _BluetoothScannerScreenState();
}

class _BluetoothScannerScreenState extends State<BluetoothScannerScreen> {
  // Controla el ícono de la barra superior y evita iniciar acciones duplicadas.
  bool _isScanning = false;

  // Por defecto se ocultan anuncios BLE que no informan nombre.
  // El filtro permite mostrarlos cuando sea necesario identificar un dispositivo.
  bool _mostrarSinNombre = false;

  @override
  void initState() {
    super.initState();
    // El ciclo de vida initState se ejecuta una sola vez al crear la pantalla.
    // Se solicitan permisos antes de intentar acceder al adaptador Bluetooth.
    _solicitarPermisos();
  }

  Future<void> _solicitarPermisos() async {
    // Android puede requerir permisos de escaneo y conexión. La ubicación se
    // solicita para versiones de Android que la usan durante el descubrimiento.
    await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.location,
    ].request();
  }

  Future<void> _iniciarEscaneo() async {
    // Algunas plataformas, como Linux en ciertas configuraciones, no ofrecen
    // una implementación BLE compatible con flutter_blue_plus.
    if (!await FlutterBluePlus.isSupported) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Bluetooth BLE no está disponible en esta plataforma'),
        ),
      );
      return;
    }

    // El escaneo solo puede comenzar cuando el adaptador está encendido.
    if (!mounted) return;
    if (await FlutterBluePlus.adapterState.first != BluetoothAdapterState.on) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Por favor, encendé el Bluetooth')),
      );
      return;
    }

    setState(() => _isScanning = true);

    // flutter_blue_plus publica los resultados en `scanResults`. El await
    // termina al cumplirse el timeout o si el escaneo se detiene manualmente.
    try {
      await FlutterBluePlus.startScan(timeout: const Duration(seconds: 15));
    } finally {
      if (mounted) setState(() => _isScanning = false);
    }
  }

  Future<void> _detenerEscaneo() async {
    // Detener el escaneo antes de conectar reduce conflictos con Android BLE.
    await FlutterBluePlus.stopScan();
    if (mounted) setState(() => _isScanning = false);
  }

  Future<void> _conectarDispositivo(BluetoothDevice device) async {
    // Regla de oro: detener SIEMPRE el escaneo antes de conectar.
    await FlutterBluePlus.stopScan();

    try {
      // `License.nonprofit` es obligatorio en esta versión del paquete. La
      // conexión no automática espera a que este método termine correctamente.
      await device.connect(license: License.nonprofit, autoConnect: false);

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Conectado exitosamente a ${device.advName}')),
      );

      // El escáner no conoce la pantalla principal: informa el éxito mediante
      // un callback y deja que main.dart decida cómo navegar.
      widget.onConnected(device);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('Error de conexión: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Buscar Dispositivo'),
        actions: [
          IconButton(
            tooltip: _mostrarSinNombre
                ? 'Ocultar dispositivos sin nombre'
                : 'Mostrar dispositivos sin nombre',
            icon: Icon(
              _mostrarSinNombre ? Icons.filter_alt : Icons.filter_alt_outlined,
            ),
            onPressed: () {
              setState(() => _mostrarSinNombre = !_mostrarSinNombre);
            },
          ),
          _isScanning
              ? IconButton(
                  icon: const Icon(Icons.stop, color: Colors.red),
                  onPressed: _detenerEscaneo,
                )
              : IconButton(
                  icon: const Icon(Icons.search),
                  onPressed: _iniciarEscaneo,
                ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: StreamBuilder<List<ScanResult>>(
              // StreamBuilder reconstruye la lista cada vez que llega un nuevo
              // resultado del escaneo, sin tener que administrar una lista a mano.
              stream: FlutterBluePlus.scanResults,
              initialData: const [],
              builder: (context, snapshot) {
                final results = snapshot.data ?? [];
                // Se filtran solo para la interfaz; el escaneo BLE sigue
                // detectando todos los dispositivos cercanos.
                final visibleResults = _mostrarSinNombre
                    ? results
                    : results
                          .where((result) => result.device.advName.isNotEmpty)
                          .toList();

                if (visibleResults.isEmpty) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        _mostrarSinNombre
                            ? 'No se encontraron dispositivos cercanos.'
                            : 'No hay dispositivos con nombre. Activá el filtro para ver todos.',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  );
                }

                return ListView.builder(
                  itemCount: visibleResults.length,
                  itemBuilder: (context, index) {
                    final device = visibleResults[index].device;
                    final nombre = device.advName.isNotEmpty
                        ? device.advName
                        : 'Sin nombre (${device.remoteId})';

                    return Card(
                      margin: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                      child: ListTile(
                        leading: const Icon(Icons.bluetooth),
                        title: Text(nombre),
                        subtitle: Text(device.remoteId.toString()),
                        trailing: ElevatedButton(
                          onPressed: () => _conectarDispositivo(device),
                          child: const Text('Conectar'),
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
          SafeArea(
            // SafeArea evita que el botón quede debajo de la barra de navegación
            // o del área reservada para gestos del teléfono.
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: widget.onContinueWithoutConnection,
                  icon: const Icon(Icons.dashboard_outlined),
                  label: const Text('Continuar sin conectar (modo demo)'),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
