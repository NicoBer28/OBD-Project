package com.obd.api.device.exception;

/**
 * Raised when a serial is unknown <em>or</em> belongs to a car the caller may
 * not see. Indistinguishable on purpose: a phone that is not a member of the
 * family must not be able to confirm that a dongle exists.
 */
public class DeviceNotFoundException extends RuntimeException {

    private static final String MESSAGE = "No device with serial: %s";

    public DeviceNotFoundException(String serial) {
        super(MESSAGE.formatted(serial));
    }
}
