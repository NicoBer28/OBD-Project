package com.example.obd_app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ObdDao {
    // ---- TRIPS ----
    // Cada auto tiene su propio viaje activo: todas las consultas van por auto.
    @Insert
    suspend fun insertTrip(trip: Trip)

    @Update
    suspend fun updateTrip(trip: Trip)

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' AND carId = :carId LIMIT 1")
    suspend fun getActiveTrip(carId: String): Trip?

    // Los viajes abiertos de todos los autos (para cerrar los que vencieron)
    @Query("SELECT * FROM trips WHERE status = 'ACTIVE'")
    suspend fun getActiveTrips(): List<Trip>

    // Buscar viajes que el servidor todavía no conoce
    @Query("SELECT * FROM trips WHERE syncStatus IN ('PENDING_START', 'PENDING_FINISH')")
    suspend fun getUnsyncedTrips(): List<Trip>

    // Viajes que el servidor todavía no conoce (activos o ya cerrados localmente)
    @Query("SELECT * FROM trips WHERE carId = :carId AND backendId IS NULL AND syncStatus IN ('PENDING_START', 'PENDING_FINISH') ORDER BY startedAt ASC")
    suspend fun getTripsToStart(carId: String): List<Trip>

    // Viajes cerrados localmente que falta cerrar en el servidor
    @Query("SELECT * FROM trips WHERE carId = :carId AND backendId IS NOT NULL AND syncStatus = 'PENDING_FINISH' ORDER BY startedAt ASC")
    suspend fun getTripsToFinish(carId: String): List<Trip>

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
        WHERE status = 'ACTIVE' AND carId = :carId
    """)
    suspend fun closeActiveTrip(carId: String, endedAt: Long, finalFuel: Int?): Int

    // Viajes que todavía tienen algo por subir (ni terminados de sincronizar ni rechazados)
    @Query("SELECT COUNT(*) FROM trips WHERE syncStatus NOT IN ('COMPLETED', 'FAILED')")
    suspend fun countPendingTrips(): Int

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    // ---- CHUNKS ----
    @Insert
    suspend fun insertChunk(chunk: TelemetryChunk)

    // Agarrar chunks pendientes priorizando los más viejos (los FAILED quedan afuera)
    @Query("SELECT * FROM telemetry_chunks WHERE carId = :carId AND syncStatus = 'PENDING' ORDER BY startTime ASC LIMIT 50")
    suspend fun getPendingChunksForCar(carId: String): List<TelemetryChunk>

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

    @Query("SELECT COUNT(*) FROM telemetry_chunks WHERE syncStatus = 'PENDING'")
    suspend fun countPendingChunks(): Int

    @Query("SELECT COUNT(*) FROM telemetry_chunks WHERE syncStatus = 'FAILED'")
    suspend fun countFailedChunks(): Int

    @Query("DELETE FROM telemetry_chunks")
    suspend fun deleteAllChunks()

    // Autos con algo por subir: el worker procesa uno por uno
    @Query("""
        SELECT carId FROM telemetry_chunks WHERE syncStatus = 'PENDING'
        UNION
        SELECT carId FROM trips WHERE syncStatus IN ('PENDING_START', 'PENDING_FINISH')
    """)
    suspend fun getCarIdsWithPendingWork(): List<String>

    // ---- ASSOCIATIONS ----
    // La MAC va siempre en mayúsculas, al guardar y al buscar.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssociation(association: Association)

    @Query("SELECT * FROM associations WHERE mac = :mac")
    suspend fun getAssociationByMac(mac: String): Association?

    @Query("SELECT * FROM associations")
    suspend fun getAssociations(): List<Association>

    @Query("SELECT * FROM associations WHERE carId = :carId")
    suspend fun getAssociationsByCar(carId: String): List<Association>

    @Query("DELETE FROM associations WHERE mac = :mac")
    suspend fun deleteAssociationByMac(mac: String)

    @Query("DELETE FROM associations WHERE carId = :carId")
    suspend fun deleteAssociationsByCar(carId: String)

    @Query("DELETE FROM associations")
    suspend fun deleteAllAssociations()
}
