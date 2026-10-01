package com.obd.api.mail;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);

    private final Resend resend;
    private final String sender;


    @Autowired
    public ResendMailer(@Value("${app.mail.resend.api-key:}") String apiKey,
                        @Value("${app.mail.from}") String from,
                        @Value("${app.mail.from-name}") String fromName,
                        @Value("${app.mail.resend.timeout-seconds:10}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.mail.provider is 'resend' but app.mail.resend.api-key is empty "
                            + "(set RESEND_API_KEY, or leave app.mail.provider unset to log instead)");
        }
        this.sender = senderAddress(from, fromName);
        this.resend = Resend.builder()
                .apiKey(apiKey)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .writeTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    ResendMailer(Resend resend, String from, String fromName) {
        this.resend = resend;
        this.sender = senderAddress(from, fromName);
    }

    @Override
    public void send(Email email) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(sender)
                .to(email.to())
                .subject(email.subject())
                .text(email.body())
                .build();

        try {
            CreateEmailResponse response = resend.emails().send(options, requestOptions(email));
            log.info("Sent {} mail to {} (resend id {})", email.purpose(), email.to(), response.getId());
        } catch (ResendException e) {
            throw new MailSendFailedException(email.purpose(), email.to(), e);
        }
    }

    private RequestOptions requestOptions(Email email) {
        String fingerprint = email.to() + '|' + email.subject() + '|' + email.body();
        return RequestOptions.builder()
                .setIdempotencyKey(UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString())
                .build();
    }

    static String senderAddress(String from, String fromName) {
        if (fromName == null || fromName.isBlank()) {
            return from;
        }
        return "%s <%s>".formatted(fromName.trim(), from.trim());
    }
}
