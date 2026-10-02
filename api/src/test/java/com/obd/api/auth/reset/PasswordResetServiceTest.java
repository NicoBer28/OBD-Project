package com.obd.api.auth.reset;

import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.auth.token.exception.TokenNoLongerValidException;
import com.obd.api.auth.token.exception.TokenNotFoundException;
import com.obd.api.mail.MailRequest;
import com.obd.api.support.RecordedMail;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Forgot-password against a real database.
 *
 * A real BCrypt encoder, because what the stored hash ends up being is the
 * point; a mocked RefreshTokenService, because revoking families is its own
 * concern and what matters here is that it is asked to.
 */
@RepositoryTest
@Import({PasswordResetService.class, RecordedMail.class, PasswordResetServiceTest.RealEncoder.class})
@TestPropertySource(properties = {
        "app.mail.reset-ttl-minutes=30",
        "app.mail.resend-throttle-seconds=60"
})
class PasswordResetServiceTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RealEncoder {
        // Cheap rounds: these tests hash a lot and none of it is a secret.
        @org.springframework.context.annotation.Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    private static final String OLD_PASSWORD = "supersecret123";
    private static final String NEW_PASSWORD = "evenbetter456";

    @Autowired
    private PasswordResetService resetService;
    @Autowired
    private PasswordResetTokenRepository tokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RecordedMail.Recorder mail;
    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private UUID adaId;

    @BeforeEach
    void ada() {
        mail.clear();
        adaId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace").userEmail("ada@example.com")
                .userPasswordHash(passwordEncoder.encode(OLD_PASSWORD))
                .userEmailVerifiedAt(Instant.now())
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private User reload() {
        return userRepository.findById(adaId).orElseThrow();
    }

    private String mailedToken() {
        assertThat(mail.latest()).isInstanceOf(MailRequest.PasswordReset.class);
        return mail.latestToken();
    }

    /** Ages the live token; its timestamps are updatable = false on the entity. */
    private void age(Duration by) {
        entityManager.createNativeQuery("""
                        update password_reset_tokens
                           set created_at = created_at - cast(:shift as interval),
                               expires_at = expires_at - cast(:shift as interval)
                         where user_id = :userId and consumed_at is null
                        """)
                .setParameter("shift", by.toSeconds() + " seconds")
                .setParameter("userId", adaId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    // --- request -------------------------------------------------------------

    @Test
    void requestingSendsALinkToTheAddressOnFile() {
        resetService.request("ada@example.com");

        assertThat(mail.all()).singleElement()
                .isInstanceOf(MailRequest.PasswordReset.class)
                .satisfies(request -> {
                    assertThat(request.to()).isEqualTo("ada@example.com");
                    assertThat(request.firstName()).isEqualTo("Ada");
                });
        assertThat(tokenRepository.count()).isEqualTo(1);
        assertThat(tokenRepository.findAll().getFirst().getTokenExpiresAt())
                .isBetween(Instant.now().plus(29, ChronoUnit.MINUTES),
                        Instant.now().plus(30, ChronoUnit.MINUTES).plusSeconds(5));
    }

    @Test
    void theAddressIsNormalisedBeforeItIsLookedUp() {
        resetService.request("  ADA@Example.COM  ");

        assertThat(mail.all()).hasSize(1);
    }

    @Test
    void anUnregisteredAddressLooksExactlyLikeASuccess() {
        // No exception, no token, no mail - and, crucially, nothing a caller
        // can tell apart from the case above. This endpoint must not answer
        // "does this person have an account here".
        resetService.request("nobody@example.com");

        assertThat(mail.isEmpty()).isTrue();
        assertThat(tokenRepository.count()).isZero();
    }

    @Test
    void askingTwiceInAMomentQuietlySendsOnce() {
        resetService.request("ada@example.com");
        resetService.request("ada@example.com");

        // Throttled in silence, unlike the resend endpoint: a 429 here would
        // confirm the address exists.
        assertThat(mail.all()).hasSize(1);
        assertThat(tokenRepository.count()).isEqualTo(1);
    }

    @Test
    void askingAgainLaterRetiresTheEarlierLink() {
        resetService.request("ada@example.com");
        String first = mailedToken();
        age(Duration.ofMinutes(2));

        resetService.request("ada@example.com");
        String second = mailedToken();

        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> resetService.reset(first, NEW_PASSWORD))
                .isInstanceOf(TokenNoLongerValidException.class);
        resetService.reset(second, NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reload().getUserPasswordHash())).isTrue();
    }

    // --- reset ---------------------------------------------------------------

    @Test
    void theLinkSetsTheNewPasswordAndRetiresTheOldOne() {
        resetService.request("ada@example.com");

        resetService.reset(mailedToken(), NEW_PASSWORD);

        User after = reload();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, after.getUserPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, after.getUserPasswordHash())).isFalse();
    }

    @Test
    void resettingSignsEverySessionOut() {
        resetService.request("ada@example.com");

        resetService.reset(mailedToken(), NEW_PASSWORD);

        // Everywhere, with no exception for whoever is doing the reset: the
        // account may have been in someone else's hands.
        verify(refreshTokenService).revokeAllForUser(adaId);
        // And the stamp that reaches access tokens already issued.
        assertThat(reload().getUserPasswordChangedAt())
                .isNotNull()
                .isEqualTo(reload().getUserPasswordChangedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void reachingTheMailboxCountsAsVerifyingTheAddress() {
        User unverified = userRepository.saveAndFlush(User.builder()
                .userName("Grace").userLastName("Hopper").userEmail("grace@example.com")
                .userPasswordHash(passwordEncoder.encode(OLD_PASSWORD))
                .role(Role.USER).enabled(true).build());
        resetService.request("grace@example.com");

        resetService.reset(mailedToken(), NEW_PASSWORD);

        // Reading the link proves ownership just as well as the verification
        // flow does, so the gate lifts too - otherwise somebody who reset
        // their password would still be walled out of the application with
        // nothing left to click.
        assertThat(userRepository.findById(unverified.getUserId()).orElseThrow()
                .getUserEmailVerifiedAt()).isNotNull();
    }

    @Test
    void aLinkWorksOnlyOnce() {
        resetService.request("ada@example.com");
        String raw = mailedToken();
        resetService.reset(raw, NEW_PASSWORD);

        assertThatThrownBy(() -> resetService.reset(raw, "thirdtime789"))
                .isInstanceOf(TokenNoLongerValidException.class);
        // The second attempt changed nothing.
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reload().getUserPasswordHash())).isTrue();
    }

    @Test
    void anUnknownLinkIsNotFoundAndChangesNothing() {
        String hashBefore = reload().getUserPasswordHash();

        assertThatThrownBy(() -> resetService.reset("not-a-real-token", NEW_PASSWORD))
                .isInstanceOf(TokenNotFoundException.class);

        assertThat(reload().getUserPasswordHash()).isEqualTo(hashBefore);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void anExpiredLinkIsRefused() {
        resetService.request("ada@example.com");
        String raw = mailedToken();
        age(Duration.ofHours(1));

        assertThatThrownBy(() -> resetService.reset(raw, NEW_PASSWORD))
                .isInstanceOf(TokenNoLongerValidException.class);
        assertThat(passwordEncoder.matches(OLD_PASSWORD, reload().getUserPasswordHash())).isTrue();
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void oneAccountsLinkNeverResetsAnother() {
        User grace = userRepository.saveAndFlush(User.builder()
                .userName("Grace").userLastName("Hopper").userEmail("grace@example.com")
                .userPasswordHash(passwordEncoder.encode(OLD_PASSWORD))
                .role(Role.USER).enabled(true).build());

        resetService.request("ada@example.com");
        resetService.reset(mailedToken(), NEW_PASSWORD);

        assertThat(passwordEncoder.matches(OLD_PASSWORD,
                userRepository.findById(grace.getUserId()).orElseThrow().getUserPasswordHash())).isTrue();
    }

    @Test
    void theStoredRowNeverContainsTheLink() {
        resetService.request("ada@example.com");
        String raw = mailedToken();

        PasswordResetToken stored = tokenRepository.findAll().getFirst();
        assertThat(stored.getTokenHash()).isNotEqualTo(raw).hasSize(64);
    }
}
