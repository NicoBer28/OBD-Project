import 'package:obd_app/src/generated/obd_api.g.dart';

/// Textos en español para el estado de la conexión con el ESP32 de un auto.
/// Todo lo que la app dice sobre la conexión sale de este archivo.
extension EstadoConexionFormat on EstadoConexion {
  String get etiqueta => switch (this) {
    EstadoConexion.desconectado => 'Desconectado',
    EstadoConexion.conectando => 'Auto detectado - conectando…',
    EstadoConexion.conectado => 'Conectado',
    EstadoConexion.reconectando => 'Conexión perdida - esperando que vuelva…',
  };

  /// Detectado pero sin datos todavía, o cortado con el viaje aún abierto.
  bool get enEspera => this == EstadoConexion.conectando || this == EstadoConexion.reconectando;
}

/// Textos de la tarjeta del viaje y del dato "Estado" mientras la conexión está a medias.
abstract final class ConexionTextos {
  /// Dato "Estado" del auto: recién detectado, todavía sin datos.
  static const estadoConectando = 'Conectando…';

  /// Dato "Estado" del auto: se cortó y corre el tiempo de gracia.
  static const estadoSinConexion = 'Sin conexión';

  /// Título de la tarjeta del viaje en curso cuando se cortó la conexión.
  static const viajeSinConexion = 'Se perdió la conexión con el auto';

  /// Detalle de esa tarjeta. [restante] es la cuenta regresiva ("2:31") hasta que el viaje
  /// se da por terminado; null si no se sabe cuánto falta.
  static String viajeSeTerminaEn(String? restante) => restante == null
      ? 'Si no vuelve en unos minutos, el viaje se da por terminado'
      : 'Si no vuelve, el viaje se da por terminado en $restante';
}
