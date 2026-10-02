package com.obd.api.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    /**
     * The driver's earlier attempt at this same trip, if the client sent an
     * idempotency key. Scoped to the driver, not global: keying on the value
     * alone would hand somebody else's trip to a caller who guessed - or
     * replayed - their id.
     */
    Optional<Trip> findByTripDriverIdAndTripClientTripId(UUID tripDriverId, UUID tripClientTripId);

    /**
     * Closes a trip, if it is this driver's and still open.
     *
     * {@code endedAt >= tripStartedAt} is in the WHERE clause rather than
     * checked beforehand, so the comparison happens against the row being
     * written in the same statement - there is no window where another request
     * could change the start time between the check and the write. A return of
     * 0 means not yours, already ended, or an end before the start; the service
     * reads the row back to say which.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Trip t
           set t.tripEndedAt   = :endedAt,
               t.tripFinalFuel = :finalFuel,
               t.tripDistance  = :distanceKm
         where t.tripId       = :tripId
           and t.tripDriverId = :driverId
           and t.tripEndedAt is null
           and :endedAt >= t.tripStartedAt
        """)
    int finish(@Param("tripId") UUID tripId,
               @Param("driverId") UUID driverId,
               @Param("endedAt") Instant endedAt,
               @Param("finalFuel") Integer finalFuel,
               @Param("distanceKm") BigDecimal distanceKm);

    Optional<Trip> deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(UUID tripId, UUID driveId);
}
