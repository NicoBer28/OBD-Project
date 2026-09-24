package com.obd.api.trip.dto;

import com.obd.api.trip.Trip;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

public class TripDTO {

    /**
     * Starting a trip takes only the car. The driver comes from the access
     * token and the start time from the server clock, so neither can be spoofed
     * or backdated.
     */
    public record Create(
            @NotNull UUID carId,
            // Optional: when the client does not send a reading, the car's last
            // reported fuel level is used instead. Null on both means the
            // expense for this trip is simply unknown.
            @PositiveOrZero Integer initialFuel
    ) {}

    public record Read(
            UUID id,
            UUID carId,
            UUID driverId,
            Instant startedAt,
            Instant endedAt,
            Integer initialFuel,
            Integer finalFuel,
            // initialFuel - finalFuel, never stored. Null while the trip is
            // running, and null afterwards if either reading is missing.
            Integer fuelUsed,
            Integer distance,
            // endedAt == null, spelled out so a client does not have to infer it.
            boolean active
    ) {
        public static Read from(Trip trip) {
            return new Read(
                    trip.getTripId(),
                    trip.getTripCarId(),
                    trip.getTripDriverId(),
                    trip.getTripStartedAt(),
                    trip.getTripEndedAt(),
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
            @Positive Integer tripDistance
    ){}
}
