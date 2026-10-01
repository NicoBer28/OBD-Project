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
 * Vacía la cola local hacia el backend, auto por auto. Orden para cada auto: iniciar
 * viajes → telemetría → cerrar viajes.
 *
 * Reglas de error (lo importante es que un dato roto nunca bloquee la cola):
 * - 2xx                → listo, se borra / se marca.
 * - 401                → token vencido. Se corta SIN borrar nada; sube cuando Flutter mande
 *                        un token nuevo (eso vuelve a encolar este worker). Corta la corrida
 *                        entera: el token es del usuario, el mismo para todos sus autos.
 * - red, 408, 429, 5xx → Result.retry() con backoff exponencial.
 * - otro 4xx           → el servidor lo rechaza: se marca FAILED y se sigue con el resto.
 *
 * Reintentar es seguro: la telemetría es idempotente (duplicados = éxito) y los conflictos
 * de viajes (409) se resuelven abajo.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Outcome { OK, RETRY, AUTH, REJECTED }

    companion object {
        private const val TAG = "OBD-SYNC"
        private const val MAX_RONDAS_CHUNKS = 20 // 20 x 50 chunks por auto en cada corrida, como mucho

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
            Log.w(TAG, "Flutter todavía no entregó la sesión. Los datos quedan en cola.")
            return@withLock Result.success()
        }

        val api = ApiClient.service(session.baseUrl)
        var reintentar = false

        for (carId in dao.getCarIdsWithPendingWork()) {
            val auth = authHeaderFor(carId)
            if (auth == null) {
                Log.w(TAG, "No hay token para subir. Los datos quedan en cola.")
                return@withLock Result.success()
            }

            Log.i(TAG, "🔄 Iniciando sincronización del auto $carId...")

            val inicio = iniciarViajes(api, auth, dao, carId, session.userId)
            if (inicio == Outcome.AUTH) return@withLock tokenVencido()

            // La telemetría no depende de los viajes: se sube aunque el inicio haya fallado.
            val telemetria = subirTelemetria(api, auth, dao, carId)
            if (telemetria == Outcome.AUTH) return@withLock tokenVencido()

            val cierre = cerrarViajes(api, auth, dao, carId)
            if (cierre == Outcome.AUTH) return@withLock tokenVencido()

            if (Outcome.RETRY in listOf(inicio, telemetria, cierre)) reintentar = true
        }

        if (reintentar) Result.retry() else Result.success()
    }

    // Con qué credencial se suben los datos de [carId]. Hoy es el JWT del usuario, que sirve
    // para todos sus autos. FASE 2: pasa a ser el token de dispositivo de ese auto ("Device …").
    private fun authHeaderFor(carId: String): String? {
        val token = NativeSession.read(applicationContext)?.accessToken ?: return null
        return "Bearer $token"
    }

    // Se devuelve success para no reintentar en loop con un token que no sirve. Cuando
    // Flutter mande uno nuevo, MainActivity vuelve a encolar este worker.
    private fun tokenVencido(): Result {
        Log.w(TAG, "Token vencido o rechazado (401). Los datos quedan en cola hasta tener uno nuevo.")
        return Result.success()
    }

    // ---------------------------------------------------------------- VIAJES: INICIO

    private suspend fun iniciarViajes(
        api: ObdApiService, auth: String, dao: ObdDao, carId: String, myUserId: String?
    ): Outcome {
        for (viaje in dao.getTripsToStart(carId)) {
            val (outcome, response) = llamar {
                api.startTrip(auth, StartTripRequest(viaje.carId, viaje.initialFuel))
            }

            when {
                outcome == Outcome.OK -> {
                    val backendId = response?.body()?.id ?: return Outcome.RETRY
                    dao.markTripStarted(viaje.localId, backendId)
                    Log.i(TAG, "✅ Viaje iniciado en el servidor. ID: $backendId")
                }
                // 409 = el auto ya tiene un viaje abierto. Puede ser nuestro (la respuesta de
                // un intento anterior se perdió, o se inició a mano desde la app) o de otro.
                response?.code() == 409 -> {
                    val r = adoptarViajeActivo(api, auth, dao, viaje, myUserId)
                    if (r == Outcome.AUTH || r == Outcome.RETRY) return r
                }
                outcome == Outcome.AUTH || outcome == Outcome.RETRY -> return outcome
                else -> {
                    Log.e(TAG, "Servidor rechazó el viaje ${viaje.localId}: ${response?.code()} ${errorBody(response)}")
                    dao.setTripSyncStatus(viaje.localId, "FAILED")
                }
            }
        }
        return Outcome.OK
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
        if (activoId != null && myUserId != null && activo.driverId == myUserId) {
            dao.markTripStarted(viaje.localId, activoId)
            Log.i(TAG, "✅ Viaje ya abierto por este usuario: se adopta $activoId")
        } else {
            // TODO (Fase 2, backend): con clientTripId este caso deja de ser ambiguo.
            Log.w(TAG, "El auto está en viaje con otro conductor. Viaje local ${viaje.localId} → FAILED")
            dao.setTripSyncStatus(viaje.localId, "FAILED")
        }
        return Outcome.OK
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

                val (outcome, response) = llamar { api.uploadTelemetry(auth, request) }
                when (outcome) {
                    Outcome.OK -> dao.deleteChunks(listOf(chunk.id))
                    Outcome.AUTH -> return Outcome.AUTH
                    Outcome.RETRY -> {
                        dao.incrementChunkRetry(chunk.id)
                        return Outcome.RETRY
                    }
                    Outcome.REJECTED -> {
                        Log.e(TAG, "Servidor rechazó chunk ${chunk.id}: ${response?.code()} ${errorBody(response)}")
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
                    raw = if (s.has("r")) mapOf("rpm" to s.getInt("r")) else null,
                )
            )
        }
        return TelemetryBatchRequest(chunk.carId, readings)
    }

    // ---------------------------------------------------------------- VIAJES: FIN

    private suspend fun cerrarViajes(api: ObdApiService, auth: String, dao: ObdDao, carId: String): Outcome {
        for (viaje in dao.getTripsToFinish(carId)) {
            val backendId = viaje.backendId ?: continue
            val (outcome, response) = llamar {
                api.finishTrip(auth, backendId, FinishTripRequest(tripFinalFuel = viaje.finalFuel))
            }

            when {
                // 409 = ya estaba cerrado (un intento anterior llegó pero la respuesta no)
                outcome == Outcome.OK || response?.code() == 409 -> {
                    dao.setTripSyncStatus(viaje.localId, "COMPLETED")
                    Log.i(TAG, "✅ Viaje $backendId cerrado en el servidor.")
                }
                outcome == Outcome.AUTH || outcome == Outcome.RETRY -> return outcome
                else -> {
                    Log.e(TAG, "Servidor rechazó el cierre de $backendId: ${response?.code()} ${errorBody(response)}")
                    dao.setTripSyncStatus(viaje.localId, "FAILED")
                }
            }
        }
        return Outcome.OK
    }

    // ---------------------------------------------------------------- HELPERS

    private suspend fun <T> llamar(block: suspend () -> Response<T>): Pair<Outcome, Response<T>?> =
        try {
            val response = block()
            clasificar(response.code()) to response
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Error de red: ${e.message}")
            Outcome.RETRY to null
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado: ${e.message}")
            Outcome.RETRY to null
        }

    private fun clasificar(code: Int): Outcome = when {
        code in 200..299 -> Outcome.OK
        code == 401 -> Outcome.AUTH
        code == 408 || code == 429 || code >= 500 -> Outcome.RETRY
        else -> Outcome.REJECTED
    }

    private fun errorBody(response: Response<*>?): String =
        try { response?.errorBody()?.string()?.take(300) ?: "" } catch (e: Exception) { "" }
}
