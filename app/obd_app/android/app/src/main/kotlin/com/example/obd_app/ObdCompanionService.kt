package com.example.obd_app

import android.app.Notification
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
import java.util.concurrent.ConcurrentHashMap
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

//Foreground Service que se ejecuta en segundo plano cuando algún auto está encendido y conectado a su ESP32
class ObdCompanionService : CompanionDeviceService() {
    private val NOTIFICATION_ID = 1996
    private val CHANNEL_ID = "OBD_TRIP_CHANNEL"

    // --- GPS (compartido: el celular está en un solo lugar, sea cual sea el auto) ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLng: Double? = null

    // --- BLUETOOTH ---
    private val OBD_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NOTIFY_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- SESIONES ---
    // Una por ESP32 (clave: MAC en mayúsculas). Cada auto tiene su conexión, su viaje y su
    // buffer; lo único compartido es el GPS y la notificación.
    // El mapa y el estado de conexión de cada sesión se tocan solo desde el hilo principal.
    private val sesiones = ConcurrentHashMap<String, SesionAuto>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var enPrimerPlano = false

    // --- BASE DE DATOS ---
    private lateinit var db: AppDatabase
    private lateinit var dao: ObdDao
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recuperacionJob: Job? = null

    companion object {
        private const val MUESTREO_MIN_MS = 1_000L       // 1 muestra por segundo
        private const val CHUNK_MAX_MUESTRAS = 60        // se guarda al llegar a 60 muestras...
        private const val CHUNK_MAX_EDAD_MS = 15_000L    // ...o a los 15 s, lo que pase primero
        private const val RECONEXION_BASE_MS = 1_000L    // espera antes de conectar; se duplica en cada fallo...
        private const val RECONEXION_MAX_MS = 30_000L    // ...hasta este tope

        @Volatile private var instancia: ObdCompanionService? = null

        /**
         * Avisa que cambió la tabla de asociaciones (se vinculó, reasignó o desvinculó un
         * ESP32). Si ese ESP32 está conectado en este momento, sus datos pasan a atribuirse
         * al auto nuevo sin esperar a que el auto se apague y vuelva a aparecer.
         */
        fun asociacionesCambiaron() {
            instancia?.refrescarAsociaciones()
        }

        /** Están llegando datos del ESP32 de ese auto. */
        fun recibeDatosDe(carId: String): Boolean =
            instancia?.sesiones?.values?.any { it.carId == carId && !it.enlaceCaido } == true

        /** Android ve al ESP32 de ese auto (aunque todavía no haya conexión ni datos). */
        fun vePresenteA(carId: String): Boolean =
            instancia?.sesiones?.values?.any { it.carId == carId && it.presente } == true
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        dao = db.obdDao()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        crearCanalNotificacion()

        // Si el proceso murió con viajes abiertos y ya pasó el tiempo de gracia, se cierran
        // ahora (con la hora de su último dato) en vez de retomarlos como si fueran los mismos.
        recuperacionJob = serviceScope.launch { TripGrace.cerrarViajesVencidos(applicationContext, dao) }

        // Red de seguridad: si algún aviso de sync se perdió, cada 15 min se reintenta.
        SyncScheduler.schedulePeriodic(this)

        instancia = this
    }

    // Se ejecuta cuando Android detecta que un ESP32 está en el aire (auto encendido)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
        if (mac == null) {
            Log.e("OBD-C", "La asociación no tiene MAC. No se puede conectar.")
            return
        }
        Log.i("OBD-C", "Auto detectado ($mac). Iniciando Viaje...")

        // Si ya había una sesión para esta MAC (el auto volvió dentro del tiempo de gracia)
        // se retoma: conserva su viaje. El tiempo de gracia NO se cancela acá: que Android vea
        // al ESP32 no garantiza que lleguen datos. Lo cancela el primer paquete recibido.
        val sesion = sesiones.getOrPut(mac) { SesionAuto(mac) }
        sesion.presente = true
        sesion.intentosReconexion = 0

        actualizarCompartidos()

        // El auto sale de la MAC. Se busca antes de conectar para que ningún dato quede sin dueño.
        serviceScope.launch {
            val carId = dao.getAssociationByMac(mac)?.carId
            withContext(Dispatchers.Main) {
                sesion.asignarAuto(carId)
                sesion.avisarEstado() // detectado: todavía conectando
                if (carId == null) {
                    Log.w("OBD-C", "El ESP32 $mac no está vinculado a ningún auto. Se muestran datos en vivo pero no se guardan.")
                }
                if (!sesion.presente) return@withContext // desapareció mientras se buscaba

                Log.i("OBD-C", "Antena ocupada leyendo aparición. Esperando 1 segundo...")
                // Retrasamos la conexión (para no saturar)
                sesion.programarConexion(RECONEXION_BASE_MS)
            }
        }
    }

    // se desconectó de un ESP32 (auto apagado o fuera de rango)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        val mac = associationInfo.deviceMacAddress?.toString()?.uppercase() ?: return
        Log.i("OBD-C", "Auto apagado ($mac). Esperando tiempo de gracia...")

        val sesion = sesiones[mac]
        if (sesion == null) {
            // "Desapareció" sin haber aparecido (servicio recreado): no hay nada que cerrar acá.
            apagarSiNoQuedanSesiones()
            return
        }

        sesion.presente = false
        mainHandler.removeCallbacks(sesion.conectarRunnable)
        sesion.alCaerEnlace()
    }

    override fun onDestroy() {
        instancia = null
        detenerTrackingGPS()

        // Si Android mata el servicio, que no se pierda lo que estaba en RAM de ningún auto.
        val pendientes = sesiones.values.mapNotNull { sesion ->
            mainHandler.removeCallbacks(sesion.conectarRunnable)
            sesion.desconectarBLE()
            // La espera en memoria muere con el servicio; el cierre queda en manos de WorkManager.
            sesion.disconnectJob?.cancel()
            sesion.registrarCorte()
            sesion.extraerChunk()?.let { sesion to it }
        }
        if (pendientes.isNotEmpty()) {
            runBlocking(Dispatchers.IO) {
                for ((sesion, chunk) in pendientes) sesion.persistirChunk(chunk)
            }
        }
        val autos = sesiones.values.mapNotNull { it.carId }
        sesiones.clear()
        // Sin servicio ya no hay conexión; los que tengan un viaje abierto quedan "en gracia".
        for (carId in autos) ConnectionStatus.avisar(applicationContext, carId)
        super.onDestroy()
    }

    private fun refrescarAsociaciones() {
        serviceScope.launch {
            for (sesion in sesiones.values) {
                val carId = dao.getAssociationByMac(sesion.mac)?.carId
                withContext(Dispatchers.Main) { sesion.asignarAuto(carId) }
            }
        }
    }

    // --- LO COMPARTIDO ENTRE SESIONES: GPS, NOTIFICACIÓN Y VIDA DEL SERVICIO ---

    // El GPS corre mientras haya algún auto presente; la notificación dice en qué están.
    private fun actualizarCompartidos() {
        val presentes = sesiones.values.count { it.presente }
        if (presentes > 0) {
            // iniciamos (o actualizamos) el Foreground Service con una notificación fija
            startForeground(NOTIFICATION_ID, crearNotificacion())
            enPrimerPlano = true
            iniciarTrackingGPS()
        } else {
            detenerTrackingGPS()
            if (enPrimerPlano) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, crearNotificacion())
            }
        }
    }

    private fun crearNotificacion(): Notification {
        val conectados = sesiones.values.count { !it.enlaceCaido }
        val texto = when {
            conectados == 1 -> "Conectado al auto."
            conectados > 1 -> "Conectado a $conectados autos."
            sesiones.values.any { it.presente } -> "Auto detectado. Conectando..."
            else -> "Auto desconectado. Esperando que vuelva..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO Cambiar por otro logo
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun apagarSiNoQuedanSesiones() {
        if (sesiones.isNotEmpty()) return
        detenerTrackingGPS()
        stopForeground(STOP_FOREGROUND_REMOVE)
        enPrimerPlano = false
        stopSelf() // Apagamos el servicio de Android
    }

    private fun iniciarTrackingGPS() {
        if (locationCallback != null) return // ya estaba activo (otro auto, o reconexión dentro del tiempo de gracia)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateDistanceMeters(15f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastLat = location.latitude
                lastLng = location.longitude
                Log.i("OBD-C", "NUEVO PUNTO -> GPS: [$lastLat, $lastLng]")
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

    /**
     * Todo lo que es propio de UN auto: la conexión con su ESP32, sus últimos valores, su
     * viaje, su tiempo de gracia y su buffer. Con dos autos encendidos hay dos de estas.
     */
    private inner class SesionAuto(val mac: String) {
        // Auto al que se atribuyen los datos; sale de la tabla de asociaciones por la MAC.
        // null = ESP32 sin vincular: se muestra en vivo pero no se graba.
        @Volatile var carId: String? = null

        // --- VARIABLES DE ESTADO ACTUAL ---
        // null = todavía no llegó ese dato. Así no se manda un 0 falso al servidor
        // (antes la nafta arrancaba en 0 hasta que llegaba el primer paquete 0x02).
        @Volatile var lastSpeed: Int? = null
        @Volatile var lastRpm: Int? = null
        @Volatile var lastFuel: Int? = null

        // --- RECONEXIÓN Y TIEMPO DE GRACIA (el umbral es TripGrace.TIEMPO_DE_GRACIA_MS) ---
        var bluetoothGatt: BluetoothGatt? = null
        val conectarRunnable = Runnable { conectarBLE() }
        // true mientras Android ve al ESP32; false si el auto desapareció.
        @Volatile var presente = false
        var intentosReconexion = 0
        var disconnectJob: Job? = null
        // true mientras no llegan datos (todavía no conectó, o se cayó el enlace).
        @Volatile var enlaceCaido = true
        // Momento del último paquete BLE recibido: es la hora real de fin si el viaje se cierra.
        // 0 = no hay datos de un viaje en curso.
        @Volatile var ultimoDatoMs = 0L

        // TODO: Mejorar lógica de detección de inicio real del auto
        @Volatile var isTripActive = false

        // El BLE manda datos cada ~20 ms, pero el backend guarda UNA lectura por segundo por auto:
        // guardar más solo genera duplicados que el servidor descarta.
        private val bufferLock = Any()
        private val ramBuffer = mutableListOf<JSONObject>()
        private var chunkStartTime = 0L
        private var ultimaMuestraMs = 0L

        // El ESP32 pasó a ser de otro auto (o de ninguno). Lo que ya se leyó queda para el
        // auto anterior, y su viaje se cierra solo al vencer el tiempo de gracia.
        fun asignarAuto(nuevo: String?) {
            if (nuevo == carId) return
            val anterior = carId

            registrarCorte()
            val pendiente = synchronized(bufferLock) {
                val chunk = carId?.let { extraerChunkLocked(it) }
                carId = nuevo
                chunk
            }
            pendiente?.let { serviceScope.launch { persistirChunk(it) } }
            isTripActive = false

            ConnectionStatus.avisar(applicationContext, anterior)
            avisarEstado()
        }

        // La app muestra en qué está la conexión de cada auto: hay que avisarle cada vez que cambia.
        fun avisarEstado() {
            ConnectionStatus.avisar(applicationContext, carId)
        }

        // Se perdió la conexión con el ESP32: error de GATT, desconexión o auto fuera de rango.
        fun alCaerEnlace() {
            enlaceCaido = true
            desconectarBLE()

            // Lo que quedó en RAM se guarda YA, no al final del tiempo de gracia.
            guardarBufferEnDb()

            iniciarTiempoDeGracia()

            avisarEstado()
            actualizarCompartidos()
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

            avisarEstado()
            actualizarCompartidos()
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
                    Log.i("OBD-C", "⏳ Tiempo de gracia expirado ($mac). Finalizando Viaje definitivamente...")
                    carId?.let { TripGrace.cerrarViajeVencido(applicationContext, dao, it) }
                    isTripActive = false
                    ultimoDatoMs = 0L
                    avisarEstado()
                }

                withContext(Dispatchers.Main) {
                    // Si el auto sigue presente se sigue intentando reconectar: cuando vuelvan
                    // los datos arranca un viaje nuevo. Si no, la sesión se termina y, si era
                    // la última, se apaga el servicio.
                    if (!presente) {
                        sesiones.remove(mac, this@SesionAuto)
                        apagarSiNoQuedanSesiones()
                    }
                }
            }
        }

        // Deja el corte asentado fuera de la memoria del servicio, para que el viaje se cierre
        // (con la hora correcta) aunque Android destruya el servicio durante la espera.
        fun registrarCorte() {
            val carId = carId ?: return
            val corte = ultimoDatoMs
            if (corte == 0L) return
            TripGrace.guardarUltimoDato(applicationContext, carId, corte, lastFuel)
            TripGrace.programarCierre(applicationContext, carId, corte)
        }

        private fun guardarUltimoDato() {
            val carId = carId ?: return
            val ultimo = ultimoDatoMs
            if (ultimo > 0) TripGrace.guardarUltimoDato(applicationContext, carId, ultimo, lastFuel)
        }

        fun programarConexion(esperaMs: Long) {
            mainHandler.removeCallbacks(conectarRunnable)
            mainHandler.postDelayed(conectarRunnable, esperaMs)
        }

        // Tras una caída del enlace: se reintenta mientras el auto siga presente, cada vez más
        // espaciado para no saturar la antena si el ESP32 no responde.
        private fun reintentarConexion() {
            if (!presente) return
            val espera = (RECONEXION_BASE_MS shl intentosReconexion.coerceAtMost(5))
                .coerceAtMost(RECONEXION_MAX_MS)
            intentosReconexion++
            Log.i("OBD-C", "Reintentando conexión BLE con $mac en $espera ms (intento $intentosReconexion)...")
            programarConexion(espera)
        }

        private fun conectarBLE() {
            if (!presente) return             // el auto desapareció mientras esperábamos
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

        fun desconectarBLE() {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }

        // Escucha cuando el ESP32 acepta la conexión, busca los servicios y se suscribe a las notificaciones.
        // Cada sesión tiene el suyo: así se sabe de qué auto viene cada dato.
        private val gattCallback = object : BluetoothGattCallback() {

            // Se ejecuta cuando el ESP32 acepta o rechaza la conexión
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                // Este callback llega en un hilo de Binder; el estado se maneja en el principal.
                mainHandler.post {
                    if (gatt !== bluetoothGatt) return@post // aviso tardío de una conexión ya descartada

                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i("OBD-C", "GATT Conectado ($mac). Buscando servicios OBD...")
                        gatt.discoverServices()
                    } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // Un microcorte suele llegar como error (status 8 = timeout, 133). Antes
                        // se cerraba la conexión y no se volvía a intentar nunca más.
                        Log.w("OBD-C", "Enlace BLE caído con $mac (status=$status).")
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

            // La UI en vivo se actualiza siempre, esté o no vinculado a un auto.
            notificarAFlutter()

            val carId = carId ?: return

            // El viaje arranca recién cuando se conoce la nafta (primer paquete 0x02), para no
            // registrar un nivel inicial de 0 que no es real.
            val nafta = lastFuel
            if (!isTripActive && nafta != null) {
                isTripActive = true
                serviceScope.launch { iniciarViajeLocal(carId, nafta) }
            }

            guardarTelemetriaLocal()
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
            val ahora = System.currentTimeMillis()

            val chunkListo: TelemetryChunk? = synchronized(bufferLock) {
                // Se lee con el lock tomado: si el ESP32 se reasigna a otro auto, ninguna
                // muestra del auto anterior termina en un chunk del nuevo.
                val carId = carId ?: return
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

        fun extraerChunk(): TelemetryChunk? = synchronized(bufferLock) {
            carId?.let { extraerChunkLocked(it) }
        }

        private fun guardarBufferEnDb() {
            extraerChunk()?.let { serviceScope.launch { persistirChunk(it) } }
        }

        suspend fun persistirChunk(chunk: TelemetryChunk) {
            dao.insertChunk(chunk)
            // Mientras llegan datos, la marca en disco nunca queda más vieja que un chunk.
            if (chunk.carId == carId) guardarUltimoDato()
            // Le avisamos al WorkManager que hay datos nuevos (sube solo cuando haya internet).
            SyncScheduler.requestSync(applicationContext)
        }

        private fun notificarAFlutter() {
            val evento = TelemetryEvent(
                speed = lastSpeed?.toLong(),
                rpm = lastRpm?.toLong(),
                fuel = lastFuel?.toLong(),
                lat = lastLat,
                lng = lastLng,
                carId = carId
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

            val activeTrip = dao.getActiveTrip(carId)
            if (activeTrip != null) {
                Log.i("OBD-DB", "Se retoma el viaje local ${activeTrip.localId} (auto $carId)")
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
            Log.i("OBD-DB", "¡Nuevo viaje creado en SQLite! ID local: ${nuevo.localId} (auto $carId)")
            SyncScheduler.requestSync(applicationContext)
        }
    }
}
