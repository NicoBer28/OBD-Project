package com.obd.api.car.exception;

import java.util.UUID;

/**
 * Raised when a car does not exist <em>or</em> the caller has no access to it.
 * The two cases are deliberately indistinguishable: answering 403 for someone
 * else's car would confirm that the id exists.
 */
public class CarNotFoundException extends RuntimeException {

    private final static String MESSAGE = "No car with id: %s";

    public CarNotFoundException(UUID carId) {
        super(MESSAGE.formatted(carId));
    }
}
