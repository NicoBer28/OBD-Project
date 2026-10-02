package com.obd.api.auth.reset;

import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.auth.token.SecretTokens;
import com.obd.api.devicetoken.DeviceTokenRepository;
import com.obd.api.auth.token.TokenKind;
import com.obd.api.auth.token.exception.TokenNoLongerValidException;
import com.obd.api.auth.token.exception.TokenNotFoundException;
import com.obd.api.mail.MailRequest;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import com.obd.api.user.exception.UserNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;


@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ApplicationEventPublisher events;
    private final long ttlMinutes;
    private final long throttleSeconds;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokenService,
                                DeviceTokenRepository deviceTokenRepository,
                                ApplicationEventPublisher events,
                                @Value("${app.mail.reset-ttl-minutes}") long ttlMinutes,
                                @Value("${app.mail.resend-throttle-seconds}") long throttleSeconds) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.deviceTokenRepository = deviceTokenRepository;
        this.events = events;
        this.ttlMinutes = ttlMinutes;
        this.throttleSeconds = throttleSeconds;
    }


    @Transactional
    public void request(String rawEmail) {
        String email = rawEmail.trim().toLowerCase();
        Instant now = Instant.now();

        User user = userRepository.findByUserEmail(email).orElse(null);
        if (user == null) {
            log.debug("Password reset requested for an address with no account");
            return;
        }

        boolean throttled = tokenRepository.findByTokenUserIdAndTokenConsumedAtIsNull(user.getUserId())
                .filter(live -> live.getTokenCreatedAt().isAfter(now.minusSeconds(throttleSeconds)))
                .isPresent();
        if (throttled) {
            log.debug("Password reset throttled for an account");
            return;
        }

        tokenRepository.consumeLiveFor(user.getUserId(), now);

        String raw = SecretTokens.mint();
        tokenRepository.saveAndFlush(PasswordResetToken.builder()
                .tokenUserId(user.getUserId())
                .tokenHash(SecretTokens.hash(raw))
                .tokenCreatedAt(now)
                .tokenExpiresAt(now.plus(ttlMinutes, ChronoUnit.MINUTES))
                .build());

        events.publishEvent(new MailRequest.PasswordReset(
                user.getUserEmail(), user.getUserName(), raw));
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        Instant now = Instant.now();
        String hash = SecretTokens.hash(rawToken);

        if (tokenRepository.consume(hash, now) == 0) {
            tokenRepository.findByTokenHash(hash)
                    .orElseThrow(() -> new TokenNotFoundException(TokenKind.RESET));
            throw new TokenNoLongerValidException(TokenKind.RESET);
        }

        PasswordResetToken token = tokenRepository.findByTokenHash(hash).orElseThrow();
        User user = userRepository.findById(token.getTokenUserId())
                .orElseThrow(() -> new UserNotFoundException(token.getTokenUserId()));

        user.setUserPasswordHash(passwordEncoder.encode(newPassword));
        user.setUserPasswordChangedAt(now.truncatedTo(ChronoUnit.SECONDS));
        if (user.getUserEmailVerifiedAt() == null) {
            user.setUserEmailVerifiedAt(now);
        }
        userRepository.saveAndFlush(user);

        refreshTokenService.revokeAllForUser(user.getUserId());
        deviceTokenRepository.revokeAllForUser(user.getUserId(), now);
    }
}
