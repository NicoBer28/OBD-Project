package com.obd.api.car;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides who may do what with a car.
 *
 * Every endpoint that touches a car goes through here rather than comparing
 * owner ids inline, so the rule lives in one definition - CarRepository.READABLE
 * - and the list a user sees, the trip they start and the telemetry they upload
 * all agree. The per-car methods answer "not yours" and "does not exist"
 * identically - an empty Optional - because a caller must not be able to tell
 * the two apart (see CarNotFoundException).
 */
@Component
@RequiredArgsConstructor
public class CarAccess {

    private final CarRepository carRepository;

    /**
     * Read-level access: enough to see the car, drive it, and upload what it
     * reports. The owner, or any member of the group the car is shared with.
     */
    public Optional<Car> readableBy(UUID userId, UUID carId) {
        return carRepository.findReadableBy(userId, carId);
    }

    /** Every car the user has read-level access to - what GET /cars lists. */
    public List<Car> allReadableBy(UUID userId) {
        return carRepository.findAllReadableBy(userId);
    }

    /**
     * Owner-level access: share, un-share, rename, delete. Deliberately never
     * widens to group members - a member may drive the car, not give it away.
     */
    public Optional<Car> ownedBy(UUID userId, UUID carId) {
        return carRepository.findById(carId)
                .filter(car -> userId.equals(car.getCarOwnerId()));
    }
}
