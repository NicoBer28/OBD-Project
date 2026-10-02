package com.obd.api.trip.exception;

import lombok.Getter;

import java.time.Instant;

@Getter
public class TripTimeOutOfRangeException extends RuntimeException {

    private static final String MESSAGE = "%s %s is %s";

    private final String field;
    private final String reason;

    public TripTimeOutOfRangeException(String field, Instant claimed, String reason) {
        super(MESSAGE.formatted(field, claimed, reason));
        this.field = field;
        this.reason = reason;
    }
}
