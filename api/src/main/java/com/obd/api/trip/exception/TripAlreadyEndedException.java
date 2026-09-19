package com.obd.api.trip.exception;

import java.util.UUID;

public class TripAlreadyEndedException extends RuntimeException {

    private static final String MESSAGE = "Trip, id: %s, already ended";

    public TripAlreadyEndedException(UUID tripId) {
        super(MESSAGE.formatted(tripId));
    }
}
