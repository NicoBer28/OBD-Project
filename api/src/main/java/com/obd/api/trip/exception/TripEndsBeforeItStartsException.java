package com.obd.api.trip.exception;

import java.time.Instant;
import java.util.UUID;

public class TripEndsBeforeItStartsException extends RuntimeException {

    private static final String MESSAGE = "Trip %s: endedAt %s is before startedAt %s";

    public TripEndsBeforeItStartsException(UUID tripId, Instant endedAt, Instant startedAt) {
        super(MESSAGE.formatted(tripId, endedAt, startedAt));
    }
}
