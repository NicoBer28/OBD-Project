import 'package:flutter/foundation.dart';
import 'package:obd_app/core/native_bridge.dart';
import 'package:obd_app/data/api/obd_api.dart';

enum ResultadoVinculacion { vinculado, cancelado, error }

/// Cómo terminó [DeviceLinkService.vincular]. [reemplazoCarId] es el auto que tenía ese
/// ESP32 antes, si era otro.
typedef Vinculacion = ({ResultadoVinculacion resultado, String? reemplazoCarId});

/// Qué ESP32 tiene vinculado cada auto en este celular, y el paso de la sesión al nativo.
///
/// Cada auto tiene su propio ESP32. El servicio nativo se conecta solo al que aparece y
/// atribuye los datos a su auto, así que acá no hace falta decirle cuál está elegido.
class DeviceLinkService extends ChangeNotifier {
  DeviceLinkService._();

  static final DeviceLinkService instance = DeviceLinkService._();

  Map<String, AsociacionLocal> _asociaciones = const {};
  final Set<String> _vinculando = {};
  Map<String, ConexionAuto> _conexiones = const {};

  /// Lo último que se le mandó al nativo, para no repetir el envío si nada cambió.
  String? _lastSessionKey;

  /// Hay un ESP32 guardado para ese auto. No dice si el auto está encendido ahora.
  bool estaVinculado(String carId) => _asociaciones.containsKey(carId);

  bool estaVinculando(String carId) => _vinculando.contains(carId);

  AsociacionLocal? asociacionDe(String carId) => _asociaciones[carId];

  /// En qué está la conexión con el ESP32 de ese auto ahora mismo.
  ConexionAuto conexionDe(String carId) => _conexiones[carId] ?? ConexionAuto.desconectado;

  Future<void> cargar() async {
    final lista = await NativeBleBridge.obtenerAsociaciones();
    final conexiones = await NativeBleBridge.obtenerEstadosConexion();
    _asociaciones = {for (final a in lista) a.carId: a};
    _conexiones = conexiones;
    notifyListeners();
  }

  /// El nativo avisó que cambió la conexión de [carId].
  void actualizarConexion(String carId, ConexionAuto conexion) {
    _conexiones = {..._conexiones, carId: conexion};
    notifyListeners();
  }

  /// Abre el selector de Android para elegir el ESP32 de [carId].
  Future<Vinculacion> vincular({required String carId, required String carName}) async {
    _vinculando.add(carId);
    notifyListeners();
    try {
      final resultado = await NativeBleBridge.iniciarVinculacion(carId: carId);
      if (resultado == null) return (resultado: ResultadoVinculacion.cancelado, reemplazoCarId: null);

      // FASE 2: crear el token de dispositivo y llamar a guardarCredencial
      await cargar();
      return (resultado: ResultadoVinculacion.vinculado, reemplazoCarId: resultado.reemplazoCarId);
    } on VinculacionException catch (e) {
      debugPrint('No se pudo vincular $carName: $e');
      return (resultado: ResultadoVinculacion.error, reemplazoCarId: null);
    } finally {
      _vinculando.remove(carId);
      notifyListeners();
    }
  }

  Future<void> desvincular(String carId) async {
    // FASE 2: revocar el token
    await NativeBleBridge.desvincularAuto(carId);
    await cargar();
  }

  /// Le pasa al nativo el servidor, el usuario y (por ahora) el access token. Hay que
  /// llamarlo cada vez que cambia la sesión. Si la sesión se cayó, el nativo deja de subir
  /// pero conserva las vinculaciones: sigue grabando y sube cuando haya un token nuevo.
  Future<void> sincronizarSesion() async {
    final session = ObdApi.instance.session;

    if (!session.isAuthenticated) {
      if (_lastSessionKey == null) return;
      _lastSessionKey = null;
      await NativeBleBridge.setSessionToken(null);
      return;
    }

    final token = session.accessToken;
    final userId = session.userId;
    final key = '$token|$userId';
    if (key == _lastSessionKey) return;
    _lastSessionKey = key;

    await NativeBleBridge.configurar(baseUrl: ObdApi.instance.client.config.baseUrl, userId: userId);
    await NativeBleBridge.setSessionToken(token);
  }

  /// Cuántos registros (telemetría y viajes) están guardados sin subir.
  Future<int> pendientesDeSubir() async {
    final estado = await NativeBleBridge.estadoSincronizacion();
    return estado.pendingChunks + estado.pendingTrips;
  }

  /// Logout manual: se desvinculan todos los autos de este celular.
  Future<void> cerrarSesion({required bool descartarPendientes}) async {
    // FASE 2: revocar todos los tokens antes del logout
    await NativeBleBridge.cerrarSesion(descartarPendientes: descartarPendientes);
    _lastSessionKey = null;
    _asociaciones = const {};
    _conexiones = const {};
    _vinculando.clear();
    notifyListeners();
    await ObdApi.instance.auth.logout();
  }
}
