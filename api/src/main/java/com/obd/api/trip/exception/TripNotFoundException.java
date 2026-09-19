package com.obd.api.trip.exception;

import java.util.UUID;

public class TripNotFoundException extends RuntimeException {

    private static final String MESSAGE = "Trip, id: %s, not found";

    public TripNotFoundException(UUID tripId) {
        super(MESSAGE.formatted(tripId));
    }
}
