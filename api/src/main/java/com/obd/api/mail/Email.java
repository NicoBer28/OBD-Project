package com.obd.api.mail;

/**
 * One outgoing message, already rendered.
 *
 * {@code purpose} is not part of the message: it labels the send for logs and
 * for the {@link LoggingMailer}'s parseable output, so a development run can
 * tell a verification mail from a reset without reading the body.
 */
public record Email(
        String to,
        String subject,
        String body,
        Purpose purpose
) {
    public enum Purpose { EMAIL_VERIFICATION, PASSWORD_RESET }
}
