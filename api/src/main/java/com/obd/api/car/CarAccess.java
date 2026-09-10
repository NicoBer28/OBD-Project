package com.obd.api.car;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides who may do what with a car.
 *
 * Every endpoint that touches a car goes through here rather than comparing
 * owner ids inline, so that when sharing lands (ROADMAP Phase 4) the rule
 * changes in one method instead of in every service. Both methods answer
 * "not yours" and "does not exist" identically - an empty Optional - because
 * a caller must not be able to tell the two apart (see CarNotFoundException).
 */
@Component
@RequiredArgsConstructor
public class CarAccess {

    private final CarRepository carRepository;

    /**
     * Read-level access: enough to see the car, drive it, and upload what it
     * reports. Owner only until cars can be shared with a group, at which point
     * this widens to the group's members. Deliberately the same rule as
     * {@link #ownedBy} for now - the split exists so callers already say which
     * one they mean.
     */
    public Optional<Car> readableBy(UUID userId, UUID carId) {
        return carRepository.findById(carId)
                .filter(car -> userId.equals(car.getCarOwnerId()));
    }

    /**
     * Owner-level access: share, un-share, rename, delete. Never widens to
     * group members.
     */
    public Optional<Car> ownedBy(UUID userId, UUID carId) {
        return carRepository.findById(carId)
                .filter(car -> userId.equals(car.getCarOwnerId()));
    }
}
