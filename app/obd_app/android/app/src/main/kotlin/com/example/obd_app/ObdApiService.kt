package com.example.obd_app

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// --- MODELOS DE DATOS PARA MANDAR AL SERVIDOR ---

data class StartTripRequest(
    val carId: String,
    val initialFuel: Int
)

data class StartTripResponse(
    val id: String, // Este es el UUID real del backend que necesitamos guardar
    val carId: String,
    val startedAt: String
)

data class FinishTripRequest(
    val tripFinalFuel: Int,
    val tripDistance: Double
)


data class TelemetryBatchRequest(
    val carId: String,
    val readings: List<ReadingPayload>
)

data class ReadingPayload(
    val recordedAt: String,
    val latitude: Double?,
    val longitude: Double?,
    val speed: Int,
    val fuelLevel: Int,
    val raw: Map<String, Any>? = null
)

// --- INTERFAZ RETROFIT ---

interface ObdApiService {
    
    @POST("/api/v1/trips")
    suspend fun startTrip(
        @Header("Authorization") token: String,
        @Body request: StartTripRequest
    ): Response<StartTripResponse>

    @POST("/api/v1/trips/{tripId}/finish")
    suspend fun finishTrip(
        @Header("Authorization") token: String,
        @Path("tripId") tripId: String,
        @Body request: FinishTripRequest
    ): Response<Unit>

    @POST("/api/v1/telemetry")
    suspend fun uploadTelemetry(
        @Header("Authorization") token: String,
        @Body request: TelemetryBatchRequest
    ): Response<Unit>
}