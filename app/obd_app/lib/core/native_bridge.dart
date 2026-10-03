import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:obd_app/src/generated/obd_api.g.dart';

/// Lo que devuelve el nativo cuando se vinculó un ESP32 con un auto.
class VinculacionResultado {
  final String carId;
  final String mac;
  final int associationId;

  /// Marca y modelo del celular (ej. "samsung SM-A546E").
  final String deviceLabel;

  /// Auto al que estaba vinculado ese ESP32 antes, si era otro.
  final String? reemplazoCarId;

  const VinculacionResultado({
    required this.carId,
    required this.mac,
    required this.associationId,
    required this.deviceLabel,
    this.reemplazoCarId,
  });

  factory VinculacionResultado.fromMap(Map<Object?, Object?> map) => VinculacionResultado(
    carId: map['carId'] as String,
    mac: map['mac'] as String,
    associationId: map['associationId'] as int,
    deviceLabel: map['deviceLabel'] as String? ?? '',
    reemplazoCarId: map['reemplazoCarId'] as String?,
  );
}

/// Un ESP32 vinculado a un auto en este celular.
class AsociacionLocal {
  final String carId;
  final String mac;
  final int associationId;

  const AsociacionLocal({required this.carId, required this.mac, required this.associationId});

  factory AsociacionLocal.fromMap(Map<Object?, Object?> map) => AsociacionLocal(
    carId: map['carId'] as String,
    mac: map['mac'] as String,
    associationId: map['associationId'] as int,
  );
}

/// Lo que el nativo tiene guardado y todavía no subió.
class EstadoSincronizacion {
  final int pendingChunks;
  final int pendingTrips;
  final int failedChunks;

  const EstadoSincronizacion({this.pendingChunks = 0, this.pendingTrips = 0, this.failedChunks = 0});

  factory EstadoSincronizacion.fromMap(Map<Object?, Object?> map) => EstadoSincronizacion(
    pendingChunks: map['pendingChunks'] as int? ?? 0,
    pendingTrips: map['pendingTrips'] as int? ?? 0,
    failedChunks: map['failedChunks'] as int? ?? 0,
  );
}

/// El token de dispositivo de un auto, según lo que tiene guardado el nativo.
class EstadoCredencial {
  final String carId;

  /// El ESP32 vinculado a ese auto. Null = el token quedó sin vinculación (por ejemplo, el
  /// ESP32 se reasignó a otro auto) y hay que revocarlo.
  final String? mac;

  /// Null = el auto está vinculado pero todavía no tiene token.
  final String? tokenId;

  /// False si no hay token o si el servidor lo rechazó.
  final bool valid;

  /// Cuándo vence si no se usa (el `idleExpiresAt` que dio el servidor al crearlo).
  final DateTime? expiresAt;

  /// Marca y modelo de este celular, para el `label` de un token nuevo.
  final String deviceLabel;

  const EstadoCredencial({
    required this.carId,
    this.mac,
    this.tokenId,
    this.valid = false,
    this.expiresAt,
    this.deviceLabel = '',
  });

  bool get vinculado => mac != null;

  factory EstadoCredencial.fromMap(Map<Object?, Object?> map) {
    final expiresAt = map['expiresAt'];
    return EstadoCredencial(
      carId: map['carId'] as String,
      mac: map['mac'] as String?,
      tokenId: map['tokenId'] as String?,
      valid: map['valid'] as bool? ?? false,
      expiresAt: expiresAt is int ? DateTime.fromMillisecondsSinceEpoch(expiresAt) : null,
      deviceLabel: map['deviceLabel'] as String? ?? '',
    );
  }
}

/// En qué está la conexión con el ESP32 de un auto.
class ConexionAuto {
  final EstadoConexion estado;

  /// Solo con [EstadoConexion.reconectando]: cuándo vence el tiempo de gracia y el viaje se
  /// da por terminado si no volvió la conexión.
  final DateTime? finGracia;

  const ConexionAuto(this.estado, {this.finGracia});

  static const desconectado = ConexionAuto(EstadoConexion.desconectado);

  factory ConexionAuto.fromMs(EstadoConexion estado, int? finGraciaMs) => ConexionAuto(
    estado,
    finGracia: finGraciaMs == null ? null : DateTime.fromMillisecondsSinceEpoch(finGraciaMs),
  );

  factory ConexionAuto.fromMap(Map<Object?, Object?> map) {
    final estado = map['estado'];
    return ConexionAuto.fromMs(
      estado is int && estado >= 0 && estado < EstadoConexion.values.length
          ? EstadoConexion.values[estado]
          : EstadoConexion.desconectado,
      map['finGraciaMs'] as int?,
    );
  }
}

/// La vinculación falló por algo que no fue una cancelación del usuario.
class VinculacionException implements Exception {
  final String code;
  final String? message;

  const VinculacionException(this.code, [this.message]);

  @override
  String toString() => 'VinculacionException($code${message == null ? '' : ': $message'})';
}

class NativeBleBridge {
  // sintonizamos exactamente la misma frecuencia que pusimos en Kotlin
  static const platform = MethodChannel('com.example.obd_app/ble_channel');

  /// Abre el selector de Android para elegir el ESP32 de [carId] y espera a que el usuario
  /// elija. Devuelve null si canceló; lanza [VinculacionException] en cualquier otro error.
  static Future<VinculacionResultado?> iniciarVinculacion({required String carId}) async {
    try {
      final result = await platform.invokeMapMethod<Object?, Object?>('iniciarVinculacion', {'carId': carId});
      if (result == null) throw const VinculacionException('ERROR', 'Respuesta vacía del nativo');
      return VinculacionResultado.fromMap(result);
    } on PlatformException catch (e) {
      if (e.code == 'CANCELADO') return null;
      throw VinculacionException(e.code, e.message);
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
      throw const VinculacionException('NO_DISPONIBLE', 'La vinculación solo está disponible en Android');
    }
  }

  /// Le dice al nativo a qué servidor sube y quién es el usuario. El auto ya no se manda:
  /// sale del ESP32 que aparece.
  static Future<void> configurar({required String baseUrl, required String? userId}) async {
    try {
      await platform.invokeMethod('configurar', {'baseUrl': baseUrl, 'userId': userId});
    } on PlatformException catch (e) {
      debugPrint('No se pudo configurar el nativo: ${e.message}');
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
    }
  }

  /// Le entrega al nativo el token de dispositivo de [carId], recién creado. El nativo lo
  /// guarda cifrado y sube enseguida lo que estaba en cola para ese auto. Lanza
  /// [PlatformException] si no se pudo guardar.
  static Future<void> guardarCredencial({
    required String carId,
    String? carName,
    required String tokenId,
    required String token,
    DateTime? expiresAt,
  }) async {
    try {
      await platform.invokeMethod('guardarCredencial', {
        'carId': carId,
        'carName': carName,
        'tokenId': tokenId,
        'token': token,
        'expiresAt': expiresAt?.millisecondsSinceEpoch,
      });
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
    }
  }

  /// Qué token tiene cada auto vinculado en este celular, más los tokens que quedaron sin
  /// vinculación (con `mac` null) y hay que revocar.
  static Future<List<EstadoCredencial>> estadoCredenciales() async {
    try {
      final result = await platform.invokeListMethod<Object?>('estadoCredenciales');
      return [
        for (final item in result ?? const <Object?>[])
          if (item is Map<Object?, Object?>) EstadoCredencial.fromMap(item),
      ];
    } on PlatformException catch (e) {
      debugPrint('No se pudo leer el estado de las credenciales: ${e.message}');
      return const [];
    } on MissingPluginException {
      return const [];
    }
  }

  /// Los ESP32 vinculados en este celular (uno por auto, como mucho).
  static Future<List<AsociacionLocal>> obtenerAsociaciones() async {
    try {
      final result = await platform.invokeListMethod<Object?>('obtenerAsociaciones');
      return [
        for (final item in result ?? const <Object?>[])
          if (item is Map<Object?, Object?>) AsociacionLocal.fromMap(item),
      ];
    } on PlatformException catch (e) {
      debugPrint('No se pudieron leer las asociaciones: ${e.message}');
      return const [];
    } on MissingPluginException {
      return const [];
    }
  }

  /// En qué está la conexión con el ESP32 de cada auto vinculado, por `carId`. Es la foto
  /// del momento; los cambios posteriores llegan por `ObdFlutterApi.onConnectionChanged`.
  static Future<Map<String, ConexionAuto>> obtenerEstadosConexion() async {
    try {
      final result = await platform.invokeMapMethod<String, Object?>('obtenerEstadosConexion');
      return {
        for (final MapEntry(:key, :value) in (result ?? const <String, Object?>{}).entries)
          if (value is Map<Object?, Object?>) key: ConexionAuto.fromMap(value),
      };
    } on PlatformException catch (e) {
      debugPrint('No se pudo leer el estado de las conexiones: ${e.message}');
      return const {};
    } on MissingPluginException {
      return const {};
    }
  }

  static Future<EstadoSincronizacion> estadoSincronizacion() async {
    try {
      final result = await platform.invokeMapMethod<Object?, Object?>('estadoSincronizacion');
      return result == null ? const EstadoSincronizacion() : EstadoSincronizacion.fromMap(result);
    } on PlatformException catch (e) {
      debugPrint('No se pudo leer el estado de sincronización: ${e.message}');
      return const EstadoSincronizacion();
    } on MissingPluginException {
      return const EstadoSincronizacion();
    }
  }

  /// Este celular deja de registrar los viajes de [carId]. Lo ya guardado igual se sube.
  static Future<void> desvincularAuto(String carId) async {
    try {
      await platform.invokeMethod('desvincularAuto', {'carId': carId});
    } on PlatformException catch (e) {
      debugPrint('No se pudo desvincular el auto: ${e.message}');
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
    }
  }

  /// Logout: se desvinculan todos los autos, se borran sus tokens y el nativo deja de subir. Con
  /// [descartarPendientes] también se borra lo que estaba guardado sin subir.
  static Future<void> cerrarSesion({required bool descartarPendientes}) async {
    try {
      await platform.invokeMethod('cerrarSesion', {'descartarPendientes': descartarPendientes});
    } on PlatformException catch (e) {
      debugPrint('No se pudo cerrar la sesión nativa: ${e.message}');
    } on MissingPluginException {
      // iOS todavía no implementa el canal.
    }
  }
}
