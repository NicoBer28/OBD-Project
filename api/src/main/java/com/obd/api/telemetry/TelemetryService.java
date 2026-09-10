package com.obd.api.telemetry;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.telemetry.dto.TelemetryDTO;
import com.obd.api.telemetry.dto.TelemetryDTO.Reading;
import com.obd.api.trip.Trip;
import com.obd.api.trip.TripRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final CarRepository carRepository;
    private final TripRepository tripRepository;
    private final CarAccess carAccess;

    /**
     * Stores a batch of readings for one car and brings the car's cached
     * snapshot up to date.
     *
     * The whole batch is one transaction: if any insert fails nothing is kept
     * and the snapshot does not move, so the client can simply retry the batch.
     * Retrying is always safe - a reading already present counts as a
     * duplicate rather than an error.
     */
    @Transactional
    public TelemetryDTO.Ingested ingest(UUID userId, TelemetryDTO.Ingest request) {
        Car car = carAccess.readableBy(userId, request.carId())
                .orElseThrow(() -> new CarNotFoundException(request.carId()));

        // Looked up once per batch, and by car rather than by uploader: the
        // reading belongs to whoever is driving the car, which need not be
        // whoever is holding the phone.
        Trip openTrip = tripRepository.findByTripCarIdAndTripEndedAtIsNull(car.getCarId())
                .orElse(null);

        int stored = 0;
        for (Reading r : request.readings()) {
            stored += telemetryRepository.insertIgnoringDuplicates(
                    car.getCarId(),
                    tripIdFor(r, openTrip),
                    r.recordedAt(),
                    r.latitude(), r.longitude(), r.speed(),
                    r.fuelLevel(), r.batteryLevel(), r.mileage(),
                    r.raw() == null ? null : r.raw().toString());
        }

        Reading newest = fold(request.readings());
        int snapshotMoved = carRepository.refreshSnapshot(
                car.getCarId(), newest.recordedAt(),
                newest.fuelLevel(), newest.batteryLevel(), newest.mileage(),
                newest.latitude(), newest.longitude());

        return new TelemetryDTO.Ingested(
                car.getCarId(),
                stored,
                request.readings().size() - stored,
                openTrip == null ? null : openTrip.getTripId(),
                snapshotMoved == 1,
                newest.recordedAt());
    }

    /**
     * One page of a car's history after a cursor, oldest first.
     *
     * Fetches one row more than the limit: whether that extra row exists is
     * {@code hasMore}, which spares the client a final empty round trip and
     * spares us a count query over the largest table in the database.
     *
     * No transaction: Telemetry holds raw ids, not lazy associations, so there
     * is nothing that needs the persistence context kept open.
     */
    public TelemetryDTO.Page history(UUID userId, TelemetryDTO.Query query) {
        Car car = carAccess.readableBy(userId, query.carId())
                .orElseThrow(() -> new CarNotFoundException(query.carId()));

        List<Telemetry> rows = telemetryRepository
                .findByTelemetryCarIdAndTelemetryRecordedAtGreaterThanOrderByTelemetryRecordedAtAsc(
                        car.getCarId(), query.since(), PageRequest.of(0, query.limit() + 1));

        boolean hasMore = rows.size() > query.limit();
        List<Telemetry> page = hasMore ? rows.subList(0, query.limit()) : rows;

        return new TelemetryDTO.Page(
                car.getCarId(),
                page.stream().map(TelemetryDTO.Read::from).toList(),
                page.isEmpty() ? query.since() : page.getLast().getTelemetryRecordedAt(),
                hasMore);
    }

    /**
     * A reading belongs to the open trip only if it was taken after the trip
     * started. A late-flushed reading from before the driver pressed "start"
     * is history from when the car was parked, not part of the trip.
     */
    private static UUID tripIdFor(Reading reading, Trip openTrip) {
        if (openTrip == null || reading.recordedAt().isBefore(openTrip.getTripStartedAt())) {
            return null;
        }
        return openTrip.getTripId();
    }

    /**
     * Collapses the batch into the single reading the snapshot is refreshed
     * from: each field taken from the newest reading that actually carries it,
     * stamped with the newest time in the batch.
     *
     * Simply taking the newest reading would be wrong in a way that is easy to
     * miss: if it happens to lack a fuel level but an older reading in the same
     * batch has one, coalesce in refreshSnapshot would keep the *database's*
     * old value and discard the batch's - throwing away data we were just
     * handed.
     */
    static Reading fold(List<Reading> readings) {
        List<Reading> newestFirst = readings.stream()
                .sorted(Comparator.comparing(Reading::recordedAt).reversed())
                .toList();

        return new Reading(
                newestFirst.getFirst().recordedAt(),
                firstPresent(newestFirst, Reading::latitude),
                firstPresent(newestFirst, Reading::longitude),
                firstPresent(newestFirst, Reading::speed),
                firstPresent(newestFirst, Reading::fuelLevel),
                firstPresent(newestFirst, Reading::batteryLevel),
                firstPresent(newestFirst, Reading::mileage),
                null);
    }

    private static <T> T firstPresent(List<Reading> newestFirst, Function<Reading, T> field) {
        return newestFirst.stream()
                .map(field)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
