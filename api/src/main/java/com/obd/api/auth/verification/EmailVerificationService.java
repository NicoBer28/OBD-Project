package com.obd.api.auth.verification;

import com.obd.api.auth.token.SecretTokens;
import com.obd.api.auth.token.TokenKind;
import com.obd.api.auth.token.exception.TokenNoLongerValidException;
import com.obd.api.auth.token.exception.TokenNotFoundException;
import com.obd.api.auth.token.exception.TokenRequestedTooSoonException;
import com.obd.api.mail.MailRequest;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import com.obd.api.user.exception.EmailAlreadyVerifiedException;
import com.obd.api.user.exception.UserNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;


@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final long ttlHours;
    private final long throttleSeconds;

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
                                    UserRepository userRepository,
                                    ApplicationEventPublisher events,
                                    @Value("${app.mail.verification-ttl-hours}") long ttlHours,
                                    @Value("${app.mail.resend-throttle-seconds}") long throttleSeconds) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.events = events;
        this.ttlHours = ttlHours;
        this.throttleSeconds = throttleSeconds;
    }


    @Transactional
    public void sendOnRegistration(User user) {
        issue(user);
    }

    @Transactional
    public void resend(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getUserEmailVerifiedAt() != null) {
            throw new EmailAlreadyVerifiedException();
        }

        tokenRepository.findByTokenUserIdAndTokenConsumedAtIsNull(userId)
                .filter(live -> live.getTokenCreatedAt()
                        .isAfter(Instant.now().minusSeconds(throttleSeconds)))
                .ifPresent(live -> {
                    throw new TokenRequestedTooSoonException(throttleSeconds);
                });

        issue(user);
    }

    @Transactional
    public void resendPublicly(String rawEmail) {
        String email = rawEmail.trim().toLowerCase();

        User user = userRepository.findByUserEmail(email).orElse(null);
        if (user == null || user.getUserEmailVerifiedAt() != null) {
            log.debug("Verification resend requested for an address with no unverified account");
            return;
        }

        boolean throttled = tokenRepository.findByTokenUserIdAndTokenConsumedAtIsNull(user.getUserId())
                .filter(live -> live.getTokenCreatedAt().isAfter(Instant.now().minusSeconds(throttleSeconds)))
                .isPresent();
        if (throttled) {
            log.debug("Verification resend throttled");
            return;
        }

        issue(user);
    }

    @Transactional
    public void verify(String rawToken) {
        Instant now = Instant.now();
        String hash = SecretTokens.hash(rawToken);

        if (tokenRepository.consume(hash, now) == 0) {
            // Zero rows means unknown, already used, or expired. Read the row
            // back to answer with the right one.
            tokenRepository.findByTokenHash(hash)
                    .orElseThrow(() -> new TokenNotFoundException(TokenKind.VERIFICATION));
            throw new TokenNoLongerValidException(TokenKind.VERIFICATION);
        }

        EmailVerificationToken token = tokenRepository.findByTokenHash(hash).orElseThrow();
        User user = userRepository.findById(token.getTokenUserId())
                .orElseThrow(() -> new UserNotFoundException(token.getTokenUserId()));

        // Idempotent in effect: a user who somehow holds two links keeps the
        // first timestamp rather than having it moved.
        if (user.getUserEmailVerifiedAt() == null) {
            user.setUserEmailVerifiedAt(now);
            userRepository.saveAndFlush(user);
        }
    }

    private void issue(User user) {
        Instant now = Instant.now();

        // Retire the previous link first: one live token per user, so an older
        // email stops working the moment a newer one is sent.
        tokenRepository.consumeLiveFor(user.getUserId(), now);

        String raw = SecretTokens.mint();
        tokenRepository.saveAndFlush(EmailVerificationToken.builder()
                .tokenUserId(user.getUserId())
                .tokenHash(SecretTokens.hash(raw))
                .tokenCreatedAt(now)
                .tokenExpiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .build());

        // Published, not sent: delivery happens after this transaction commits.
        events.publishEvent(new MailRequest.Verification(
                user.getUserEmail(), user.getUserName(), raw));
    }
}
