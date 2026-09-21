import Foundation
import CoreBluetooth

// Implementamos los delegados nativos de Apple para actuar como "Central" (el iPhone) conectándose a un "Periférico" (el ESP32)
class ObdBluetoothManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    
    // Singleton para accederlo desde toda la app
    static let shared = ObdBluetoothManager()
    
    private var centralManager: CBCentralManager!
    private var obdPeripheral: CBPeripheral?
    
    // Los UUIDs idénticos a los que usó tu amigo en Android
    let obdServiceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    let txCharacteristicUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    
    // Variables para almacenar la última lectura
    private var lastSpeed: Int64 = 0
    private var lastRpm: Int64 = 0
    private var lastFuel: Int64 = 0
    
    private override init() {
        super.init()
        // ¡LA CLAVE DE LA PERSISTENCIA EN IOS!
        // Le damos un RestoreIdentifier. Si la app se cierra, iOS guarda este manager en memoria.
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.example.obd_app.centralRestore"]
        )
    }
    
    // 1. Resurrección (State Restoration)
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        print("🔄 OBD-C: iOS está restaurando el Bluetooth en segundo plano")
        // Recuperamos el ESP32 si iOS lo mantuvo conectado por nosotros
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for peripheral in peripherals {
                obdPeripheral = peripheral
                obdPeripheral?.delegate = self
            }
        }
    }
    
    // 2. Estado del Bluetooth del iPhone
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            if let peripheral = obdPeripheral {
                // Si la app resucitó y ya conocía al auto, se reconecta directo
                centralManager.connect(peripheral, options: nil)
            } else {
                // Si es la primera vez, escaneamos buscando el servicio
                centralManager.scanForPeripherals(withServices: [obdServiceUUID], options: nil)
            }
        } else {
            print("❌ OBD-C: Bluetooth apagado o sin permisos.")
        }
    }
    
    // 3. Auto Encontrado
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        print("🔍 OBD-C: ESP32 Encontrado. Conectando...")
        obdPeripheral = peripheral
        obdPeripheral?.delegate = self
        centralManager.stopScan()
        
        // En iOS, conectarse a un periférico sin timeout lo deja "en espera" infinitamente hasta que el auto aparezca.
        centralManager.connect(peripheral, options: nil)
    }
    
    // 4. Conexión Exitosa
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        print("✅ OBD-C: Conectado al auto. Buscando servicios...")
        // Le avisamos al GPS que el viaje empezó
        ObdLocationManager.shared.startTracking()
        peripheral.discoverServices([obdServiceUUID])
    }
    
    // 5. Desconexión (El auto se apagó o se alejó)
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        print("⚠️ OBD-C: Desconectado. iOS intentará reconectar automáticamente.")
        // Apagamos el GPS para no gastar batería
        ObdLocationManager.shared.stopTracking()
        // Le pedimos a iOS que se conecte automáticamente apenas vuelva a ver el auto en el aire
        centralManager.connect(peripheral, options: nil)
    }
    
    // 6. Configurando Notificaciones
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == obdServiceUUID {
            peripheral.discoverCharacteristics([txCharacteristicUUID], for: service)
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics where characteristic.uuid == txCharacteristicUUID {
            print("🔔 OBD-C: Suscribiendo a notificaciones...")
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }
    
    // 7. Llegada de Datos (¡El motor está hablando!)
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        let bytes = [UInt8](data)
        if bytes.isEmpty { return }
        
        let id = bytes[0]
        
        // Decodificación exacta de la estructura Little Endian
        if id == 0x01 && bytes.count >= 4 {
            lastSpeed = Int64(bytes[1])
            let rpmLow = UInt16(bytes[2])
            let rpmHigh = UInt16(bytes[3])
            lastRpm = Int64((rpmHigh << 8) | rpmLow)
        } else if id == 0x02 && bytes.count >= 3 {
            lastFuel = Int64(bytes[2])
        }
        
        // Juntamos el motor con el GPS
        let lat = ObdLocationManager.shared.lastLatitude
        let lng = ObdLocationManager.shared.lastLongitude
        
        // Armamos el objeto autogenerado por Pigeon para Flutter
        let evento = TelemetryEvent(
            speed: lastSpeed,
            rpm: lastRpm,
            fuel: lastFuel,
            lat: lat,
            lng: lng
        )
        
        // MANDAMOS A FLUTTER EN EL HILO PRINCIPAL
        DispatchQueue.main.async {
            // El ObdEventBridge lo vamos a crear en el último paso (Paso 4)
            if let api = ObdEventBridge.shared.flutterApi {
                api.onTelemetryUpdated(eventArg: evento) { _ in }
            }
        }
    }
}