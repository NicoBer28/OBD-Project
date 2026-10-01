package com.obd.api.auth.token.exception;

/**
 * A new link was asked for before the throttle window elapsed.
 *
 * Without this the resend endpoint is a way to flood someone's inbox, and
 * every extra live link is another chance for an old one to be read by the
 * wrong person.
 */
public class TokenRequestedTooSoonException extends RuntimeException {

    private static final String MESSAGE = "Another link was sent less than %d seconds ago";

    public TokenRequestedTooSoonException(long throttleSeconds) {
        super(MESSAGE.formatted(throttleSeconds));
    }
}
