package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.devicetoken.DeviceScope;
import com.obd.api.telemetry.Telemetry;
import com.obd.api.telemetry.TelemetryRepository;
import com.obd.api.trip.dto.TripDTO;
import com.obd.api.trip.exception.*;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripService {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private static final Duration BACKDATING_LIMIT = Duration.ofDays(30);

    private final TripRepository tripRepository;
    private final TelemetryRepository telemetryRepository;
    private final CarAccess carAccess;
    private final DeviceScope deviceScope;

    public record Started(TripDTO.Read trip, boolean created) {}

    @Transactional
    public Started start(UUID driverId, TripDTO.Create request) {
        Car car = carAccess.readableBy(driverId, request.carId())
                .orElseThrow(() -> new CarNotFoundException(request.carId()));

        deviceScope.requireCar(car.getCarId());

        if (request.clientTripId() != null) {
            Optional<Trip> alreadyStarted =
                    tripRepository.findByTripDriverIdAndTripClientTripId(driverId, request.clientTripId());
            if (alreadyStarted.isPresent()) {
                return new Started(TripDTO.Read.from(alreadyStarted.get()), false);
            }
        }

        Instant startedAt = startedAtFrom(request);

        tripRepository.findByTripCarIdAndTripEndedAtIsNull(car.getCarId())
                .ifPresent(active -> {
                    throw new CarAlreadyOnATripException(car.getCarId());
                });

        Trip trip = Trip.builder()
                .tripCarId(car.getCarId())
                .tripDriverId(driverId)
                .tripStartedAt(startedAt)
                .tripClientTripId(request.clientTripId())
                .tripInitialFuel(request.initialFuel() != null
                        ? request.initialFuel()
                        : car.getCarFuelLevel())
                .build();

        try {
            return new Started(TripDTO.Read.from(tripRepository.saveAndFlush(trip)), true);
        } catch (DataIntegrityViolationException e) {

            if (request.clientTripId() != null) {
                Optional<Trip> won = tripRepository
                        .findByTripDriverIdAndTripClientTripId(driverId, request.clientTripId());
                if (won.isPresent()) {
                    return new Started(TripDTO.Read.from(won.get()), false);
                }
            }
            throw new CarAlreadyOnATripException(car.getCarId());
        }
    }

    public List<TripDTO.Read> getTrips(UUID userId ){
        return tripRepository.findByTripDriverIdOrderByTripStartedAtDesc(userId).stream().map(TripDTO.Read::from).toList();
    }

    public List<TripDTO.Read> getCarTrips(UUID userId, UUID carId){
        Car car = carAccess.readableBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        return tripRepository.findByTripCarIdOrderByTripStartedAtDesc(car.getCarId()).stream().map(TripDTO.Read::from).toList();
    }


    public Optional<TripDTO.Read> active(UUID userId, UUID carId){

        Car car = carAccess.readableBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        deviceScope.requireCar(car.getCarId());

        return tripRepository.findByTripCarIdAndTripEndedAtIsNull(car.getCarId()).map(TripDTO.Read::from);
    }

    @Transactional
    public List<TripDTO.RoutePoint> route(UUID userId, UUID tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        carAccess.readableBy(userId, trip.getTripCarId())
                .orElseThrow(() -> new TripNotFoundException(tripId));

        Instant until = trip.getTripEndedAt() != null ? trip.getTripEndedAt() : Instant.now();

        return telemetryRepository
                .findRoute(trip.getTripCarId(), trip.getTripStartedAt(), until)
                .stream()
                .map(TripService::point)
                .toList();
    }

    @Transactional
    public TripDTO.Read finish(UUID userId, UUID tripId, TripDTO.@Valid finish tripFinish) {
        tripRepository.findById(tripId)
                .ifPresent(trip -> deviceScope.requireCar(trip.getTripCarId()));

        Instant endedAt = tripFinish.endedAt() != null
                ? validated(tripFinish.endedAt(), "endedAt")
                : Instant.now();

        if(tripRepository.finish(tripId, userId, endedAt, tripFinish.tripFinalFuel(), tripFinish.distanceKm()) == 0){
            Trip trip = tripRepository.findById(tripId)
                    .filter(t -> t.getTripDriverId().equals(userId))
                    .orElseThrow(()-> new TripNotFoundException(tripId));

            if (trip.getTripEndedAt() != null) {
                throw new TripAlreadyEndedException(tripId);
            }
            throw new TripEndsBeforeItStartsException(tripId, endedAt, trip.getTripStartedAt());
        }

        return TripDTO.Read.from(tripRepository.findById(tripId).orElse(new Trip()));
    }


    @Transactional
    public void delete(UUID userId, UUID tripId){
        if (tripRepository.deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(tripId, userId).isEmpty()) {
            tripRepository.findById(tripId)
                    .filter(t -> t.getTripDriverId().equals(userId))
                    .orElseThrow(() -> new TripNotFoundException(tripId));

            throw new TripAlreadyEndedException(tripId);
        }
    }

    private Instant startedAtFrom(TripDTO.Create request) {
        return request.startedAt() != null ? validated(request.startedAt(), "startedAt") : Instant.now();
    }

    private static Instant validated(Instant claimed, String field) {
        Instant now = Instant.now();
        if (claimed.isAfter(now.plus(CLOCK_SKEW))) {
            throw new TripTimeOutOfRangeException(field, claimed, "more than 5 minutes in the future");
        }
        if (claimed.isBefore(now.minus(BACKDATING_LIMIT))) {
            throw new TripTimeOutOfRangeException(field, claimed, "more than 30 days old");
        }
        return claimed;
    }

    private static TripDTO.RoutePoint point(Telemetry reading) {
        return new TripDTO.RoutePoint(
                reading.getTelemetryRecordedAt(),
                reading.getTelemetryLocation().getLatitude(),
                reading.getTelemetryLocation().getLongitude(),
                reading.getTelemetrySpeed());
    }
}
