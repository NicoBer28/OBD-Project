package com.obd.api.user.exception;

public class PasswordUnchangedException extends RuntimeException {

    public PasswordUnchangedException() {
        super("The new password must be different from the current one");
    }
}
