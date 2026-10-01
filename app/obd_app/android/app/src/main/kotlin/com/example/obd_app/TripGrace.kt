package com.example.obd_app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Tiempo de gracia de un viaje: cuánto puede pasar sin datos del ESP32 antes de darlo por
 * terminado. Un corte más corto que eso (microcorte de Bluetooth) no termina el viaje.
 *
 * El momento del último dato se guarda en disco y la decisión de cerrar se toma SIEMPRE
 * contra ese valor, no contra la memoria del servicio: Android puede destruir el servicio
 * (o matar el proceso) en medio de la espera, y el viaje se tiene que cerrar igual, con la
 * hora real del corte.
 */
object TripGrace {
    /** Umbral sin conexión a partir del cual el viaje se da por terminado. */
    const val TIEMPO_DE_GRACIA_MS = 3 * 60 * 1000L

    private const val TAG = "OBD-DB"
    private const val PREFS = "obd_trip_grace"
    private const val KEY_ULTIMO_DATO = "ultimoDatoMs_" // + carId
    private const val KEY_NAFTA = "nafta_"               // + carId
    private const val UNIQUE_CIERRE = "obd-trip-grace-" // + carId

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Cada auto tiene su propia marca y su propio cierre programado: con dos autos en viaje,
    // el corte de uno no puede cerrar (ni mantener abierto) el viaje del otro.
    fun guardarUltimoDato(context: Context, carId: String, ultimoDatoMs: Long, nafta: Int?) {
        prefs(context).edit().apply {
            putLong(KEY_ULTIMO_DATO + carId, ultimoDatoMs)
            if (nafta != null) putInt(KEY_NAFTA + carId, nafta) else remove(KEY_NAFTA + carId)
        }.apply()
    }

    /**
     * Encola el cierre para cuando venza el tiempo de gracia contado desde [corteMs].
     * Va por WorkManager para que ocurra aunque el proceso ya no exista. Si para entonces
     * volvieron los datos, [cerrarViajesVencidos] no hace nada.
     */
    fun programarCierre(context: Context, carId: String, corteMs: Long) {
        val espera = (corteMs + TIEMPO_DE_GRACIA_MS - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<TripGraceWorker>()
            .setInitialDelay(espera, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_CIERRE + carId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Cierra el viaje activo de [carId] si su último dato es más viejo que el tiempo de
     * gracia. La hora de fin es la del último dato, no la del momento en que se detecta.
     * Es idempotente.
     *
     * @return true si cerró un viaje.
     */
    suspend fun cerrarViajeVencido(context: Context, dao: ObdDao, carId: String): Boolean {
        val viaje = dao.getActiveTrip(carId) ?: return false
        return cerrarSiVencio(context, dao, viaje)
    }

    /** Lo mismo que [cerrarViajeVencido], para los viajes abiertos de todos los autos. */
    suspend fun cerrarViajesVencidos(context: Context, dao: ObdDao) {
        for (viaje in dao.getActiveTrips()) cerrarSiVencio(context, dao, viaje)
    }

    private suspend fun cerrarSiVencio(context: Context, dao: ObdDao, viaje: Trip): Boolean {
        val p = prefs(context)
        val marca = p.getLong(KEY_ULTIMO_DATO + viaje.carId, 0L)
        // Una marca anterior al inicio es de un viaje previo: no dice nada de este.
        val marcaEsDeEsteViaje = marca >= viaje.startedAt
        val ultimoDato = if (marcaEsDeEsteViaje) marca else viaje.startedAt

        if (System.currentTimeMillis() - ultimoDato < TIEMPO_DE_GRACIA_MS) return false

        val keyNafta = KEY_NAFTA + viaje.carId
        val nafta = if (marcaEsDeEsteViaje && p.contains(keyNafta)) p.getInt(keyNafta, 0) else null
        if (dao.closeActiveTrip(viaje.carId, ultimoDato, nafta) == 0) return false

        Log.i(TAG, "Viaje del auto ${viaje.carId} cerrado en SQLite local (sin datos desde hace más del tiempo de gracia).")
        SyncScheduler.requestSync(context)
        return true
    }
}

/** Corre al vencer el tiempo de gracia. Sin restricción de red: el cierre es local. */
class TripGraceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).obdDao()
        TripGrace.cerrarViajesVencidos(applicationContext, dao)
        return Result.success()
    }
}
