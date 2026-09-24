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
}

@FlutterApi()
abstract class ObdFlutterApi {
  void onTelemetryUpdated(TelemetryEvent event);
}
