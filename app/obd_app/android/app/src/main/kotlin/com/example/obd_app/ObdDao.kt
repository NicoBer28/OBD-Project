package com.example.obd_app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ObdDao {
    // ---- TRIPS ----
    @Insert
    suspend fun insertTrip(trip: Trip)

    @Update
    suspend fun updateTrip(trip: Trip)

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTrip(): Trip?

    // Buscar viajes que el servidor todavía no conoce
    @Query("SELECT * FROM trips WHERE syncStatus IN ('PENDING_START', 'PENDING_FINISH')")
    suspend fun getUnsyncedTrips(): List<Trip>

    // Viajes que el servidor todavía no conoce (activos o ya cerrados localmente)
    @Query("SELECT * FROM trips WHERE backendId IS NULL AND syncStatus IN ('PENDING_START', 'PENDING_FINISH') ORDER BY startedAt ASC")
    suspend fun getTripsToStart(): List<Trip>

    // Viajes cerrados localmente que falta cerrar en el servidor
    @Query("SELECT * FROM trips WHERE backendId IS NOT NULL AND syncStatus = 'PENDING_FINISH' ORDER BY startedAt ASC")
    suspend fun getTripsToFinish(): List<Trip>

    // UPDATEs puntuales en vez de updateTrip(copy): el servicio y el worker tocan el mismo
    // viaje desde hilos distintos, y reescribir la fila entera con una copia vieja pisaría
    // el cambio del otro (ej.: volver a poner ACTIVE un viaje recién cerrado).

    @Query("""
        UPDATE trips SET backendId = :backendId,
            syncStatus = CASE WHEN status = 'FINISHED' THEN 'PENDING_FINISH' ELSE 'SYNCED_START' END
        WHERE localId = :localId
    """)
    suspend fun markTripStarted(localId: String, backendId: String)

    // syncStatus: 'COMPLETED' o 'FAILED' (el servidor rechazó el viaje; no se reintenta)
    @Query("UPDATE trips SET syncStatus = :syncStatus WHERE localId = :localId")
    suspend fun setTripSyncStatus(localId: String, syncStatus: String)

    @Query("""
        UPDATE trips SET status = 'FINISHED', endedAt = :endedAt, finalFuel = :finalFuel,
            syncStatus = CASE WHEN syncStatus = 'FAILED' THEN 'FAILED' ELSE 'PENDING_FINISH' END
        WHERE status = 'ACTIVE'
    """)
    suspend fun closeActiveTrip(endedAt: Long, finalFuel: Int?): Int

    // ---- CHUNKS ----
    @Insert
    suspend fun insertChunk(chunk: TelemetryChunk)

    // Agarrar chunks pendientes priorizando los más viejos (los FAILED quedan afuera)
    @Query("SELECT * FROM telemetry_chunks WHERE syncStatus = 'PENDING' ORDER BY startTime ASC LIMIT 50")
    suspend fun getPendingChunks(): List<TelemetryChunk>

    @Query("UPDATE telemetry_chunks SET syncStatus = 'SYNCED' WHERE id IN (:chunkIds)")
    suspend fun markChunksAsSynced(chunkIds: List<String>)

    //Para limpiar espacio en el celular
    @Query("DELETE FROM telemetry_chunks WHERE syncStatus = 'SYNCED'")
    suspend fun deleteSyncedChunks()

    // Un chunk subido se borra enseguida: no hace falta el paso intermedio SYNCED
    @Query("DELETE FROM telemetry_chunks WHERE id IN (:chunkIds)")
    suspend fun deleteChunks(chunkIds: List<String>)

    // El servidor lo rechazó (400/404/...): reintentarlo nunca va a funcionar y bloquearía
    // la cola. Queda guardado para diagnóstico.
    @Query("UPDATE telemetry_chunks SET syncStatus = 'FAILED' WHERE id = :chunkId")
    suspend fun markChunkFailed(chunkId: String)

    @Query("UPDATE telemetry_chunks SET retryCount = retryCount + 1 WHERE id = :chunkId")
    suspend fun incrementChunkRetry(chunkId: String)
}
