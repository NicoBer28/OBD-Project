package com.obd.api.trip.exception;

import java.util.UUID;

public class CarAlreadyOnATripException extends RuntimeException {

    private final static String MESSAGE = "Car %s already has an active trip";

    public CarAlreadyOnATripException(UUID carId) {
        super(MESSAGE.formatted(carId));
    }
}
