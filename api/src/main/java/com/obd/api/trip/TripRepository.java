package com.obd.api.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    List<Trip> findByTripDriverIdOrderByTripStartedAtDesc(UUID tripDriverId);


    List<Trip> findByTripCarIdOrderByTripStartedAtDesc(UUID carId);

    /**
     * The car's active trip, if it has one. Derived from the entity properties
     * (tripCarId, tripEndedAt), not the columns.
     *
     * Returns at most one row because {@code ux_trips_one_active_per_car}
     * guarantees it; {@code Optional} rather than a list makes that guarantee
     * visible at the call site, and fails loudly if the index is ever dropped.
     */
    Optional<Trip> findByTripCarIdAndTripEndedAtIsNull(UUID tripCarId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Trip t
           set t.tripEndedAt   = :now,
               t.tripFinalFuel = :finalFuel,
               t.tripDistance  = :distance
         where t.tripId       = :tripId
           and t.tripDriverId = :driverId
           and t.tripEndedAt is null
        """)
    int finish(@Param("tripId") UUID tripId,
               @Param("driverId") UUID driverId,
               @Param("now") Instant now,
               @Param("finalFuel") Integer finalFuel,
               @Param("distance") Integer distance);

    Optional<Trip> deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(UUID tripId, UUID driveId);
}
