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

    // ---- CHUNKS ----
    @Insert
    suspend fun insertChunk(chunk: TelemetryChunk)

    // Agarrar chunks pendientes priorizando los más viejos
    @Query("SELECT * FROM telemetry_chunks WHERE syncStatus = 'PENDING' ORDER BY startTime ASC LIMIT 50")
    suspend fun getPendingChunks(): List<TelemetryChunk>
    
    @Query("UPDATE telemetry_chunks SET syncStatus = 'SYNCED' WHERE id IN (:chunkIds)")
    suspend fun markChunksAsSynced(chunkIds: List<String>)
    
    //Para limpiar espacio en el celular
    @Query("DELETE FROM telemetry_chunks WHERE syncStatus = 'SYNCED'")
    suspend fun deleteSyncedChunks()
}