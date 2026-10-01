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
    val syncStatus: String           // "PENDING_START", "SYNCED_START", "PENDING_FINISH", "COMPLETED", "FAILED"
)

@Entity(tableName = "telemetry_chunks")
data class TelemetryChunk(
    @PrimaryKey val id: String,
    val carId: String,
    val startTime: Long,             // Marca de tiempo del primer dato del chunk
    val payload: String,             // JSON Array crudo para guardar localmente
    val syncStatus: String,          // "PENDING", "SYNCED", "FAILED"
    val retryCount: Int = 0
)

// Qué ESP32 (MAC, siempre en mayúsculas) corresponde a qué auto en este celular.
@Entity(tableName = "associations")
data class Association(
    @PrimaryKey val mac: String,
    val associationId: Int,          // id de la asociación en CompanionDeviceManager
    val carId: String,
    val createdAt: Long
)
