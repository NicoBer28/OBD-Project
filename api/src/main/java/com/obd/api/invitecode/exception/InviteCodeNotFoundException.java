package com.obd.api.invitecode.exception;

/**
 * No code with that value. Carries no detail about the code itself - the
 * value is a secret, and it has no place in a log line or an error body.
 */
public class InviteCodeNotFoundException extends RuntimeException {

    public InviteCodeNotFoundException() {
        super("No such invite code");
    }
}
