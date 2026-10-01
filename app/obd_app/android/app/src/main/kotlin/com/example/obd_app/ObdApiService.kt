package com.example.obd_app

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// --- MODELOS DE DATOS PARA MANDAR AL SERVIDOR ---
// Gson NO serializa los campos null: un opcional en null directamente no viaja.

data class StartTripRequest(
    val carId: String,
    val initialFuel: Int? // null = el servidor usa el último nivel de nafta conocido del auto
)

/** TripDTO.Read. Todo nullable: Gson no respeta la nulabilidad de Kotlin. */
data class TripResponse(
    val id: String?,
    val carId: String?,
    val driverId: String?,
    val active: Boolean?,
    val startedAt: String?
)

data class FinishTripRequest(
    val tripFinalFuel: Int?,      // null = gasto desconocido (no cero)
    val tripDistance: Double? = null // opcional, > 0. Null hasta calcular la distancia real
)

data class TelemetryBatchRequest(
    val carId: String,
    val readings: List<ReadingPayload>
)

data class ReadingPayload(
    val recordedAt: String,
    val latitude: Double?,
    val longitude: Double?,
    val speed: Int?,
    val fuelLevel: Int?,
    val raw: Map<String, Any>? = null
)

// --- INTERFAZ RETROFIT ---
// token = valor completo del header, ej. "Bearer eyJ..." (en la Fase 2: "Device obdd_...").

interface ObdApiService {

    @POST("/api/v1/trips")
    suspend fun startTrip(
        @Header("Authorization") token: String,
        @Body request: StartTripRequest
    ): Response<TripResponse>

    @POST("/api/v1/trips/{tripId}/finish")
    suspend fun finishTrip(
        @Header("Authorization") token: String,
        @Path("tripId") tripId: String,
        @Body request: FinishTripRequest
    ): Response<Unit>

    /** 200 con el viaje abierto, o 204 (body null) si el auto no está en viaje. */
    @GET("/api/v1/cars/{carId}/trips/active")
    suspend fun getActiveTrip(
        @Header("Authorization") token: String,
        @Path("carId") carId: String
    ): Response<TripResponse>

    @POST("/api/v1/telemetry")
    suspend fun uploadTelemetry(
        @Header("Authorization") token: String,
        @Body request: TelemetryBatchRequest
    ): Response<Unit>
}
