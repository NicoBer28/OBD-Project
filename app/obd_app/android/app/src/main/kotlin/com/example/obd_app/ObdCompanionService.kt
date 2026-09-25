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
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

//Foreground Service que se ejecuta en segundo plano cuando el auto está encendido y conectado al ESP32
class ObdCompanionService : CompanionDeviceService() {
   private val NOTIFICATION_ID = 1996
    private val CHANNEL_ID = "OBD_TRIP_CHANNEL"
    
    // --- GPS ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // --- VARIABLES DE ESTADO ACTUAL ---
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var lastSpeed: Int = 0
    private var lastRpm: Int = 0
    private var lastFuel: Int = 0

    // --- BLUETOOTH ---
    private val OBD_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var bluetoothGatt: BluetoothGatt? = null

    private var disconnectJob: Job? = null
    private val TIEMPO_DE_GRACIA_MS = 3 * 60 * 1000L // 3 minutos

    // --- BASE DE DATOS Y BUFFER LOCAL ---
    private lateinit var db: AppDatabase
    private lateinit var dao: ObdDao
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var currentTripId: String? = null
    private val carIdFalso = "2d14dd55-c2c8-4b5a-aae8-b1c93a632cfc" 
    
    private val ramBuffer = mutableListOf<JSONObject>()
    private var chunkStartTime = 0L

    companion object {
        private const val FILAS_POR_CHUNK = 50
    }
    
    // TODO: Mejorar lógica de detección de inicio real del auto
    private var isTripActive = false

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        dao = db.obdDao()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()
    }

    // Se ejecuta cuando Android detecta que el ESP32 está en el aire (auto encendido)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val macAddress = associationInfo.deviceMacAddress.toString()
        Log.i("OBD-C", "Auto detectado ($macAddress). Iniciando Viaje...")

        //si habia un contarizador, lo cancelamos
        disconnectJob?.cancel()
        disconnectJob = null

        // iniciamos el Foreground Service con una notificación fija
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD")
            .setContentText("Conectado al auto.")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO Cambiar por otro logo
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        startForeground(NOTIFICATION_ID, notification)

        iniciarTrackingGPS()

        conectarBLE(macAddress)
    }

    // se desconectó del ESP32 (auto apagado o fuera de rango)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i("OBD-C", "Auto apagado. Finalizando Viaje...")

        val tiempoExactoDesconexion = System.currentTimeMillis()
        
        fusedLocationClient.removeLocationUpdates(locationCallback)

        desconectarBLE()

        disconnectJob = serviceScope.launch {
            delay(TIEMPO_DE_GRACIA_MS)
            
            Log.i("OBD-C", "⏳ Tiempo de gracia expirado. Finalizando Viaje definitivamente...")

            if (isTripActive) {
                finalizarViajeLocal(lastFuel, tiempoExactoDesconexion)
                isTripActive = false
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // Apagamos el servicio de Android
        }
    }

    private fun iniciarTrackingGPS() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateDistanceMeters(15f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    lastLat = location.latitude
                    lastLng = location.longitude
                    
                    Log.i("OBD-C", "NUEVO PUNTO -> GPS: [$lastLat, $lastLng] | Nafta: $lastFuel%")
                    
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
        
        // Retrasamos la conexión 1000 milisegundos (para no saturar)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                val device = adapter.getRemoteDevice(macMayusculas)

                Log.i("OBD-C", "Intentando dar la mano (GATT LE) al ESP32 ($macMayusculas)...")
                
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
        }, 1000) //ms
    }

    private fun desconectarBLE() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    // Escucha cuando el ESP32 acepta la conexión, busca los servicios y se suscribe a las notificaciones
    private val gattCallback = object : BluetoothGattCallback() {

        // Se ejecuta cuando el ESP32 acepta o rechaza la conexión
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("OBD-C", "Error GATT: status=$status. Cerrando conexión para liberar memoria.")
                gatt.close()
                bluetoothGatt = null
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("OBD-C", "GATT Conectado. Buscando servicios OBD...")
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
                    
                    gatt.setCharacteristicNotification(characteristic, true)
                    
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
            recepcionDatos(characteristic.value)
        }

        // Se ejecuta cada 20ms - PARA ANDROID 13 O SUPERIOR
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            recepcionDatos(value)
        }
    }

    private fun recepcionDatos(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        decodificarBytes(bytes)

        if (!isTripActive) {
            iniciarViajeLocal(lastFuel)
            isTripActive = true
        }

        guardarTelemetriaLocal()

        notificarAFlutter()
    }

    private fun decodificarBytes(bytes: ByteArray) {
        val id = bytes[0].toInt() and 0xFF
        if (id == 0x01 && bytes.size >= 4) {
            lastSpeed = bytes[1].toInt() and 0xFF
            val rpmLow = bytes[2].toInt() and 0xFF
            val rpmHigh = bytes[3].toInt() and 0xFF
            lastRpm = (rpmHigh shl 8) or rpmLow
        } else if (id == 0x02 && bytes.size >= 3) {
            val temp = bytes[1].toInt() and 0xFF
            lastFuel = bytes[2].toInt() and 0xFF
        }
    }

    private fun guardarTelemetriaLocal() {
        val timestamp = System.currentTimeMillis()
        if (ramBuffer.isEmpty()) {
            chunkStartTime = timestamp 
        }

        val muestra = JSONObject().apply {
            put("t", timestamp - chunkStartTime)
            put("s", lastSpeed)
            put("r", lastRpm)
            put("f", lastFuel)
        }
        
        ramBuffer.add(muestra)

        if (ramBuffer.size >= FILAS_POR_CHUNK) {
            val muestrasParaGuardar = JSONArray(ramBuffer.toList())
            val tiempoInicioChunk = chunkStartTime
            
            ramBuffer.clear()

            serviceScope.launch {
                val payloadString = JSONObject().apply {
                    put("t0", tiempoInicioChunk)
                    put("samples", muestrasParaGuardar)
                }.toString()

                val nuevoChunk = TelemetryChunk(
                    id = UUID.randomUUID().toString(),
                    carId = carIdFalso,
                    startTime = tiempoInicioChunk,
                    payload = payloadString,
                    syncStatus = "PENDING"
                )
                
                dao.insertChunk(nuevoChunk)
                //le avisamos al WorkManager que hay datos nuevos.
                // solo ejecutar si hay conexión a Internet.
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(applicationContext).enqueue(syncWorkRequest)
            }
        }
    }

    private fun notificarAFlutter() {
        val evento = TelemetryEvent(
            speed = lastSpeed.toLong(),
            rpm = lastRpm.toLong(),
            fuel = lastFuel.toLong(),
            lat = lastLat,
            lng = lastLng
        )

        CoroutineScope(Dispatchers.Main).launch {
            try {
                ObdEventBridge.flutterApi?.onTelemetryUpdated(evento)
            } catch (e: Exception) {
                // Silenciado intencionalmente si Flutter está cerrado
            }
        }
    }

    private fun iniciarViajeLocal(nivelNaftaInicial: Int) {
        serviceScope.launch {
            val (tokenFlutter, carIdFlutter) = obtenerCredencialesFlutter(applicationContext)
            val idDelAuto = carIdFlutter ?: "AUTO_DESCONOCIDO"

            var activeTrip = dao.getActiveTrip()
            
            if (activeTrip == null) {
                currentTripId = UUID.randomUUID().toString()
                activeTrip = Trip(
                    localId = currentTripId!!,
                    backendId = null,
                    carId = carIdFalso,
                    initialFuel = nivelNaftaInicial,
                    startedAt = System.currentTimeMillis(),
                    status = "ACTIVE",
                    syncStatus = "PENDING_START"
                )
                dao.insertTrip(activeTrip)
                Log.i("OBD-DB", "¡Nuevo viaje creado en SQLite! ID local: $currentTripId")
                if (tokenFlutter != null && carIdFlutter != null) {
                    try {
                        val tokenFalso = "Bearer TU_TOKEN_DE_PRUEBA" // TODO: Traer desde Flutter
                        val request = StartTripRequest(carId = carIdFalso, initialFuel = nivelNaftaInicial)
                        val response = ApiClient.retrofitService.startTrip(tokenFalso, request)
                        
                        if (response.isSuccessful) {
                            val backendId = response.body()?.id
                            if (backendId != null) {
                                val viajeSincronizado = activeTrip.copy(
                                    backendId = backendId,
                                    syncStatus = "SYNCED_START"
                                )
                                dao.updateTrip(viajeSincronizado)
                                Log.i("OBD-RED", "Viaje iniciado en Backend. ID real: $backendId")
                            }
                        } else {
                            Log.e("OBD-RED", "Error del server al iniciar: ${response.code()}. Queda PENDING_START.")
                        }
                    } catch (e: Exception) {
                        Log.w("OBD-RED", "Sin internet al iniciar. Queda en cola local: ${e.message}")
                    }
                }
            } else {
                currentTripId = activeTrip.localId
            }
        }
    }

    private fun finalizarViajeLocal(nivelNaftaFinal: Int, tiempoFin: Long = System.currentTimeMillis()) {
        serviceScope.launch {
            val (tokenFlutter, carIdFlutter) = obtenerCredencialesFlutter(applicationContext)

            val activeTrip = dao.getActiveTrip()
            if (activeTrip != null) {
                val viajeCerrado = activeTrip.copy(
                    endedAt = tiempoFin,
                    finalFuel = nivelNaftaFinal,
                    status = "FINISHED",
                    syncStatus = "PENDING_FINISH"
                )
                dao.updateTrip(viajeCerrado)
                Log.i("OBD-DB", "Viaje cerrado en SQLite local.")
                val backendId = viajeCerrado.backendId
                if (backendId == null) {
                    Log.w("OBD-RED", "Falta backendId. No se puede finalizar en la nube aún.")
                    return@launch 
                }

                val backendId = viajeCerrado.backendId
                if (backendId == null) {
                    Log.w("OBD-RED", "Falta backendId. No se puede finalizar en la nube aún.")
                    return@launch 
                }
                
                if (tokenFlutter == null) {
                    Log.w("OBD-RED", "No hay token de sesión. Se subirá luego.")
                    return@launch 
                }

                try {
                    val distanciaSimulada = 1.0 // TODO: Calcular distancia real. La API exige > 0
                    val request = FinishTripRequest(
                        tripFinalFuel = nivelNaftaFinal, 
                        tripDistance = distanciaSimulada
                    )
                    
                    val response = ApiClient.retrofitService.finishTrip(tokenFlutter, backendId, request)
                    
                    if (response.isSuccessful) {
                        val viajeCompletado = viajeCerrado.copy(syncStatus = "COMPLETED")
                        dao.updateTrip(viajeCompletado)
                        Log.i("OBD-RED", "Viaje finalizado exitosamente en el servidor.")
                    } else {
                        Log.e("OBD-RED", "Error del server al finalizar: ${response.code()}. Queda PENDING_FINISH.")
                    }
                } catch (e: Exception) {
                    Log.w("OBD-RED", "Sin internet al finalizar. Queda en cola local: ${e.message}")
                }
            }
        }
    }

    fun obtenerCredencialesFlutter(context: Context): Pair<String?, String?> {
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        
        // Flutter le agrega automáticamente el prefijo "flutter_"
        val token = prefs.getString("flutter_token", null)
        val carId = prefs.getString("flutter_carId", null)
        
        return Pair(token, carId)
    }

}