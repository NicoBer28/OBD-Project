package com.obd.api.devicetoken.exception;

public class DeviceNotAllowedException extends RuntimeException {

    public DeviceNotAllowedException(String detail) {
        super(detail);
    }
}
