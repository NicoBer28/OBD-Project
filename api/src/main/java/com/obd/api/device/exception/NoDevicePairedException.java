package com.obd.api.device.exception;

import java.util.UUID;

/** The car is readable by the caller, but has no dongle paired. */
public class NoDevicePairedException extends RuntimeException {

    private static final String MESSAGE = "No device paired to car: %s";

    public NoDevicePairedException(UUID carId) {
        super(MESSAGE.formatted(carId));
    }
}
