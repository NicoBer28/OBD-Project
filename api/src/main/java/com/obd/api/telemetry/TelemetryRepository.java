package com.obd.api.telemetry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    /**
     * Stores one reading, or does nothing if the car already has one at that
     * instant. Returns 1 or 0 accordingly.
     *
     * Native, because this is what {@code ux_telemetry_car_recorded_at} is for
     * and JPQL has no {@code on conflict}. The obvious alternative - save each
     * reading and catch the duplicate-key exception - does not work in
     * Postgres: the first violation aborts the transaction and every statement
     * after it fails until rollback, so a batch would lose everything past its
     * first duplicate. {@code received_at} is left to the column default, which
     * is the database clock.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into telemetry (car_id, trip_id, recorded_at,
                                   latitude, longitude, speed,
                                   fuel_level, battery_level, mileage, raw_frame)
            values (:carId, :tripId, :recordedAt,
                    :latitude, :longitude, :speed,
                    :fuelLevel, :batteryLevel, :mileage, cast(:raw as jsonb))
            on conflict (car_id, recorded_at) do nothing
            """)
    int insertIgnoringDuplicates(@Param("carId") UUID carId,
                                 @Param("tripId") UUID tripId,
                                 @Param("recordedAt") Instant recordedAt,
                                 @Param("latitude") Double latitude,
                                 @Param("longitude") Double longitude,
                                 @Param("speed") Integer speed,
                                 @Param("fuelLevel") Integer fuelLevel,
                                 @Param("batteryLevel") Integer batteryLevel,
                                 @Param("mileage") Integer mileage,
                                 @Param("raw") String raw);

    /**
     * The car's most recent reading. Derived from the entity properties
     * (telemetryCarId, telemetryRecordedAt), not the columns.
     */
    Optional<Telemetry> findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(UUID telemetryCarId);

    /** A car's history, newest first. Paged - this table has no natural bound. */
    List<Telemetry> findByTelemetryCarIdOrderByTelemetryRecordedAtDesc(UUID telemetryCarId,
                                                                      Pageable pageable);

    /**
     * Readings recorded strictly after {@code since}, oldest first: one page of
     * an incremental sync. Served entirely by {@code ux_telemetry_car_recorded_at}
     * - a range scan from the cursor forward - so it costs the same on a car
     * with a million readings as on one with ten.
     */
    List<Telemetry> findByTelemetryCarIdAndTelemetryRecordedAtGreaterThanOrderByTelemetryRecordedAtAsc(
            UUID telemetryCarId, Instant since, Pageable pageable);

    /**
     * Every reading taken during one trip, oldest first: the trip's route, and
     * what the per-trip speed and fuel figures are computed from.
     */
    List<Telemetry> findByTelemetryTripIdOrderByTelemetryRecordedAtAsc(UUID telemetryTripId);
}
