package com.obd.api.mail;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log", matchIfMissing = true)
@RequiredArgsConstructor
public class LoggingMailer implements Mailer {

    public static final String MARKER = "MAIL";

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(Email email) {
        log.info("{} to={} purpose={} subject={}", MARKER, email.to(), email.purpose(), email.subject());
        log.info("{} body to={}\n{}", MARKER, email.to(), email.body());
    }
}
