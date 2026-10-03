package com.example.obd_app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Vacía la cola local hacia el backend, auto por auto, cada uno con su token de dispositivo.
 * Orden para cada auto: viajes (en orden cronológico) → telemetría.
 *
 * Reglas de error (lo importante es que un dato roto nunca bloquee la cola):
 * - 2xx                → listo, se borra / se marca.
 * - 401, o 403 con `car_not_accessible`
 *                      → el token de ESE auto ya no sirve. Se marca inválido, se avisa al
 *                        usuario y se sigue con los otros autos, SIN borrar nada. Al abrir la
 *                        app, Flutter crea un token nuevo y vuelve a encolar este worker.
 * - red, 408, 429, 5xx → Result.retry() con backoff exponencial.
 * - otro 4xx           → el servidor lo rechaza: se marca FAILED y se sigue con el resto.
 *
 * Reintentar es seguro: la telemetría es idempotente (duplicados = éxito) y el inicio de un
 * viaje también (`clientTripId`: repetirlo devuelve el mismo viaje).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Outcome { OK, RETRY, AUTH, REJECTED }

    /** El resultado de una llamada. [error] es el cuerpo del error, leído una sola vez. */
    private data class Llamada<T>(val outcome: Outcome, val response: Response<T>?, val error: String = "")

    companion object {
        private const val TAG = "OBD-SYNC"
        private const val MAX_RONDAS_CHUNKS = 20 // 20 x 50 chunks por auto en cada corrida, como mucho

        // Claves de una muestra que ya tienen su lugar en ReadingPayload.
        private val CLAVES_CON_CAMPO = setOf("t", "la", "lo", "s", "f", "m")

        // El worker único y el periódico podrían coincidir: que no corran a la vez.
        private val mutex = Mutex()
    }

    // Formato del backend ("2026-09-10T14:56:13Z"). Precisión de segundos: el servidor
    // guarda una lectura por (auto, segundo).
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun doWork(): Result = mutex.withLock {
        val dao = AppDatabase.getDatabase(applicationContext).obdDao()

        // Red de seguridad: un viaje que quedó abierto sin que nadie programara su cierre
        // (el proceso murió en pleno viaje) se cierra acá.
        TripGrace.cerrarViajesVencidos(applicationContext, dao)

        val session = NativeSession.read(applicationContext)
        if (session == null) {
            Log.w(TAG, "Flutter todavía no configuró el servidor. Los datos quedan en cola.")
            return@withLock Result.success()
        }

        val api = ApiClient.service(session.baseUrl)
        var reintentar = false

        for (carId in dao.getCarIdsWithPendingWork()) {
            val credencial = CredentialStore.get(applicationContext, carId)
            if (credencial == null || credencial.invalid) {
                Log.w(TAG, "El auto $carId no tiene un token válido. Sus datos quedan en cola hasta abrir la app.")
                continue
            }
            val auth = "Device ${credencial.token}"

            Log.i(TAG, "🔄 Iniciando sincronización del auto $carId...")

            val viajes = sincronizarViajes(api, auth, dao, carId, session.userId)
            if (viajes == Outcome.AUTH) {
                credencialRechazada(credencial)
                continue
            }

            // La telemetría no depende de los viajes: se sube aunque alguno haya fallado.
            val telemetria = subirTelemetria(api, auth, dao, carId)
            if (telemetria == Outcome.AUTH) {
                credencialRechazada(credencial)
                continue
            }

            if (Outcome.RETRY in listOf(viajes, telemetria)) reintentar = true
        }

        if (reintentar) Result.retry() else Result.success()
    }

    // El servidor rechazó el token de ese auto. Los otros autos siguen subiendo.
    private fun credencialRechazada(credencial: DeviceCredential) {
        Log.w(TAG, "El token del auto ${credencial.carId} fue rechazado. Sus datos quedan en cola hasta abrir la app.")
        CredentialStore.markInvalid(applicationContext, credencial.carId)
        SyncAlerts.mostrarReactivacion(applicationContext, credencial.carId, credencial.carName)
    }

    // ---------------------------------------------------------------- VIAJES

    /**
     * Cada viaje se inicia y, si ya terminó, se cierra antes de pasar al siguiente: el
     * servidor admite un solo viaje abierto por auto, así que con dos en cola el segundo
     * chocaría (409) con el primero si este no se cerró antes.
     */
    private suspend fun sincronizarViajes(
        api: ObdApiService, auth: String, dao: ObdDao, carId: String, myUserId: String?
    ): Outcome {
        for (pendiente in dao.getTripsToSync(carId)) {
            if (pendiente.backendId == null) {
                val inicio = iniciarViaje(api, auth, dao, pendiente, myUserId)
                if (inicio != Outcome.OK) return inicio
            }

            // Se relee: el servicio pudo haberlo cerrado mientras tanto.
            val viaje = dao.getTrip(pendiente.localId) ?: continue
            val backendId = viaje.backendId ?: continue // el servidor lo rechazó (FAILED)

            // Sigue abierto en el servidor: los siguientes no se pueden iniciar todavía.
            if (viaje.syncStatus != "PENDING_FINISH") return Outcome.OK

            val cierre = cerrarViaje(api, auth, dao, viaje, backendId)
            if (cierre != Outcome.OK) return cierre
        }
        return Outcome.OK
    }

    /** OK también cuando el servidor lo rechazó (queda FAILED y se sigue con el resto). */
    private suspend fun iniciarViaje(
        api: ObdApiService, auth: String, dao: ObdDao, viaje: Trip, myUserId: String?
    ): Outcome {
        val (outcome, response, error) = llamar {
            api.startTrip(
                auth,
                StartTripRequest(
                    carId = viaje.carId,
                    initialFuel = viaje.initialFuel,
                    clientTripId = viaje.localId,
                    startedAt = isoFormatter.format(Date(viaje.startedAt)),
                )
            )
        }

        return when {
            outcome == Outcome.OK -> {
                val backendId = response?.body()?.id ?: return Outcome.RETRY
                dao.markTripStarted(viaje.localId, backendId)
                Log.i(TAG, "✅ Viaje iniciado en el servidor. ID: $backendId")
                Outcome.OK
            }
            // 409 = el auto tiene OTRO viaje abierto (un reintento del mismo devuelve 200).
            response?.code() == 409 -> adoptarViajeActivo(api, auth, dao, viaje, myUserId)
            outcome == Outcome.AUTH || outcome == Outcome.RETRY -> outcome
            else -> {
                // Ej.: 400 porque startedAt tiene más de 30 días.
                Log.e(TAG, "Servidor rechazó el viaje ${viaje.localId}: ${response?.code()} $error")
                dao.setTripSyncStatus(viaje.localId, "FAILED")
                Outcome.OK
            }
        }
    }

    private suspend fun adoptarViajeActivo(
        api: ObdApiService, auth: String, dao: ObdDao, viaje: Trip, myUserId: String?
    ): Outcome {
        val (outcome, response) = llamar { api.getActiveTrip(auth, viaje.carId) }
        if (outcome == Outcome.AUTH || outcome == Outcome.RETRY) return outcome
        if (outcome == Outcome.REJECTED) {
            dao.setTripSyncStatus(viaje.localId, "FAILED")
            return Outcome.OK
        }

        val activo = response?.body()
            ?: return Outcome.RETRY // 204: el viaje se cerró entre medio; se reintenta el inicio

        val activoId = activo.id
        val clientTripId = activo.clientTripId
        val esOtroViajeLocal = clientTripId != null && clientTripId != viaje.localId && dao.getTrip(clientTripId) != null

        when {
            // Un viaje anterior de este celular quedó abierto en el servidor: hay que esperar
            // a que se cierre, no adoptarlo (serían dos viajes distintos).
            esOtroViajeLocal -> {
                Log.w(TAG, "El auto sigue con el viaje local $clientTripId abierto en el servidor. Se reintenta ${viaje.localId}.")
                return Outcome.RETRY
            }
            // Iniciado por este usuario desde la app (sin clientTripId) o desde otro celular.
            activoId != null && myUserId != null && activo.driverId == myUserId -> {
                dao.markTripStarted(viaje.localId, activoId)
                Log.i(TAG, "✅ Viaje ya abierto por este usuario: se adopta $activoId")
            }
            else -> {
                Log.w(TAG, "El auto está en viaje con otro conductor. Viaje local ${viaje.localId} → FAILED")
                dao.setTripSyncStatus(viaje.localId, "FAILED")
            }
        }
        return Outcome.OK
    }

    /** OK también cuando el servidor lo rechazó (queda FAILED). */
    private suspend fun cerrarViaje(
        api: ObdApiService, auth: String, dao: ObdDao, viaje: Trip, backendId: String
    ): Outcome {
        val (outcome, response, error) = llamar {
            api.finishTrip(
                auth,
                backendId,
                FinishTripRequest(
                    tripFinalFuel = viaje.finalFuel,
                    endedAt = viaje.endedAt?.let { isoFormatter.format(Date(it)) },
                )
            )
        }

        return when {
            // 409 = ya estaba cerrado (un intento anterior llegó pero la respuesta no)
            outcome == Outcome.OK || response?.code() == 409 -> {
                dao.setTripSyncStatus(viaje.localId, "COMPLETED")
                Log.i(TAG, "✅ Viaje $backendId cerrado en el servidor.")
                Outcome.OK
            }
            outcome == Outcome.AUTH || outcome == Outcome.RETRY -> outcome
            else -> {
                Log.e(TAG, "Servidor rechazó el cierre de $backendId: ${response?.code()} $error")
                dao.setTripSyncStatus(viaje.localId, "FAILED")
                Outcome.OK
            }
        }
    }

    // ---------------------------------------------------------------- TELEMETRÍA

    private suspend fun subirTelemetria(api: ObdApiService, auth: String, dao: ObdDao, carId: String): Outcome {
        repeat(MAX_RONDAS_CHUNKS) {
            val chunks = dao.getPendingChunksForCar(carId)
            if (chunks.isEmpty()) return Outcome.OK

            for (chunk in chunks) {
                val request = try {
                    armarLote(chunk)
                } catch (e: JSONException) {
                    Log.e(TAG, "Chunk ${chunk.id} corrupto: ${e.message}")
                    dao.markChunkFailed(chunk.id)
                    continue
                }

                if (request.readings.isEmpty()) {
                    dao.deleteChunks(listOf(chunk.id))
                    continue
                }

                val (outcome, response, error) = llamar { api.uploadTelemetry(auth, request) }
                when (outcome) {
                    Outcome.OK -> dao.deleteChunks(listOf(chunk.id))
                    Outcome.AUTH -> return Outcome.AUTH
                    Outcome.RETRY -> {
                        dao.incrementChunkRetry(chunk.id)
                        return Outcome.RETRY
                    }
                    Outcome.REJECTED -> {
                        Log.e(TAG, "Servidor rechazó chunk ${chunk.id}: ${response?.code()} $error")
                        dao.markChunkFailed(chunk.id)
                    }
                }
            }
            Log.i(TAG, "🚀 Se procesaron ${chunks.size} chunks de telemetría")
        }
        return Outcome.OK // quedan más: siguen en la próxima corrida
    }

    private fun armarLote(chunk: TelemetryChunk): TelemetryBatchRequest {
        val json = JSONObject(chunk.payload)
        val t0 = json.getLong("t0")
        val samples = json.getJSONArray("samples")

        val segundosVistos = HashSet<Long>()
        val readings = ArrayList<ReadingPayload>(samples.length())

        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            val timestamp = t0 + s.getLong("t")

            // El servidor es único por (auto, segundo): no mandar dos del mismo segundo.
            if (!segundosVistos.add(timestamp / 1000)) continue

            val tienePosicion = s.has("la") && s.has("lo")
            readings.add(
                ReadingPayload(
                    recordedAt = isoFormatter.format(Date(timestamp)),
                    latitude = if (tienePosicion) s.getDouble("la") else null,
                    longitude = if (tienePosicion) s.getDouble("lo") else null,
                    speed = if (s.has("s")) s.getInt("s") else null,
                    fuelLevel = if (s.has("f")) s.getInt("f") else null,
                    mileage = if (s.has("m")) s.getInt("m") else null,
                    raw = extras(s),
                )
            )
        }
        return TelemetryBatchRequest(chunk.carId, readings)
    }

    // Lo que no tiene campo propio en el servidor viaja en `raw`, con su clave como nombre:
    // una señal nueva en el perfil de lectura no obliga a tocar este worker.
    private fun extras(muestra: JSONObject): Map<String, Any>? {
        val extras = HashMap<String, Any>()
        for (clave in muestra.keys()) {
            if (clave in CLAVES_CON_CAMPO) continue
            // "r": las RPM en los chunks que guardó la versión anterior de la app.
            extras[if (clave == "r") "rpm" else clave] = muestra.get(clave)
        }
        return extras.ifEmpty { null }
    }

    // ---------------------------------------------------------------- HELPERS

    private suspend fun <T> llamar(block: suspend () -> Response<T>): Llamada<T> =
        try {
            val response = block()
            // El cuerpo del error se puede leer una sola vez: se guarda para clasificar y loguear.
            val error = if (response.isSuccessful) "" else errorBody(response)
            Llamada(clasificar(response.code(), error), response, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Error de red: ${e.message}")
            Llamada(Outcome.RETRY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado: ${e.message}")
            Llamada(Outcome.RETRY, null)
        }

    private fun clasificar(code: Int, error: String): Outcome = when {
        code in 200..299 -> Outcome.OK
        code == 401 -> Outcome.AUTH
        code == 403 -> {
            val razon = razon(error)
            // El usuario perdió acceso al auto: no es un dato roto, es el token. Al abrir la
            // app la reconciliación decide (token nuevo, o desvincular si ya no tiene acceso).
            if (razon == "car_not_accessible") {
                Outcome.AUTH
            } else {
                Log.e(TAG, "403 ($razon): el token no alcanza a ese auto o endpoint. Es un bug del cliente.")
                Outcome.REJECTED
            }
        }
        code == 408 || code == 429 || code >= 500 -> Outcome.RETRY
        else -> Outcome.REJECTED
    }

    // El `reason` del ProblemDetail del backend (ej. "car_not_accessible", "token_revoked").
    private fun razon(error: String): String? =
        try { JSONObject(error).optString("reason").ifEmpty { null } } catch (e: JSONException) { null }

    private fun errorBody(response: Response<*>): String =
        try { response.errorBody()?.string()?.take(300) ?: "" } catch (e: Exception) { "" }
}
