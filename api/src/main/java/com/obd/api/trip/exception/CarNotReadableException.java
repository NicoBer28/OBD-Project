package com.obd.api.trip.exception;

import java.util.UUID;

public class CarNotReadableException extends RuntimeException {

    private static final String MESSAGE = "Car is not readable";

    public CarNotReadableException() {
        super(MESSAGE);
    }
}
