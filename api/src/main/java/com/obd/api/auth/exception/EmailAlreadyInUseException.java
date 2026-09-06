package com.obd.api.auth.exception;

public class EmailAlreadyInUseException extends RuntimeException {

    private final static String MESSAGE = "Email already in use for: %s";

    public EmailAlreadyInUseException(String email) {
        super(MESSAGE.formatted(email));
    }
}
