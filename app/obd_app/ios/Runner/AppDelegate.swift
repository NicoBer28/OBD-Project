import UIKit
import Flutter

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
      
    // 1. Despertamos al gestor de Bluetooth para que empiece a escuchar en segundo plano
    _ = ObdBluetoothManager.shared
      
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  // 2. Este método es exclusivo de la nueva arquitectura de Apple (SceneDelegate)
  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
      
    // 3. EL ARREGLO: Extraemos el mensajero usando un registrador
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "OBDEventBridge") {
        ObdEventBridge.shared.flutterApi = ObdFlutterApi(binaryMessenger: registrar.messenger())
    }
  }
}