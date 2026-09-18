import 'package:pigeon/pigeon.dart';

// Le decimos a Pigeon dónde generar los archivos traducidos
@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/generated/obd_api.g.dart',
  kotlinOut: 'android/app/src/main/kotlin/com/example/obd_app/ObdApi.g.kt',
  kotlinOptions: KotlinOptions(package: 'com.example.obd_app'),
))

// Esta es la "Caja" unificada que contiene OBD + GPS
class TelemetryEvent {
  int? speed;
  int? rpm;
  int? fuel;
  double? lat;
  double? lng;
}

// @FlutterApi indica que esto es Nativo hablando hacia Flutter (De Kotlin a Dart)
@FlutterApi()
abstract class ObdFlutterApi {
  void onTelemetryUpdated(TelemetryEvent event);
}