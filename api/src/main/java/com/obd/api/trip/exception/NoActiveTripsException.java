package com.obd.api.trip.exception;

import java.util.UUID;

public class NoActiveTripsException extends RuntimeException {

    private static final String MESSAGE = "Not active trips for car, id: %s";

    public NoActiveTripsException(UUID carId) {
        super(MESSAGE.formatted(carId));
    }
}
