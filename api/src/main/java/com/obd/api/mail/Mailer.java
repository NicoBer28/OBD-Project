package com.obd.api.mail;

/**
 * How a message leaves the application - the seam between "what to say" and
 * "who delivers it".
 *
 * Flows depend on this interface and never on a provider, so the same code
 * logs in development, records in tests and calls a real API in production,
 * chosen by {@code app.mail.provider} at startup.
 *
 * Implementations must be best-effort: a send that fails throws, and every
 * caller is expected to catch, log and carry on. Losing a verification email
 * is a resend away; failing the request that triggered it is not.
 */
public interface Mailer {

    void send(Email email);
}
