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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    // --- RECONEXIÓN Y TIEMPO DE GRACIA (el umbral es TripGrace.TIEMPO_DE_GRACIA_MS) ---
    // El estado de la conexión se toca solo desde el hilo principal.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val conectarRunnable = Runnable { conectarBLE() }
    // MAC del ESP32 mientras Android lo ve presente; null si el auto desapareció.
    private var macActual: String? = null
    private var intentosReconexion = 0
    private var disconnectJob: Job? = null
    private var recuperacionJob: Job? = null
    // true mientras no llegan datos (todavía no conectó, o se cayó el enlace).
    @Volatile private var enlaceCaido = true
    // Momento del último paquete BLE recibido: es la hora real de fin si el viaje se cierra.
    // 0 = no hay datos de un viaje en curso.
    @Volatile private var ultimoDatoMs = 0L

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
        private const val RECONEXION_BASE_MS = 1_000L    // espera antes de conectar; se duplica en cada fallo...
        private const val RECONEXION_MAX_MS = 30_000L    // ...hasta este tope
    }

    // TODO: Mejorar lógica de detección de inicio real del auto
    @Volatile private var isTripActive = false

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        dao = db.obdDao()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()

        // Si el proceso murió con un viaje abierto y ya pasó el tiempo de gracia, se cierra
        // ahora (con la hora de su último dato) en vez de retomarlo como si fuera el mismo.
        recuperacionJob = serviceScope.launch { TripGrace.cerrarViajeVencido(applicationContext, dao) }

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

        // El tiempo de gracia NO se cancela acá: que Android vea al ESP32 no garantiza que
        // lleguen datos. Lo cancela el primer paquete recibido (alRecuperarEnlace).

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

        macActual = macAddress.uppercase()
        intentosReconexion = 0
        Log.i("OBD-C", "Antena ocupada leyendo aparición. Esperando 1 segundo...")
        // Retrasamos la conexión (para no saturar)
        programarConexion(RECONEXION_BASE_MS)
    }

    // se desconectó del ESP32 (auto apagado o fuera de rango)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i("OBD-C", "Auto apagado. Esperando tiempo de gracia...")

        macActual = null
        mainHandler.removeCallbacks(conectarRunnable)
        detenerTrackingGPS()
        alCaerEnlace()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(conectarRunnable)
        detenerTrackingGPS()
        desconectarBLE()
        // La espera en memoria muere con el servicio; el cierre queda en manos de WorkManager.
        disconnectJob?.cancel()
        registrarCorte()
        // Si Android mata el servicio, que no se pierda lo que estaba en RAM.
        val pendiente = extraerChunk()
        if (pendiente != null) {
            runBlocking(Dispatchers.IO) { persistirChunk(pendiente) }
        }
        super.onDestroy()
    }

    // Se perdió la conexión con el ESP32: error de GATT, desconexión o auto fuera de rango.
    private fun alCaerEnlace() {
        enlaceCaido = true
        desconectarBLE()

        // Lo que quedó en RAM se guarda YA, no al final del tiempo de gracia.
        guardarBufferEnDb()

        iniciarTiempoDeGracia()
    }

    // Volvieron a llegar datos antes de que venza el tiempo de gracia: el viaje sigue.
    private fun alRecuperarEnlace() {
        // Aviso repetido, o paquete tardío de una conexión que ya se cerró.
        if (!enlaceCaido || bluetoothGatt == null) return

        enlaceCaido = false
        intentosReconexion = 0
        disconnectJob?.cancel()
        disconnectJob = null
        guardarUltimoDato()
    }

    private fun iniciarTiempoDeGracia() {
        // Si ya está corriendo no se reinicia: no llegó ningún dato desde el primer corte,
        // así que el corte sigue siendo el mismo.
        if (disconnectJob?.isActive == true) return

        registrarCorte()

        // Se cuenta desde el último dato recibido, no desde que Android avisa del corte.
        val ultimo = ultimoDatoMs
        val espera = if (ultimo > 0) {
            (ultimo + TripGrace.TIEMPO_DE_GRACIA_MS - System.currentTimeMillis()).coerceAtLeast(0)
        } else {
            TripGrace.TIEMPO_DE_GRACIA_MS
        }

        disconnectJob = serviceScope.launch {
            delay(espera)

            // Una vez vencido no se cancela a medias: el cierre en la base y el estado en
            // memoria tienen que quedar coherentes.
            withContext(NonCancellable) {
                Log.i("OBD-C", "⏳ Tiempo de gracia expirado. Finalizando Viaje definitivamente...")
                TripGrace.cerrarViajeVencido(applicationContext, dao)
                isTripActive = false
                ultimoDatoMs = 0L
            }

            withContext(Dispatchers.Main) {
                // Si el auto sigue presente se sigue intentando reconectar: cuando vuelvan
                // los datos arranca un viaje nuevo.
                if (macActual == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf() // Apagamos el servicio de Android
                }
            }
        }
    }

    // Deja el corte asentado fuera de la memoria del servicio, para que el viaje se cierre
    // (con la hora correcta) aunque Android destruya el servicio durante la espera.
    private fun registrarCorte() {
        val corte = ultimoDatoMs
        if (corte == 0L) return
        TripGrace.guardarUltimoDato(applicationContext, corte, lastFuel)
        TripGrace.programarCierre(applicationContext, corte)
    }

    private fun guardarUltimoDato() {
        val ultimo = ultimoDatoMs
        if (ultimo > 0) TripGrace.guardarUltimoDato(applicationContext, ultimo, lastFuel)
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


    private fun programarConexion(esperaMs: Long) {
        mainHandler.removeCallbacks(conectarRunnable)
        mainHandler.postDelayed(conectarRunnable, esperaMs)
    }

    // Tras una caída del enlace: se reintenta mientras el auto siga presente, cada vez más
    // espaciado para no saturar la antena si el ESP32 no responde.
    private fun reintentarConexion() {
        if (macActual == null) return
        val espera = (RECONEXION_BASE_MS shl intentosReconexion.coerceAtMost(5))
            .coerceAtMost(RECONEXION_MAX_MS)
        intentosReconexion++
        Log.i("OBD-C", "Reintentando conexión BLE en $espera ms (intento $intentosReconexion)...")
        programarConexion(espera)
    }

    private fun conectarBLE() {
        val mac = macActual ?: return     // el auto desapareció mientras esperábamos
        if (bluetoothGatt != null) return // ya hay una conexión en curso

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            val device = adapter.getRemoteDevice(mac)

            Log.i("OBD-C", "Intentando dar la mano (GATT LE) al ESP32 ($mac)...")

            bluetoothGatt = device.connectGatt(
                this@ObdCompanionService,
                true,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (bluetoothGatt == null) {
                Log.e("OBD-C", "Android se negó a iniciar la conexión GATT.")
                reintentarConexion()
            }
        } catch (e: SecurityException) {
            Log.e("OBD-C", "🚨 FALTA PERMISO BLUETOOTH_CONNECT: ${e.message}")
        } catch (e: Exception) {
            Log.e("OBD-C", "Error fatal conectando BLE: ${e.message}")
        }
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
            // Este callback llega en un hilo de Binder; el estado se maneja en el principal.
            mainHandler.post {
                if (gatt !== bluetoothGatt) return@post // aviso tardío de una conexión ya descartada

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i("OBD-C", "GATT Conectado. Buscando servicios OBD...")
                    gatt.discoverServices()
                } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Un microcorte suele llegar como error (status 8 = timeout, 133). Antes
                    // se cerraba la conexión y no se volvía a intentar nunca más.
                    Log.w("OBD-C", "Enlace BLE caído (status=$status).")
                    alCaerEnlace()
                    reintentarConexion()
                }
            }
        }

        // Se ejecuta cuando Android termina de leer los UUIDs del ESP32
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(OBD_SERVICE_UUID)?.getCharacteristic(NOTIFY_CHARACTERISTIC_UUID)
            } else {
                null
            }

            if (characteristic != null) {
                Log.i("OBD-C", "Característica encontrada. Suscribiendo...")

                gatt.setCharacteristicNotification(characteristic, true)

                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                // Conectado pero sin datos no sirve: se empieza de nuevo.
                Log.e("OBD-C", "No se encontró la característica de notificación (status=$status)")
                mainHandler.post {
                    if (gatt !== bluetoothGatt) return@post
                    alCaerEnlace()
                    reintentarConexion()
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

        ultimoDatoMs = System.currentTimeMillis()
        if (enlaceCaido) mainHandler.post { alRecuperarEnlace() }

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
        // Mientras llegan datos, la marca en disco nunca queda más vieja que un chunk.
        guardarUltimoDato()
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
        // Primero termina la revisión de onCreate: un viaje vencido no se retoma.
        recuperacionJob?.join()

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
}
