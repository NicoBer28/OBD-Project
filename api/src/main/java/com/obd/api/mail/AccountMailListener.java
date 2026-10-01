package com.obd.api.mail;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AccountMailListener {

    private static final Logger log = LoggerFactory.getLogger(AccountMailListener.class);

    private final Mailer mailer;
    private final MailMessages messages;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MailRequest request) {
        Email email = switch (request) {
            case MailRequest.Verification v -> messages.verification(v.to(), v.firstName(), v.rawToken());
            case MailRequest.PasswordReset r -> messages.passwordReset(r.to(), r.firstName(), r.rawToken());
        };

        try {
            mailer.send(email);
        } catch (RuntimeException e) {
            log.error("Could not send {} mail to {}", email.purpose(), email.to(), e);
        }
    }
}
