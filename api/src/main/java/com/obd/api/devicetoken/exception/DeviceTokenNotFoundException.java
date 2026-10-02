package com.obd.api.devicetoken.exception;

import java.util.UUID;

public class DeviceTokenNotFoundException extends RuntimeException {

    private static final String MESSAGE = "No device token %s for this user";

    public DeviceTokenNotFoundException(UUID tokenId) {
        super(MESSAGE.formatted(tokenId));
    }
}
