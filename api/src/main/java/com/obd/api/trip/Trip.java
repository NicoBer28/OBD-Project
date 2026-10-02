package com.obd.api.trip;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trips")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "car_id", nullable = false, updatable = false)
    private UUID tripCarId;

    @Column(name = "driver_id", updatable = false)
    private UUID tripDriverId;

    @Column(name = "started_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant tripStartedAt = Instant.now();

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant tripCreatedAt = Instant.now();

    @Column(name = "client_trip_id", updatable = false)
    private UUID tripClientTripId;

    @Column(name = "ended_at")
    private Instant tripEndedAt;

    @Column(name = "initial_fuel")
    private Integer tripInitialFuel;

    @Column(name = "final_fuel")
    private Integer tripFinalFuel;

    @Column(name = "distance_km")
    private BigDecimal tripDistance;

    @Transient
    public boolean isActive() {
        return tripEndedAt == null;
    }
}
