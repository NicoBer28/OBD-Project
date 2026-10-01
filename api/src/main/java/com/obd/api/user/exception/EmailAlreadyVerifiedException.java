package com.obd.api.user.exception;

/** A verification link was asked for by an account that is already verified. */
public class EmailAlreadyVerifiedException extends RuntimeException {

    public EmailAlreadyVerifiedException() {
        super("This email address is already verified");
    }
}
