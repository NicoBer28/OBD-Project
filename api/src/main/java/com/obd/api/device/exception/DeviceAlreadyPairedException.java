package com.obd.api.device.exception;

/** The serial is paired to a different car. Unpair it there first. */
public class DeviceAlreadyPairedException extends RuntimeException {

    private static final String MESSAGE = "Device %s is already paired to another car";

    public DeviceAlreadyPairedException(String serial) {
        super(MESSAGE.formatted(serial));
    }
}
