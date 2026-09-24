package com.obd.api.car.exception;

public class LicensePlateAlreadyRegisteredException extends RuntimeException {

    private final static String MESSAGE = "Caller already has a car with plate: %s";

    public LicensePlateAlreadyRegisteredException(String licensePlate) {
        super(MESSAGE.formatted(licensePlate));
    }
}
