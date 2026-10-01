package com.obd.api.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log line is a contract, not decoration: scripts/test-endpoints.sh greps
 * it to recover a link and drive the verification and reset flows against a
 * running server. A well-meaning tidy-up of the message would break the smoke
 * suite in a way that is genuinely hard to trace, so the format is pinned
 * here.
 */
class LoggingMailerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(LoggingMailer.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    @Test
    void theRecipientAndPurposeAreOnOneGreppableLine() {
        new LoggingMailer().send(new Email("ada@example.com", "Confirma tu correo",
                "Entra aca:\nhttps://example.test/verify-email/tok-123\n",
                Email.Purpose.EMAIL_VERIFICATION));

        String header = appender.list.getFirst().getFormattedMessage();
        assertThat(header)
                .startsWith(LoggingMailer.MARKER + " ")
                .contains("to=ada@example.com")
                .contains("purpose=EMAIL_VERIFICATION");
    }

    @Test
    void theBodyIsLoggedWholeSoTheLinkCanBeRecovered() {
        new LoggingMailer().send(new Email("ada@example.com", "Restablece tu contrasena",
                "Entra aca:\nhttps://example.test/reset-password/tok-456\n",
                Email.Purpose.PASSWORD_RESET));

        String body = appender.list.getLast().getFormattedMessage();
        assertThat(body)
                .contains(LoggingMailer.MARKER + " body to=ada@example.com")
                .contains("https://example.test/reset-password/tok-456");
    }

    @Test
    void nothingIsSentAnywhere() {
        // The whole point: development and the test suite need no provider
        // account, no API key and no network.
        new LoggingMailer().send(new Email("ada@example.com", "s", "b", Email.Purpose.PASSWORD_RESET));

        assertThat(appender.list).hasSize(2);
    }
}
