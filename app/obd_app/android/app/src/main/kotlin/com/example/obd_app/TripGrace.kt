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
    private const val KEY_ULTIMO_DATO = "ultimoDatoMs"
    private const val KEY_NAFTA = "nafta"
    private const val UNIQUE_CIERRE = "obd-trip-grace"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun guardarUltimoDato(context: Context, ultimoDatoMs: Long, nafta: Int?) {
        prefs(context).edit().apply {
            putLong(KEY_ULTIMO_DATO, ultimoDatoMs)
            if (nafta != null) putInt(KEY_NAFTA, nafta) else remove(KEY_NAFTA)
        }.apply()
    }

    /**
     * Encola el cierre para cuando venza el tiempo de gracia contado desde [corteMs].
     * Va por WorkManager para que ocurra aunque el proceso ya no exista. Si para entonces
     * volvieron los datos, [cerrarViajeVencido] no hace nada.
     */
    fun programarCierre(context: Context, corteMs: Long) {
        val espera = (corteMs + TIEMPO_DE_GRACIA_MS - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<TripGraceWorker>()
            .setInitialDelay(espera, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_CIERRE,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Cierra el viaje activo si su último dato es más viejo que el tiempo de gracia. La hora
     * de fin es la del último dato, no la del momento en que se detecta. Es idempotente.
     *
     * @return true si cerró un viaje.
     */
    suspend fun cerrarViajeVencido(context: Context, dao: ObdDao): Boolean {
        val viaje = dao.getActiveTrip() ?: return false

        val p = prefs(context)
        val marca = p.getLong(KEY_ULTIMO_DATO, 0L)
        // Una marca anterior al inicio es de un viaje previo: no dice nada de este.
        val marcaEsDeEsteViaje = marca >= viaje.startedAt
        val ultimoDato = if (marcaEsDeEsteViaje) marca else viaje.startedAt

        if (System.currentTimeMillis() - ultimoDato < TIEMPO_DE_GRACIA_MS) return false

        val nafta = if (marcaEsDeEsteViaje && p.contains(KEY_NAFTA)) p.getInt(KEY_NAFTA, 0) else null
        if (dao.closeActiveTrip(ultimoDato, nafta) == 0) return false

        Log.i(TAG, "Viaje cerrado en SQLite local (sin datos desde hace más del tiempo de gracia).")
        SyncScheduler.requestSync(context)
        return true
    }
}

/** Corre al vencer el tiempo de gracia. Sin restricción de red: el cierre es local. */
class TripGraceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).obdDao()
        TripGrace.cerrarViajeVencido(applicationContext, dao)
        return Result.success()
    }
}
