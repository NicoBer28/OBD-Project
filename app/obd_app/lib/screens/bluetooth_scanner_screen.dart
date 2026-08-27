import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';

class BluetoothScannerScreen extends StatefulWidget {
  final String nombreUsuario;
  final ValueChanged<BluetoothDevice> onConnected;
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
  bool _isScanning = false;
  bool _mostrarSinNombre = false;

  @override
  void initState() {
    super.initState();
    // Buena práctica: pedir permisos apenas carga la pantalla
    _solicitarPermisos();
  }

  Future<void> _solicitarPermisos() async {
    await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.location,
    ].request();
  }

  Future<void> _iniciarEscaneo() async {
    if (!await FlutterBluePlus.isSupported) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Bluetooth BLE no está disponible en esta plataforma'),
        ),
      );
      return;
    }

    // Si el Bluetooth está apagado, no hacemos nada
    if (!mounted) return;
    if (await FlutterBluePlus.adapterState.first != BluetoothAdapterState.on) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Por favor, encendé el Bluetooth')),
      );
      return;
    }

    setState(() => _isScanning = true);

    // Escaneamos por 15 segundos y buscamos dispositivos
    try {
      await FlutterBluePlus.startScan(timeout: const Duration(seconds: 15));
    } finally {
      if (mounted) setState(() => _isScanning = false);
    }
  }

  Future<void> _detenerEscaneo() async {
    await FlutterBluePlus.stopScan();
    if (mounted) setState(() => _isScanning = false);
  }

  Future<void> _conectarDispositivo(BluetoothDevice device) async {
    // Regla de oro: SIEMPRE detener el escaneo antes de conectar
    await FlutterBluePlus.stopScan();

    try {
      // Conexión nativa
      await device.connect(license: License.nonprofit, autoConnect: false);

      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Conectado exitosamente a ${device.advName}')),
      );

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
              stream: FlutterBluePlus.scanResults,
              initialData: const [],
              builder: (context, snapshot) {
                final results = snapshot.data ?? [];
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
