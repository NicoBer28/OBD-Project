package com.obd.api.mail;

/**
 * "Somebody needs this email" - published inside the transaction that wrote
 * the token, delivered after it commits.
 *
 * Why an event rather than a direct call: a provider call inside a
 * transaction holds a database connection open across the network (five of
 * them, on the dev pooler), and if that transaction then rolls back the mail
 * has already gone out for something that did not happen. Publishing is
 * cheap and cannot fail; {@link AccountMailListener} sends once the data is
 * durable.
 */
public sealed interface MailRequest {

    String to();
    String firstName();
    String rawToken();

    record Verification(String to, String firstName, String rawToken) implements MailRequest {}

    record PasswordReset(String to, String firstName, String rawToken) implements MailRequest {}
}
