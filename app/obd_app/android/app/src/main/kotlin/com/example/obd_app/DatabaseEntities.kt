package com.example.obd_app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey val localId: String, // UUID interno para Android
    val backendId: String?,
    val carId: String,
    val initialFuel: Int,
    val finalFuel: Int? = null,
    val distance: Double? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String,              // "ACTIVE" o "FINISHED"
    val syncStatus: String           // "PENDING_START", "SYNCED_START", "PENDING_FINISH", "COMPLETED"
)

@Entity(tableName = "telemetry_chunks")
data class TelemetryChunk(
    @PrimaryKey val id: String,
    val carId: String,
    val startTime: Long,             // Marca de tiempo del primer dato del chunk
    val payload: String,             // JSON Array crudo para guardar localmente
    val syncStatus: String,          // "PENDING", "SYNCED"
    val retryCount: Int = 0
)