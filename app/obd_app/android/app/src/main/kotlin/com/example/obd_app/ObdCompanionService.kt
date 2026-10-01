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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

//Foreground Service que se ejecuta en segundo plano cuando el auto está encendido y conectado al ESP32
class ObdCompanionService : CompanionDeviceService() {
    private val NOTIFICATION_ID = 1996
    private val CHANNEL_ID = "OBD_TRIP_CHANNEL"

    // --- GPS ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // --- VARIABLES DE ESTADO ACTUAL ---
    // null = todavía no llegó ese dato. Así no se manda un 0 falso al servidor
    // (antes la nafta arrancaba en 0 hasta que llegaba el primer paquete 0x02).
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLng: Double? = null
    @Volatile private var lastSpeed: Int? = null
    @Volatile private var lastRpm: Int? = null
    @Volatile private var lastFuel: Int? = null

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

    // Auto al que se atribuyen los datos. Lo entrega Flutter (NativeSession).
    // TODO (Fase 2): resolverlo por la MAC del ESP32 (tabla de asociaciones).
    @Volatile private var carIdActual: String? = null

    // El BLE manda datos cada ~20 ms, pero el backend guarda UNA lectura por segundo por auto:
    // guardar más solo genera duplicados que el servidor descarta.
    private val bufferLock = Any()
    private val ramBuffer = mutableListOf<JSONObject>()
    private var chunkStartTime = 0L
    private var ultimaMuestraMs = 0L

    companion object {
        private const val MUESTREO_MIN_MS = 1_000L       // 1 muestra por segundo
        private const val CHUNK_MAX_MUESTRAS = 60        // se guarda al llegar a 60 muestras...
        private const val CHUNK_MAX_EDAD_MS = 15_000L    // ...o a los 15 s, lo que pase primero
    }

    // TODO: Mejorar lógica de detección de inicio real del auto
    @Volatile private var isTripActive = false

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        dao = db.obdDao()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()

        // Red de seguridad: si algún aviso de sync se perdió, cada 15 min se reintenta.
        SyncScheduler.schedulePeriodic(this)
    }

    // Se ejecuta cuando Android detecta que el ESP32 está en el aire (auto encendido)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val macAddress = associationInfo.deviceMacAddress?.toString()
        if (macAddress == null) {
            Log.e("OBD-C", "La asociación no tiene MAC. No se puede conectar.")
            return
        }
        Log.i("OBD-C", "Auto detectado ($macAddress). Iniciando Viaje...")

        //si habia un contarizador, lo cancelamos
        disconnectJob?.cancel()
        disconnectJob = null

        carIdActual = NativeSession.read(this)?.carId
        if (carIdActual == null) {
            Log.w("OBD-C", "Sin carId: abrí la app y elegí un auto. Se muestran datos en vivo pero no se guardan.")
        }

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
        Log.i("OBD-C", "Auto apagado. Esperando tiempo de gracia...")

        val tiempoExactoDesconexion = System.currentTimeMillis()

        detenerTrackingGPS()
        desconectarBLE()

        // Lo que quedó en RAM se guarda YA, no al final del tiempo de gracia.
        guardarBufferEnDb()

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

    override fun onDestroy() {
        detenerTrackingGPS()
        desconectarBLE()
        // Si Android mata el servicio, que no se pierda lo que estaba en RAM.
        val pendiente = extraerChunk()
        if (pendiente != null) {
            runBlocking(Dispatchers.IO) { persistirChunk(pendiente) }
        }
        super.onDestroy()
    }

    private fun iniciarTrackingGPS() {
        if (locationCallback != null) return // ya estaba activo (reconexión dentro del tiempo de gracia)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateDistanceMeters(15f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastLat = location.latitude
                lastLng = location.longitude
                Log.i("OBD-C", "NUEVO PUNTO -> GPS: [$lastLat, $lastLng] | Nafta: $lastFuel%")
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
            locationCallback = callback
        } catch (e: SecurityException) {
            Log.e("OBD-C", "Faltan permisos de ubicación de fondo: $e")
        }
    }

    private fun detenerTrackingGPS() {
        // Antes era lateinit: si el auto "desaparecía" sin haber aparecido (servicio recreado),
        // removeLocationUpdates tiraba UninitializedPropertyAccessException.
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
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

        // La UI en vivo se actualiza siempre, haya o no auto elegido.
        notificarAFlutter()

        val carId = carIdActual ?: return

        // El viaje arranca recién cuando se conoce la nafta (primer paquete 0x02), para no
        // registrar un nivel inicial de 0 que no es real.
        val nafta = lastFuel
        if (!isTripActive && nafta != null) {
            isTripActive = true
            serviceScope.launch { iniciarViajeLocal(carId, nafta) }
        }

        guardarTelemetriaLocal(carId)
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

    private fun guardarTelemetriaLocal(carId: String) {
        val ahora = System.currentTimeMillis()

        val chunkListo: TelemetryChunk? = synchronized(bufferLock) {
            if (ahora - ultimaMuestraMs < MUESTREO_MIN_MS) return
            ultimaMuestraMs = ahora

            if (ramBuffer.isEmpty()) {
                chunkStartTime = ahora
            }

            val muestra = JSONObject().apply {
                put("t", ahora - chunkStartTime)
                lastSpeed?.let { put("s", it) }
                lastRpm?.let { put("r", it) }
                lastFuel?.let { put("f", it) }
                val lat = lastLat
                val lng = lastLng
                if (lat != null && lng != null) {
                    put("la", lat)
                    put("lo", lng)
                }
            }
            ramBuffer.add(muestra)

            val lleno = ramBuffer.size >= CHUNK_MAX_MUESTRAS ||
                ahora - chunkStartTime >= CHUNK_MAX_EDAD_MS
            if (lleno) extraerChunkLocked(carId) else null
        }

        chunkListo?.let { serviceScope.launch { persistirChunk(it) } }
    }

    /** Arma un chunk con lo que hay en RAM y vacía el buffer. Llamar con bufferLock tomado. */
    private fun extraerChunkLocked(carId: String): TelemetryChunk? {
        if (ramBuffer.isEmpty()) return null

        val payloadString = JSONObject().apply {
            put("t0", chunkStartTime)
            put("samples", JSONArray(ramBuffer.toList()))
        }.toString()

        val chunk = TelemetryChunk(
            id = UUID.randomUUID().toString(),
            carId = carId,
            startTime = chunkStartTime,
            payload = payloadString,
            syncStatus = "PENDING"
        )
        ramBuffer.clear()
        return chunk
    }

    private fun extraerChunk(): TelemetryChunk? {
        val carId = carIdActual ?: return null
        return synchronized(bufferLock) { extraerChunkLocked(carId) }
    }

    private fun guardarBufferEnDb() {
        extraerChunk()?.let { serviceScope.launch { persistirChunk(it) } }
    }

    private suspend fun persistirChunk(chunk: TelemetryChunk) {
        dao.insertChunk(chunk)
        // Le avisamos al WorkManager que hay datos nuevos (sube solo cuando haya internet).
        SyncScheduler.requestSync(applicationContext)
    }

    private fun notificarAFlutter() {
        val evento = TelemetryEvent(
            speed = lastSpeed?.toLong(),
            rpm = lastRpm?.toLong(),
            fuel = lastFuel?.toLong(),
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

    // El servicio SOLO escribe en la base local. La red la maneja únicamente el SyncWorker:
    // antes el servicio y el worker podían iniciar el mismo viaje a la vez (→ 409 / duplicados).
    private suspend fun iniciarViajeLocal(carId: String, nivelNaftaInicial: Int) {
        val activeTrip = dao.getActiveTrip()
        if (activeTrip != null) {
            Log.i("OBD-DB", "Se retoma el viaje local ${activeTrip.localId}")
            return
        }

        val nuevo = Trip(
            localId = UUID.randomUUID().toString(),
            backendId = null,
            carId = carId,
            initialFuel = nivelNaftaInicial,
            startedAt = System.currentTimeMillis(),
            status = "ACTIVE",
            syncStatus = "PENDING_START"
        )
        dao.insertTrip(nuevo)
        Log.i("OBD-DB", "¡Nuevo viaje creado en SQLite! ID local: ${nuevo.localId}")
        SyncScheduler.requestSync(applicationContext)
    }

    private suspend fun finalizarViajeLocal(nivelNaftaFinal: Int?, tiempoFin: Long) {
        val filas = dao.closeActiveTrip(tiempoFin, nivelNaftaFinal)
        if (filas > 0) {
            Log.i("OBD-DB", "Viaje cerrado en SQLite local.")
            SyncScheduler.requestSync(applicationContext)
        }
    }
}
