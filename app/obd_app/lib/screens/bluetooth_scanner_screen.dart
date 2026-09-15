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

  bool _mostrarTodos = false;

  String? _conectandoDeviceId;

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
          content: Text('Bluetooth BLE no está disponible'),
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
    await FlutterBluePlus.stopScan();
    
    setState(() {
      _conectandoDeviceId = device.remoteId.str;
    });

    const int maxIntentos = 10;
    int intentoActual = 0;
    bool conectado = false;
    String ultimoError = '';

    // Bucle de reintentos silenciosos
    while (intentoActual < maxIntentos && !conectado) {
      intentoActual++;
      try {
        // Configuramos un timeout para que no se quede colgado eternamente
        await device.connect(
          license: License.nonprofit, 
          autoConnect: false,
          timeout: const Duration(seconds: 5),
        );
        conectado = true;
      } catch (e) {
        ultimoError = e.toString();
        // El Error 133 requiere que el sistema operativo respire antes de reintentar
        if (intentoActual < maxIntentos) {
          await Future.delayed(const Duration(milliseconds: 1500));
        }
      }
    }

    if (!mounted) return;
    
    // Restauramos el estado del botón
    setState(() {
      _conectandoDeviceId = null;
    });

    if (conectado) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Conectado exitosamente a ${device.advName}')),
      );
      widget.onConnected(device);
    } else {
      // Solo mostramos error si fallaron todos los intentos
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Error tras $maxIntentos intentos: $ultimoError'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }
@override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Buscar Dispositivo'),
        actions: [
          IconButton(
            tooltip: _mostrarTodos
                ? 'Mostrando todos'
                : 'Mostrando solo ESP/OBD',
            icon: Icon(
              _mostrarTodos ? Icons.filter_alt_off : Icons.filter_alt,
            ),
            onPressed: () {
              setState(() => _mostrarTodos = !_mostrarTodos);
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
                
                // Filtramos la lista según el estado del botón superior
                final visibleResults = _mostrarTodos
                    ? results
                    : results.where((result) {
                        final nombreMayusculas = result.device.advName.toUpperCase();
                        return nombreMayusculas.contains('ESP') || 
                               nombreMayusculas.contains('OBD');
                      }).toList();

                if (visibleResults.isEmpty) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        _mostrarTodos
                            ? 'No se encontraron dispositivos cercanos.'
                            : 'Buscando ESP o OBD...\n\nUsá el filtro para ver todos los dispositivos.',
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
                    
                    final isConnectingToThis = _conectandoDeviceId == device.remoteId.str;
                    final isAnyConnecting = _conectandoDeviceId != null;

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
                          // Desactiva los botones si ya hay un proceso de conexión en curso
                          onPressed: isAnyConnecting 
                              ? null 
                              : () => _conectarDispositivo(device),
                          child: isConnectingToThis
                              ? const SizedBox(
                                  width: 16, 
                                  height: 16, 
                                  child: CircularProgressIndicator(strokeWidth: 2)
                                )
                              : const Text('Conectar'),
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
                  // Desactiva el botón de continuar sin conexión si está intentando conectarse
                  onPressed: _conectandoDeviceId != null 
                      ? null 
                      : widget.onContinueWithoutConnection,
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