package com.obd.api.trip.dto;

import com.obd.api.trip.Trip;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TripDTO {


    public record Create(
            @NotNull UUID carId,
            @PositiveOrZero Integer initialFuel,
            Instant startedAt,
            UUID clientTripId
    ) {}

    public record Read(
            UUID id,
            UUID carId,
            UUID driverId,
            Instant startedAt,
            Instant endedAt,
            Instant createdAt,
            UUID clientTripId,
            Integer initialFuel,
            Integer finalFuel,
            Integer fuelUsed,
            BigDecimal distanceKm,
            boolean active
    ) {
        public static Read from(Trip trip) {
            return new Read(
                    trip.getTripId(),
                    trip.getTripCarId(),
                    trip.getTripDriverId(),
                    trip.getTripStartedAt(),
                    trip.getTripEndedAt(),
                    trip.getTripCreatedAt(),
                    trip.getTripClientTripId(),
                    trip.getTripInitialFuel(),
                    trip.getTripFinalFuel(),
                    fuelUsed(trip),
                    trip.getTripDistance(),
                    trip.isActive());
        }

        private static Integer fuelUsed(Trip trip) {
            Integer initial = trip.getTripInitialFuel();
            Integer last = trip.getTripFinalFuel();
            return initial == null || last == null ? null : initial - last;
        }
    }

    public record finish(
            @PositiveOrZero Integer tripFinalFuel,
            @Positive @Digits(integer = 6, fraction = 2) BigDecimal distanceKm,
            Instant endedAt
    ){}

    public record RoutePoint(
            Instant recordedAt,
            Double latitude,
            Double longitude,
            Integer speed
    ) {}
}
