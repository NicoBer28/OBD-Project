import Foundation
import CoreLocation

// Usamos NSObject y CLLocationManagerDelegate para poder hablar con el hardware del iPhone
class ObdLocationManager: NSObject, CLLocationManagerDelegate {
    
    // Instancia global única (Singleton) para poder accederla desde el Bluetooth
    static let shared = ObdLocationManager()
    
    private let locationManager = CLLocationManager()
    
    // Acá vamos a ir guardando las coordenadas más frescas
    var lastLatitude: Double = 0.0
    var lastLongitude: Double = 0.0
    
    private override init() {
        super.init()
        locationManager.delegate = self
        
        // Configuración profesional para viajes vehiculares
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.distanceFilter = 10 // Actualiza cada 10 metros
        
        // ¡La magia del segundo plano!
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true // Muestra la pastilla azul arriba
    }
    
    func startTracking() {
        // Pedimos los permisos que declaraste recién en el Info.plist
        locationManager.requestAlwaysAuthorization()
        locationManager.startUpdatingLocation()
        print("📍 OBD-C: Iniciando rastreo GPS en segundo plano.")
    }
    
    func stopTracking() {
        locationManager.stopUpdatingLocation()
        print("📍 OBD-C: Rastreo GPS detenido.")
    }
    
    // Este método es "la oreja": se dispara solo cuando el iPhone detecta que te moviste
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        
        // Actualizamos las variables globales con la coordenada fresca
        lastLatitude = location.coordinate.latitude
        lastLongitude = location.coordinate.longitude
    }
    
    // Si el usuario se mete en un túnel sin señal, capturamos el error sin que explote la app
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("❌ OBD-C Error de GPS: \(error.localizedDescription)")
    }
}