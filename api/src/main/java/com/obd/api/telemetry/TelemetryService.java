package com.obd.api.telemetry;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.devicetoken.DeviceScope;
import com.obd.api.device.Device;
import com.obd.api.device.DeviceRepository;
import com.obd.api.device.DeviceService;
import com.obd.api.device.exception.DeviceNotFoundException;
import com.obd.api.telemetry.dto.TelemetryDTO;
import com.obd.api.telemetry.dto.TelemetryDTO.Reading;
import com.obd.api.trip.Trip;
import com.obd.api.trip.TripRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final DeviceRepository deviceRepository;
    private final CarAccess carAccess;
    private final DeviceScope deviceScope;


    @Transactional
    public TelemetryDTO.Ingested ingest(UUID userId, TelemetryDTO.Ingest request) {
        Car car = resolveCar(userId, request);

        deviceScope.requireCar(car.getCarId());

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

        // Only when the batch named the dongle do we know which device spoke;
        // a batch by carId says nothing about the hardware.
        if (request.serial() != null) {
            deviceRepository.touch(DeviceService.normaliseSerial(request.serial()), Instant.now());
        }

        return new TelemetryDTO.Ingested(
                car.getCarId(),
                stored,
                request.readings().size() - stored,
                openTrip == null ? null : openTrip.getTripId(),
                snapshotMoved == 1,
                newest.recordedAt());
    }


    private Car resolveCar(UUID userId, TelemetryDTO.Ingest request) {
        if (request.carId() != null) {
            return carAccess.readableBy(userId, request.carId())
                    .orElseThrow(() -> new CarNotFoundException(request.carId()));
        }
        String serial = DeviceService.normaliseSerial(request.serial());
        Device device = deviceRepository.findByDeviceSerial(serial)
                .orElseThrow(() -> new DeviceNotFoundException(serial));
        return carAccess.readableBy(userId, device.getDeviceCarId())
                .orElseThrow(() -> new DeviceNotFoundException(serial));
    }


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

    private static UUID tripIdFor(Reading reading, Trip openTrip) {
        if (openTrip == null || reading.recordedAt().isBefore(openTrip.getTripStartedAt())) {
            return null;
        }
        return openTrip.getTripId();
    }


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
