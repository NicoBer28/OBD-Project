package com.obd.api.trip;

import com.obd.api.car.Car;
import com.obd.api.car.CarRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.trip.dto.TripDTO;
import com.obd.api.trip.exception.CarAlreadyOnATripException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripService {

    private final TripRepository tripRepository;
    private final CarRepository carRepository;

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
        Car car = carRepository.findById(request.carId())
                // Not-mine and not-there give the same answer on purpose: a 403
                // for someone else's car would confirm that the id exists.
                // Ownership is the whole rule only until cars can be shared with
                // a group; that endpoint widens this check to group members.
                .filter(c -> driverId.equals(c.getCarOwnerId()))
                .orElseThrow(() -> new CarNotFoundException(request.carId()));

        // Checked here for the sake of a clear 409 rather than a 500 - the
        // actual guarantee is ux_trips_one_active_per_car, which is what stops
        // two simultaneous requests from both opening a trip.
        tripRepository.findByTripCarIdAndTripEndedAtIsNull(car.getCarId())
                .ifPresent(active -> {
                    throw new CarAlreadyOnATripException(car.getCarId());
                });

        Trip trip = Trip.builder()
                .tripCarId(car.getCarId())
                .tripDriverId(driverId)
                // Falls back to the car's cached fuel level so a client that
                // cannot read the gauge still gets a usable expense, instead of
                // a trip whose consumption is permanently unknowable.
                .tripInitialFuel(request.initialFuel() != null
                        ? request.initialFuel()
                        : car.getCarFuelLevel())
                .build();

        Trip saved;
        try {
            // saveAndFlush (not save): the INSERT has to hit the database inside
            // this try block, or a lost race for the unique index would surface
            // at commit time, outside the catch.
            saved = tripRepository.saveAndFlush(trip);
        } catch (DataIntegrityViolationException e) {
            // The car was checked above and every value written here is already
            // validated, so the only constraint left to lose is the one-active-
            // trip index - another request opened a trip in between.
            throw new CarAlreadyOnATripException(car.getCarId());
        }

        return TripDTO.Read.from(saved);
    }
}
