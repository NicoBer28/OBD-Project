package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.trip.dto.TripDTO;
import com.obd.api.trip.exception.*;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripService {

    private final TripRepository tripRepository;
    private final CarAccess carAccess;

    /**
     * Starts a trip: records that {@code driverId} is now using the car, and
     * leaves it open until the trip is finished.
     *
     * The driver is the authenticated caller, never a field of the request -
     * otherwise anyone could log a trip in someone else's name and inherit its
     * fuel expense.
     */
    @Transactional
    public TripDTO.Read start(UUID driverId, TripDTO.Create request) {
        Car car = carAccess.readableBy(driverId, request.carId())
                .orElseThrow(() -> new CarNotFoundException(request.carId()));

        tripRepository.findByTripCarIdAndTripEndedAtIsNull(car.getCarId())
                .ifPresent(active -> {
                    throw new CarAlreadyOnATripException(car.getCarId());
                });

        Trip trip = Trip.builder()
                .tripCarId(car.getCarId())
                .tripDriverId(driverId)
                .tripInitialFuel(request.initialFuel() != null
                        ? request.initialFuel()
                        : car.getCarFuelLevel())
                .build();

        Trip saved;
        try {
            saved = tripRepository.saveAndFlush(trip);
        } catch (DataIntegrityViolationException e) {
            throw new CarAlreadyOnATripException(car.getCarId());
        }

        return TripDTO.Read.from(saved);
    }

    public List<TripDTO.Read> getTrips(UUID userId ){
        return tripRepository.findByTripDriverIdOrderByTripStartedAtDesc(userId).stream().map(TripDTO.Read::from).toList();
    }

    // A car the caller may not read answers exactly like one that does not
    // exist (CarNotFoundException, "No such car"), so the id is not confirmed.
    public List<TripDTO.Read> getCarTrips(UUID userId, UUID carId){
        Car car = carAccess.readableBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        return tripRepository.findByTripCarIdOrderByTripStartedAtDesc(car.getCarId()).stream().map(TripDTO.Read::from).toList();
    }

    /**
     * The car's open trip, if any. Empty is the normal answer - a parked car
     * has none - so it is an Optional the controller turns into 204, not an
     * exception. Whoever may read the car may see who is driving it.
     */
    public Optional<TripDTO.Read> active(UUID userId, UUID carId){

        Car car = carAccess.readableBy(userId, carId).orElseThrow(() -> new CarNotFoundException(carId));

        return tripRepository.findByTripCarIdAndTripEndedAtIsNull(car.getCarId()).map(TripDTO.Read::from);
    }

    @Transactional
    public TripDTO.Read finish(UUID userId, UUID tripId, TripDTO.@Valid finish tripFinish) {
        if(tripRepository.finish(tripId, userId, Instant.now(), tripFinish.tripFinalFuel(), tripFinish.tripDistance()) == 0){
            Trip trip = tripRepository.findById(tripId)
                    .filter(t -> t.getTripDriverId().equals(userId))
                    .orElseThrow(()-> new TripNotFoundException(tripId));

            throw new TripAlreadyEndedException(tripId);
        }

        return TripDTO.Read.from(tripRepository.findById(tripId).orElse(new Trip()));
    }


    /**
     * Cancels an open trip of the caller's own, as if it never started.
     *
     * The delete is conditional (own + open) and refusals are told apart the
     * same way {@link #finish} does: unknown or someone else's trip is a 404,
     * a trip that already ended is a 409 - finished trips are history and
     * carry an expense, so they are never deleted.
     */
    @Transactional
    public void delete(UUID userId, UUID tripId){
        if (tripRepository.deleteTripByTripIdAndTripDriverIdAndTripEndedAtIsNull(tripId, userId).isEmpty()) {
            tripRepository.findById(tripId)
                    .filter(t -> t.getTripDriverId().equals(userId))
                    .orElseThrow(() -> new TripNotFoundException(tripId));

            throw new TripAlreadyEndedException(tripId);
        }
    }
}
