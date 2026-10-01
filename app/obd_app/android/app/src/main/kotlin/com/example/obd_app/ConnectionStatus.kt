package com.example.obd_app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Estado de la conexión con el ESP32 de cada auto, para mostrarlo en la app.
 *
 * Se calcula con lo que el servicio ve en este momento MÁS la base local, no solo con la
 * memoria del servicio: Android puede destruir el servicio apenas el auto desaparece, y el
 * viaje sigue abierto (en tiempo de gracia) aunque ya no haya servicio que lo recuerde.
 */
object ConnectionStatus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Calcular y avisar van juntos y de a uno, para que los avisos lleguen en orden.
    private val mutex = Mutex()

    suspend fun conexionDe(context: Context, carId: String): ConnectionEvent {
        if (ObdCompanionService.recibeDatosDe(carId)) return ConnectionEvent(carId, EstadoConexion.CONECTADO)

        // Viaje abierto sin datos = se cortó y todavía corre el tiempo de gracia. Se informa
        // cuándo vence, para que la app muestre la cuenta regresiva.
        val viaje = AppDatabase.getDatabase(context).obdDao().getActiveTrip(carId)
        if (viaje != null) {
            return ConnectionEvent(carId, EstadoConexion.RECONECTANDO, TripGrace.vencimientoDe(context, viaje))
        }

        val estado = if (ObdCompanionService.vePresenteA(carId)) EstadoConexion.CONECTANDO else EstadoConexion.DESCONECTADO
        return ConnectionEvent(carId, estado)
    }

    /** Le avisa a Flutter (si está abierto) en qué quedó la conexión de [carId]. */
    fun avisar(context: Context, carId: String?) {
        if (carId == null || ObdEventBridge.flutterApi == null) return
        val appContext = context.applicationContext

        scope.launch {
            mutex.withLock {
                val evento = conexionDe(appContext, carId)
                withContext(Dispatchers.Main) {
                    try {
                        ObdEventBridge.flutterApi?.onConnectionChanged(evento)
                    } catch (e: Exception) {
                        // Silenciado intencionalmente si Flutter está cerrado
                    }
                }
            }
        }
    }
}
