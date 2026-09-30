package com.obd.api.user.exception;


public class IncorrectPasswordException extends RuntimeException {

    public IncorrectPasswordException() {
        super("The current password is incorrect");
    }
}
