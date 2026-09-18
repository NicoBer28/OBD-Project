package com.example.obd_app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import android.bluetooth.*
import java.util.UUID
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ObdCompanionService : CompanionDeviceService() {

    private val NOTIFICATION_ID = 1996
    private val CHANNEL_ID = "OBD_TRIP_CHANNEL"
    
    // El motor de GPS de Google
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Variables para guardar el último estado conocido
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var lastSpeed: Int = 0
    private var lastRpm: Int = 0
    private var lastFuel: Int = 0

    // 1. Definimos los UUIDs exactos (Los mismos que usabas en Dart)
    private val OBD_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    // El Descriptor estándar de Bluetooth para habilitar notificaciones (CCCD)
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()
    }

    // ¡EL AUTO SE ENCENDIÓ Y SE CONECTÓ!
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val macAddress = associationInfo.deviceMacAddress.toString()
        Log.i("OBD-C", "Auto detectado ($macAddress). Iniciando Viaje...")

        // 1. Iniciamos el Foreground Service con la notificación imborrable
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD-C")
            .setContentText("Registrando viaje en curso...")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Cambiar por tu logo
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        startForeground(NOTIFICATION_ID, notification)

        // 2. Encendemos el GPS (Solo durante el viaje)
        iniciarTrackingGPS()

        // 3. ACÁ CONECTAREMOS EL BLUETOOTH (GATT)
        conectarBLE(macAddress)
    }

    // ¡EL AUTO SE APAGÓ!
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i("OBD-C", "Auto apagado. Finalizando Viaje...")

        // 1. Apagamos el GPS
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // 2. Desconectamos BLE
        desconectarBLE()

        // 3. Matamos el servicio y quitamos la notificación
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun iniciarTrackingGPS() {
        // Configuramos para que no mate la batería: cada 10 segs, o si se mueve 15 metros.
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateDistanceMeters(15f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    lastLat = location.latitude
                    lastLng = location.longitude
                    
                    Log.i("OBD-C", "NUEVO PUNTO -> GPS: [$lastLat, $lastLng] | Nafta: $lastFuel%")
                    
                    // ACÁ SE GUARDA EN LA BASE DE DATOS LOCAL EL "PUNTO ATÓMICO"
                    // BaseDeDatosLocal.insertarPunto(lastLat, lastLng, lastFuel, timestamp)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("OBD-C", "Faltan permisos de ubicación de fondo: $e")
        }
    }

    private fun crearCanalNotificacion() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Viajes Activos",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

private fun conectarBLE(macAddress: String) {
        val macMayusculas = macAddress.uppercase()
        Log.i("OBD-C", "Antena ocupada leyendo aparición. Esperando 1 segundo...")
        
        // Retrasamos la conexión 1000 milisegundos. ESTO ES VITAL.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                val device = adapter.getRemoteDevice(macMayusculas)

                Log.i("OBD-C", "Intentando dar la mano (GATT LE) al ESP32 ($macMayusculas)...")
                
                // BluetoothDevice.TRANSPORT_LE fuerza a usar el chip moderno
                bluetoothGatt = device.connectGatt(
                    this@ObdCompanionService, 
                    true,
                    gattCallback, 
                    BluetoothDevice.TRANSPORT_LE 
                )
                
                if (bluetoothGatt == null) {
                    Log.e("OBD-C", "Android se negó a iniciar la conexión GATT.")
                }
            } catch (e: SecurityException) {
                Log.e("OBD-C", "🚨 FALTA PERMISO BLUETOOTH_CONNECT: ${e.message}")
            } catch (e: Exception) {
                Log.e("OBD-C", "Error fatal conectando BLE: ${e.message}")
            }
        }, 1000) // <-- 1000 ms (1 segundo) de delay
    }

    // Llamá a esta función desde onDeviceDisappeared
    private fun desconectarBLE() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    // 2. El "Cerebro" de la conexión Bluetooth
    private val gattCallback = object : BluetoothGattCallback() {

        // Se ejecuta cuando el ESP32 acepta o rechaza la conexión
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Filtro de seguridad: Si el status NO es 0 (GATT_SUCCESS), falló.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("OBD-C", "Error GATT: status=$status. Cerrando conexión para liberar memoria.")
                gatt.close()
                bluetoothGatt = null
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("OBD-C", "GATT Conectado. Buscando servicios OBD...")
                // Al conectarse, obligamos a Android a descubrir qué UUIDs tiene el ESP32
                gatt.discoverServices() 
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w("OBD-C", "GATT Desconectado.")
            }
        }

        // Se ejecuta cuando Android termina de leer los UUIDs del ESP32
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(OBD_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    Log.i("OBD-C", "Característica encontrada. Suscribiendo...")
                    
                    // a) Le decimos a Android que nos interesa la característica
                    gatt.setCharacteristicNotification(characteristic, true)
                    
                    // b) El Truco de Android: Escribimos el descriptor 2902 para que el ESP32 empiece a mandar datos
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                } else {
                    Log.e("OBD-C", "No se encontró la característica de notificación")
                }
            }
        }

        // Se ejecuta cada 20ms (o cuando el ESP32 mande un dato) - PARA ANDROID 12 O INFERIOR
        @Deprecated("Usado para compatibilidad con celulares viejos")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            procesarPaquete(characteristic.value)
        }

        // Se ejecuta cada 20ms - PARA ANDROID 13 O SUPERIOR
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            procesarPaquete(value)
        }
    }

    // 3. El Parser: Mapeamos la estructura de C++ a variables locales
    private fun procesarPaquete(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        // Extraemos el ID (usando and 0xFF para evitar números negativos)
        val id = bytes[0].toInt() and 0xFF

        if (id == 0x01 && bytes.size >= 4) {
            // SpeedRpmPacket: id (1), speed (1), rpm (2)
            val speed = bytes[1].toInt() and 0xFF
            
            // Reconstruimos el uint16_t (Little Endian, como lo manda el ESP32)
            val rpmLow = bytes[2].toInt() and 0xFF
            val rpmHigh = bytes[3].toInt() and 0xFF
            val rpm = (rpmHigh shl 8) or rpmLow
            
            lastSpeed = speed
            lastRpm = rpm

        } else if (id == 0x02 && bytes.size >= 3) {
            // EngTempFuelPacket: id (1), temp (1), fuel (1)
            val temp = bytes[1].toInt() and 0xFF
            val fuel = bytes[2].toInt() and 0xFF
            
            // Actualizamos la variable global que lee el GPS
            lastFuel = fuel
            
            Log.d("OBD-C", "Nafta recibida en Background: $fuel%")
        }
        // 1. Armamos el objeto autogenerado por Pigeon
        val evento = TelemetryEvent(
            speed = lastSpeed.toLong(), // Pigeon mapea los Int de Dart a Long en Kotlin
            rpm = lastRpm.toLong(),
            fuel = lastFuel.toLong(),
            lat = lastLat,
            lng = lastLng
        )

        // 2. Enviamos a Flutter asegurándonos de estar en el Hilo Principal usando Corrutinas
        CoroutineScope(Dispatchers.Main).launch {
            try {
                ObdEventBridge.flutterApi?.onTelemetryUpdated(evento)
            } catch (e: Exception) {
                Log.e("OBD-C", "Error enviando a Flutter: \$e")
            }
        }

    }
}