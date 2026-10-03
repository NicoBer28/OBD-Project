import 'package:flutter/foundation.dart';
import 'package:obd_app/core/native_bridge.dart';
import 'package:obd_app/data/api/obd_api.dart';

enum ResultadoVinculacion { vinculado, cancelado, error }

/// Cómo terminó [DeviceLinkService.vincular]. [reemplazoCarId] es el auto que tenía ese
/// ESP32 antes, si era otro.
typedef Vinculacion = ({ResultadoVinculacion resultado, String? reemplazoCarId});

/// Cómo terminó el alta de un token de dispositivo. [noEncontrado] es un `404`: el usuario
/// ya no tiene acceso al auto, o el servidor no tiene el endpoint.
enum _Alta { ok, noEncontrado, error }

/// Qué ESP32 tiene vinculado cada auto en este celular, y el token de dispositivo con el que
/// el nativo sube los datos de cada uno con la app cerrada.
///
/// Cada auto tiene su propio ESP32. El servicio nativo se conecta solo al que aparece y
/// atribuye los datos a su auto, así que acá no hace falta decirle cuál está elegido.
///
/// Los tokens se crean y revocan con la sesión del usuario (solo con la app abierta). El
/// nativo nunca los renueva: si uno deja de servir, lo marca inválido y la próxima
/// [reconciliar] crea otro.
class DeviceLinkService extends ChangeNotifier {
  DeviceLinkService._();

  static final DeviceLinkService instance = DeviceLinkService._();

  /// Un token que vence antes de esto se reemplaza al abrir la app.
  static const _renovarAntesDe = Duration(days: 30);

  Map<String, AsociacionLocal> _asociaciones = const {};
  final Set<String> _vinculando = {};
  Map<String, ConexionAuto> _conexiones = const {};

  Future<void>? _reconciliando;

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

  /// Abre el selector de Android para elegir el ESP32 de [carId] y le crea su token.
  ///
  /// Si el token no se puede crear (por ejemplo, sin red) el auto queda vinculado igual:
  /// el nativo graba y la próxima [reconciliar] crea el token.
  Future<Vinculacion> vincular({required String carId, required String carName}) async {
    _vinculando.add(carId);
    notifyListeners();
    try {
      final resultado = await NativeBleBridge.iniciarVinculacion(carId: carId);
      if (resultado == null) return (resultado: ResultadoVinculacion.cancelado, reemplazoCarId: null);

      final alta = await _crearCredencial(carId: carId, carName: carName, label: resultado.deviceLabel);
      if (alta != _Alta.ok) {
        debugPrint('$carName quedó vinculado sin token; se reintenta al reconciliar.');
      }
      await cargar();

      // El auto que tenía este ESP32 se quedó sin vinculación: su token se revoca ahí.
      if (resultado.reemplazoCarId != null) reconciliar();

      return (resultado: ResultadoVinculacion.vinculado, reemplazoCarId: resultado.reemplazoCarId);
    } on VinculacionException catch (e) {
      debugPrint('No se pudo vincular $carName: $e');
      return (resultado: ResultadoVinculacion.error, reemplazoCarId: null);
    } finally {
      _vinculando.remove(carId);
      notifyListeners();
    }
  }

  /// Este celular deja de registrar los viajes de [carId]. Lo ya grabado se sube con el
  /// token mientras siga guardado; después queda en cola.
  Future<void> desvincular(String carId) async {
    final estado = await NativeBleBridge.estadoCredenciales();
    final tokenId = estado.where((e) => e.carId == carId).firstOrNull?.tokenId;
    if (tokenId != null) await _revocar(tokenId);
    await NativeBleBridge.desvincularAuto(carId);
    await cargar();
  }

  /// Pone al día al nativo: a qué servidor sube, quién es el usuario, y un token válido
  /// para cada auto vinculado. Llamarlo al abrir la app y al volver a primer plano.
  ///
  /// - Auto vinculado sin token, con el token rechazado o por vencer → token nuevo (y se
  ///   revoca el anterior).
  /// - El usuario ya no tiene acceso al auto (`404` al crear **y** al pedir el auto) → se
  ///   desvincula.
  /// - Token sin vinculación (el ESP32 pasó a otro auto) → se revoca y se borra.
  ///
  /// Si ya hay una en curso devuelve la misma. Nunca lanza.
  Future<void> reconciliar() => _reconciliando ??= _reconciliar().whenComplete(() => _reconciliando = null);

  Future<void> _reconciliar() async {
    try {
      final session = ObdApi.instance.session;
      if (!session.isAuthenticated) return;

      await NativeBleBridge.configurar(baseUrl: ObdApi.instance.client.config.baseUrl, userId: session.userId);

      final limite = DateTime.now().add(_renovarAntesDe);
      Map<String, String>? nombres;
      var desvinculados = false;

      for (final e in await NativeBleBridge.estadoCredenciales()) {
        // La vinculación en curso crea su propio token.
        if (_vinculando.contains(e.carId)) continue;

        if (!e.vinculado) {
          final tokenId = e.tokenId;
          if (tokenId != null) await _revocar(tokenId);
          await NativeBleBridge.desvincularAuto(e.carId);
          continue;
        }

        final vence = e.expiresAt;
        final renovar = !e.valid || (vence != null && vence.isBefore(limite));
        if (!renovar) continue;

        nombres ??= await _nombresDeAutos();
        final alta = await _crearCredencial(
          carId: e.carId,
          carName: nombres[e.carId],
          label: e.deviceLabel,
          anterior: e.tokenId,
        );
        if (alta != _Alta.noEncontrado) continue;

        // Un 404 al crear el token no alcanza para desvincular: también lo contesta un
        // servidor que todavía no tiene ese endpoint. Se confirma preguntando por el auto.
        if (await _perdioAcceso(e.carId)) {
          debugPrint('Ya no hay acceso al auto ${e.carId}: se desvincula de este celular.');
          await NativeBleBridge.desvincularAuto(e.carId);
          desvinculados = true;
        } else {
          debugPrint(
            'El servidor no tiene tokens de dispositivo (404 al crear el de ${e.carId}). '
            'El auto sigue vinculado y graba, pero no sube hasta que el servidor se actualice.',
          );
        }
      }

      if (desvinculados) await cargar();
    } catch (error) {
      debugPrint('No se pudo reconciliar los tokens de dispositivo: $error');
    }
  }

  /// Cuántos registros (telemetría y viajes) están guardados sin subir.
  Future<int> pendientesDeSubir() async {
    final estado = await NativeBleBridge.estadoSincronizacion();
    return estado.pendingChunks + estado.pendingTrips;
  }

  /// Logout manual: se revocan los tokens y se desvinculan todos los autos de este celular.
  Future<void> cerrarSesion({required bool descartarPendientes}) async {
    // Una reconciliación a medias podría crear un token después del logout.
    await _reconciliando;

    // Antes del logout: revocar necesita la sesión.
    final estado = await NativeBleBridge.estadoCredenciales();
    await Future.wait([
      for (final e in estado)
        if (e.tokenId != null) _revocar(e.tokenId!),
    ]);

    await NativeBleBridge.cerrarSesion(descartarPendientes: descartarPendientes);
    _asociaciones = const {};
    _conexiones = const {};
    _vinculando.clear();
    notifyListeners();
    await ObdApi.instance.auth.logout();
  }

  // ---------------------------------------------------------------- TOKENS

  /// Crea el token de [carId] y se lo entrega al nativo. Si había uno [anterior], lo revoca
  /// después (recién cuando el nuevo ya está guardado). Nunca lanza.
  Future<_Alta> _crearCredencial({
    required String carId,
    String? carName,
    required String label,
    String? anterior,
  }) async {
    final DeviceToken creado;
    try {
      creado = await ObdApi.instance.deviceTokens.create(carId, label: label);
    } on ApiException catch (e) {
      if (e.isNotFound) return _Alta.noEncontrado;
      debugPrint('No se pudo crear el token de $carId: $e');
      return _Alta.error;
    } catch (e) {
      debugPrint('No se pudo crear el token de $carId: $e');
      return _Alta.error;
    }

    final secreto = creado.token;
    if (secreto == null || creado.id.isEmpty) {
      debugPrint('El servidor no devolvió el token de $carId.');
      return _Alta.error;
    }

    try {
      await NativeBleBridge.guardarCredencial(
        carId: carId,
        carName: carName,
        tokenId: creado.id,
        token: secreto,
        expiresAt: creado.idleExpiresAt,
      );
    } catch (e) {
      // Que no quede vivo en el servidor un token que nadie tiene.
      debugPrint('El nativo no pudo guardar el token de $carId: $e');
      await _revocar(creado.id);
      return _Alta.error;
    }

    if (anterior != null && anterior != creado.id) await _revocar(anterior);
    return _Alta.ok;
  }

  /// Best effort: un `404` (ya revocado, o de otro usuario) cuenta como hecho.
  Future<void> _revocar(String tokenId) async {
    try {
      await ObdApi.instance.deviceTokens.revoke(tokenId);
    } on ApiException catch (e) {
      if (!e.isNotFound) debugPrint('No se pudo revocar el token $tokenId: $e');
    } catch (e) {
      debugPrint('No se pudo revocar el token $tokenId: $e');
    }
  }

  /// True solo si el servidor confirma que el usuario ya no puede ver [carId]. Ante
  /// cualquier duda (sin red, otro error) es false: desvincular no se deshace solo.
  Future<bool> _perdioAcceso(String carId) async {
    try {
      await ObdApi.instance.cars.byId(carId);
      return false;
    } on ApiException catch (e) {
      return e.isNotFound;
    } catch (_) {
      return false;
    }
  }

  /// Para el texto de la notificación del nativo ("…reactivar la sincronización de {auto}").
  Future<Map<String, String>> _nombresDeAutos() async {
    try {
      return {for (final car in await ObdApi.instance.cars.list()) car.id: car.name};
    } catch (_) {
      return const {};
    }
  }
}
