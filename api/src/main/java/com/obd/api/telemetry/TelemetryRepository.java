package com.obd.api.telemetry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    /**
     * The car's most recent reading. Ingestion compares this against the
     * incoming one before refreshing the cached snapshot on the car, so an
     * out-of-order upload cannot overwrite fresher values with stale ones.
     */
    Optional<Telemetry> findFirstByTelemetryCarIdOrderByTelemetryRecordedAtDesc(UUID telemetryCarId);

    /** A car's history, newest first. Paged - this table has no natural bound. */
    List<Telemetry> findByTelemetryCarIdOrderByTelemetryRecordedAtDesc(UUID telemetryCarId,
                                                                      Pageable pageable);

    /**
     * Every reading taken during one trip, oldest first: the trip's route, and
     * what the per-trip speed and fuel figures are computed from.
     */
    List<Telemetry> findByTelemetryTripIdOrderByTelemetryRecordedAtAsc(UUID telemetryTripId);
}
