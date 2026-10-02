package com.obd.api.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log", matchIfMissing = true)
public class LoggingMailer implements Mailer {

    public static final String MARKER = "MAIL";

    private static final Set<String> NOT_REAL_TRAFFIC = Set.of("dev", "test");

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);


    @Autowired
    public LoggingMailer(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.stream().noneMatch(NOT_REAL_TRAFFIC::contains)) {
            throw new IllegalStateException(
                    "app.mail.provider is 'log' (or unset) and no dev/test profile is active, "
                            + "so verification and password-reset links would be written to this log "
                            + "and never sent - every account that registers would be locked out, "
                            + "since none could confirm its address. Set MAIL_PROVIDER=resend with "
                            + "RESEND_API_KEY, MAIL_FROM, MAIL_VERIFY_URL_BASE and MAIL_RESET_URL_BASE; "
                            + "for local use set SPRING_PROFILES_ACTIVE=dev instead. Active profiles: "
                            + (active.isEmpty() ? "(none)" : String.join(",", active)));
        }
    }


    LoggingMailer() {
    }

    @Override
    public void send(Email email) {
        log.info("{} to={} purpose={} subject={}", MARKER, email.to(), email.purpose(), email.subject());
        log.info("{} body to={}\n{}", MARKER, email.to(), email.body());
    }
}
