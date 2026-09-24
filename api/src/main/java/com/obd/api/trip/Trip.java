package com.obd.api.trip;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One use of a car by one driver.
 *
 * A trip with no {@code tripEndedAt} is the car's active trip: that is where
 * "who is driving this car right now" comes from. The car itself holds no
 * pointer to it, so the two cannot contradict each other, and the database
 * guarantees there is at most one such row per car (see V4__trips.sql).
 */
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

    /**
     * Car and driver are held as raw ids rather than associations, as
     * {@code Car.carOwnerId} already is: starting or finishing a trip needs
     * neither the car's name nor the driver's, so a mapping would only add a
     * join and a lazy-loading trap. A listing endpoint that wants those names
     * can join for them.
     */
    @Column(name = "car_id", nullable = false, updatable = false)
    private UUID tripCarId;

    /** Null once the driver's account is deleted - the trip itself survives. */
    @Column(name = "driver_id", updatable = false)
    private UUID tripDriverId;

    // Server clock, never taken from the client: a caller must not be able to
    // backdate a trip. @Builder ignores plain field initialisers, so without
    // @Builder.Default this would be null in a NOT NULL column.
    @Column(name = "started_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant tripStartedAt = Instant.now();

    /** Null while the trip is running. Set once, when the trip is finished. */
    @Column(name = "ended_at")
    private Instant tripEndedAt;

    // Fuel at each end of the trip. The expense is the difference, computed on
    // read - see TripDTO.Read.
    @Column(name = "initial_fuel")
    private Integer tripInitialFuel;

    @Column(name = "final_fuel")
    private Integer tripFinalFuel;

    @Column(name = "distance")
    private Integer tripDistance;

    /** True while the trip is running. Derived, never stored. */
    @Transient
    public boolean isActive() {
        return tripEndedAt == null;
    }
}
