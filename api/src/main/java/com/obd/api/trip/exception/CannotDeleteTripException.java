package com.obd.api.trip.exception;

import java.util.UUID;

public class CannotDeleteTripException extends RuntimeException {

    private static final String MESSAGE = "Can not finish Trip, id: %s";

    public CannotDeleteTripException(UUID tripId) {
        super(MESSAGE.formatted(tripId));
    }
}
