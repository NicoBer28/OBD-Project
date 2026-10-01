package com.obd.api.mail;

/**
 * The provider refused or could not be reached.
 *
 * Carries the recipient and the purpose and nothing else - in particular not
 * the provider's response body, which can echo the request, and the request
 * contains the live link.
 *
 * Nobody handles this: {@link AccountMailListener} catches and logs it, since
 * by then the transaction has committed and there is no one left to tell. The
 * resend endpoint is how a user recovers.
 */
public class MailSendFailedException extends RuntimeException {

    private static final String MESSAGE = "Could not send %s mail to %s";

    public MailSendFailedException(Email.Purpose purpose, String to, Throwable cause) {
        super(MESSAGE.formatted(purpose, to), cause);
    }
}
