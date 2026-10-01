import 'package:pigeon/pigeon.dart';

//Es el contrato de pigeon

// Le decimos a Pigeon dónde generar los archivos traducidos
@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/generated/obd_api.g.dart',
    kotlinOut: 'android/app/src/main/kotlin/com/example/obd_app/ObdApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.example.obd_app'),
  ),
)
class TelemetryEvent {
  int? speed;
  int? rpm;
  int? fuel;
  double? lat;
  double? lng;

  /// Auto del que vienen los datos (sale de la MAC del ESP32). Null si ese ESP32 no está
  /// vinculado a ningún auto en este celular.
  String? carId;
}

/// En qué está la conexión entre este celular y el ESP32 de un auto.
enum EstadoConexion {
  /// El ESP32 no está al alcance (auto apagado o lejos) y no hay ningún viaje abierto.
  desconectado,

  /// Android detectó el ESP32, pero todavía no llegó ningún dato.
  conectando,

  /// Están llegando datos.
  conectado,

  /// Se cortó la conexión con un viaje abierto y todavía corre el tiempo de gracia: si
  /// vuelve a tiempo el viaje sigue; si no, se cierra.
  reconectando,
}

class ConnectionEvent {
  ConnectionEvent({required this.carId, required this.estado, this.finGraciaMs});

  String carId;
  EstadoConexion estado;

  /// Solo con [EstadoConexion.reconectando]: momento (epoch en ms) en que vence el tiempo
  /// de gracia y el viaje se da por terminado si no volvió la conexión.
  int? finGraciaMs;
}

@FlutterApi()
abstract class ObdFlutterApi {
  void onTelemetryUpdated(TelemetryEvent event);

  /// Cambió el estado de la conexión con el ESP32 de un auto.
  void onConnectionChanged(ConnectionEvent event);
}
