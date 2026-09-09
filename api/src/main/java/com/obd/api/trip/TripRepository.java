package com.obd.api.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    /**
     * The car's active trip, if it has one. Derived from the entity properties
     * (tripCarId, tripEndedAt), not the columns.
     *
     * Returns at most one row because {@code ux_trips_one_active_per_car}
     * guarantees it; {@code Optional} rather than a list makes that guarantee
     * visible at the call site, and fails loudly if the index is ever dropped.
     */
    Optional<Trip> findByTripCarIdAndTripEndedAtIsNull(UUID tripCarId);
}
