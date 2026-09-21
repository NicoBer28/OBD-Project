package com.example.obd_app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Formateador de fecha para cumplir con el formato del Backend ("2026-09-10T14:56:13Z")
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun doWork(): Result {
        Log.i("OBD-SYNC", "🔄 Iniciando sincronización de Telemetría...")

        val (tokenFlutter, carIdFlutter) = obtenerCredencialesFlutter(applicationContext)
        if (tokenFlutter == null || carIdFlutter == null) {
            Log.e("OBD-SYNC", "No hay Token/CarId guardado. Abortando sincronización.")
            return Result.failure()
        }

        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.obdDao()

        //INICIAR TRIPS
        val viajesAtrasados = dao.getUnsyncedTrips()
        
        for (viaje in viajesAtrasados) {
            if (viaje.backendId == null) {
                try {
                    val startReq = StartTripRequest(viaje.carId, viaje.initialFuel)
                    val responseStart = ApiClient.retrofitService.startTrip(tokenFlutter, startReq)
                    
                    if (responseStart.isSuccessful) {
                        val backendIdReal = responseStart.body()?.id
                        if (backendIdReal != null) {
                            val viajeActualizado = viaje.copy(
                                backendId = backendIdReal,
                                syncStatus = if (viaje.status == "FINISHED") "PENDING_FINISH" else "SYNCED_START"
                            )
                            dao.updateTrip(viajeActualizado)
                            Log.i("OBD-SYNC", "✅ Viaje iniciado en el servidor. ID: $backendIdReal")
                        }
                    } else {
                        Log.e("OBD-SYNC", "Error al iniciar viaje atrasado: ${responseStart.code()}")
                        return Result.retry()
                    }
                } catch (e: Exception) {
                    Log.e("OBD-SYNC", "Error de red iniciando viaje: ${e.message}")
                    return Result.retry()
                }
            }
        }

        //SINCRONIZAR CHUNKS
        val pendingChunks = dao.getPendingChunks()
        val chunksSincronizados = mutableListOf<String>()

        if (pendingChunks.isNotEmpty()) {
            for (chunk in pendingChunks) {
                try {
                    val jsonOriginal = JSONObject(chunk.payload)
                    val t0 = jsonOriginal.getLong("t0")
                    val samples = jsonOriginal.getJSONArray("samples")
                    
                    val readings = mutableListOf<ReadingPayload>()

                    for (i in 0 until samples.length()) {
                        val sample = samples.getJSONObject(i)
                        val timestampAbsoluto = t0 + sample.getLong("t")
                        
                        readings.add(
                            ReadingPayload(
                                recordedAt = isoFormatter.format(Date(timestampAbsoluto)),
                                latitude = null, 
                                longitude = null,
                                speed = sample.getInt("s"),
                                fuelLevel = sample.getInt("f"),
                                raw = mapOf("rpm" to sample.getInt("r"))
                            )
                        )
                    }

                    val requestBody = TelemetryBatchRequest(chunk.carId, readings)
                    val response = ApiClient.retrofitService.uploadTelemetry(tokenFlutter, requestBody)

                    if (response.isSuccessful) {
                        chunksSincronizados.add(chunk.id)
                    } else {
                        Log.e("OBD-SYNC", "Error subiendo chunk: ${response.code()}")
                        return Result.retry() 
                    }
                } catch (e: Exception) {
                    Log.e("OBD-SYNC", "Fallo al procesar chunk: ${e.message}")
                    return Result.retry()
                }
            }

            if (chunksSincronizados.isNotEmpty()) {
                dao.markChunksAsSynced(chunksSincronizados)
                dao.deleteSyncedChunks()
                Log.i("OBD-SYNC", "🚀 ¡Se subieron ${chunksSincronizados.size} chunks de telemetría!")
            }
        }

        // FINALIZAR TRIPS
        val viajesParaCerrar = dao.getUnsyncedTrips().filter { it.syncStatus == "PENDING_FINISH" && it.backendId != null }

        for (viaje in viajesParaCerrar) {
            try {
                val finishReq = FinishTripRequest(
                    tripFinalFuel = viaje.finalFuel ?: 0,
                    tripDistance = viaje.distance ?: 1.0
                )
                
                val responseFinish = ApiClient.retrofitService.finishTrip(tokenFlutter, viaje.backendId!!, finishReq)
                
                if (responseFinish.isSuccessful) {
                    val viajeCompletado = viaje.copy(syncStatus = "COMPLETED")
                    dao.updateTrip(viajeCompletado)
                    Log.i("OBD-SYNC", "✅ Viaje cerrado exitosamente en el servidor.")
                } else {
                    Log.e("OBD-SYNC", "Error al cerrar viaje: ${responseFinish.code()}")
                    return Result.retry()
                }
            } catch (e: Exception) {
                Log.e("OBD-SYNC", "Error de red cerrando viaje: ${e.message}")
                return Result.retry()
            }
        }

        return Result.success()
    }
}