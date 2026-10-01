package com.obd.api.auth.verification;

import com.obd.api.auth.token.exception.TokenNoLongerValidException;
import com.obd.api.auth.token.exception.TokenNotFoundException;
import com.obd.api.auth.token.exception.TokenRequestedTooSoonException;
import com.obd.api.mail.MailRequest;
import com.obd.api.support.RecordedMail;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import com.obd.api.user.exception.EmailAlreadyVerifiedException;
import com.obd.api.user.exception.UserNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Email verification against a real database.
 *
 * The event publisher is mocked rather than the Mailer, and that is how these
 * tests get hold of the link: the raw token never leaves the service except
 * inside the published request, so capturing that argument is the only way to
 * see it - exactly as a real user only ever sees it in their inbox.
 */
@RepositoryTest
@Import({EmailVerificationService.class, RecordedMail.class})
@TestPropertySource(properties = {
        "app.mail.verification-ttl-hours=24",
        "app.mail.resend-throttle-seconds=60"
})
class EmailVerificationServiceTest {

    @Autowired
    private EmailVerificationService verificationService;
    @Autowired
    private EmailVerificationTokenRepository tokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RecordedMail.Recorder mail;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private User ada;

    @BeforeEach
    void anUnverifiedAccount() {
        mail.clear();
        ada = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace").userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build());
    }

    /** The token as the user would read it out of the email. */
    private String mailedToken() {
        assertThat(mail.latest()).isInstanceOf(MailRequest.Verification.class);
        return mail.latestToken();
    }

    private User reload() {
        return userRepository.findById(ada.getUserId()).orElseThrow();
    }

    /**
     * Moves a token back in time.
     *
     * created_at and expires_at are updatable = false on the entity - they
     * describe a decision already taken - so Hibernate silently drops any
     * change to them. Native SQL is the honest way to age a fixture, and the
     * clear() afterwards stops the service reading a stale copy from the
     * persistence context.
     */
    private void age(Duration by) {
        entityManager.createNativeQuery("""
                        update email_verification_tokens
                           set created_at = created_at - cast(:shift as interval),
                               expires_at = expires_at - cast(:shift as interval)
                         where user_id = :userId and consumed_at is null
                        """)
                .setParameter("shift", by.toSeconds() + " seconds")
                .setParameter("userId", ada.getUserId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    // --- issuing -------------------------------------------------------------

    @Test
    void registrationIssuesALinkAddressedToTheAccount() {
        verificationService.sendOnRegistration(ada);

        assertThat(mail.all()).singleElement()
                .isInstanceOf(MailRequest.Verification.class)
                .satisfies(request -> {
                    assertThat(request.to()).isEqualTo("ada@example.com");
                    assertThat(request.firstName()).isEqualTo("Ada");
                    assertThat(request.rawToken()).isNotBlank();
                });
    }

    @Test
    void onlyTheHashIsStoredNotTheToken() {
        verificationService.sendOnRegistration(ada);
        String raw = mailedToken();

        EmailVerificationToken stored = tokenRepository.findAll().getFirst();
        assertThat(stored.getTokenHash()).isNotEqualTo(raw).hasSize(64);
        assertThat(stored.getTokenUserId()).isEqualTo(ada.getUserId());
        assertThat(stored.getTokenExpiresAt())
                .isBetween(Instant.now().plus(23, ChronoUnit.HOURS),
                        Instant.now().plus(24, ChronoUnit.HOURS).plusSeconds(5));
    }

    @Test
    void resendingRetiresTheLinkInTheOlderEmail() {
        verificationService.sendOnRegistration(ada);
        String first = mailedToken();

        // Step over the throttle window rather than sleeping through it.
        age(Duration.ofMinutes(2));

        verificationService.resend(ada.getUserId());
        String second = mailedToken();

        assertThat(second).isNotEqualTo(first);
        // The older link is dead, so only the newest email works.
        assertThatThrownBy(() -> verificationService.verify(first))
                .isInstanceOf(TokenNoLongerValidException.class);
        verificationService.verify(second);
        assertThat(reload().getUserEmailVerifiedAt()).isNotNull();
    }

    @Test
    void resendingTooSoonIsRefused() {
        verificationService.sendOnRegistration(ada);

        assertThatThrownBy(() -> verificationService.resend(ada.getUserId()))
                .isInstanceOf(TokenRequestedTooSoonException.class);

        // Nothing was issued, so the original link still works.
        assertThat(tokenRepository.count()).isEqualTo(1);
    }

    @Test
    void anAlreadyVerifiedAccountCannotAskForMore() {
        verificationService.sendOnRegistration(ada);
        verificationService.verify(mailedToken());

        assertThatThrownBy(() -> verificationService.resend(ada.getUserId()))
                .isInstanceOf(EmailAlreadyVerifiedException.class);
    }

    @Test
    void resendingForAnAccountThatIsGoneIsNotFound() {
        assertThatThrownBy(() -> verificationService.resend(UUID.randomUUID()))
                .isInstanceOf(UserNotFoundException.class);
    }

    // --- verifying -----------------------------------------------------------

    @Test
    void theLinkMarksTheAddressVerified() {
        verificationService.sendOnRegistration(ada);
        assertThat(reload().getUserEmailVerifiedAt()).isNull();

        verificationService.verify(mailedToken());

        assertThat(reload().getUserEmailVerifiedAt()).isNotNull();
        // Verification says nothing about the password or the admin switch.
        assertThat(reload().getUserPasswordChangedAt()).isNull();
        assertThat(reload().isEnabled()).isTrue();
    }

    @Test
    void aLinkWorksOnlyOnce() {
        verificationService.sendOnRegistration(ada);
        String raw = mailedToken();
        verificationService.verify(raw);

        // A mail scanner prefetching the link, or the user tapping twice.
        assertThatThrownBy(() -> verificationService.verify(raw))
                .isInstanceOf(TokenNoLongerValidException.class);
    }

    @Test
    void anUnknownLinkIsNotFound() {
        assertThatThrownBy(() -> verificationService.verify("not-a-real-token"))
                .isInstanceOf(TokenNotFoundException.class);
        assertThat(mail.isEmpty()).isTrue();
    }

    @Test
    void anExpiredLinkIsRefusedAndSaysSo() {
        verificationService.sendOnRegistration(ada);
        String raw = mailedToken();

        // Older than its 24-hour life. Both timestamps shift together, so
        // ck_evt_expires_after_created still holds.
        age(Duration.ofHours(48));

        assertThatThrownBy(() -> verificationService.verify(raw))
                .isInstanceOf(TokenNoLongerValidException.class);
        assertThat(reload().getUserEmailVerifiedAt()).isNull();
    }

    @Test
    void oneAccountsLinkNeverVerifiesAnother() {
        User grace = userRepository.saveAndFlush(User.builder()
                .userName("Grace").userLastName("Hopper").userEmail("grace@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build());

        verificationService.sendOnRegistration(ada);
        verificationService.verify(mailedToken());

        assertThat(reload().getUserEmailVerifiedAt()).isNotNull();
        assertThat(userRepository.findById(grace.getUserId()).orElseThrow()
                .getUserEmailVerifiedAt()).isNull();
    }

    @Test
    void redeemingALinkSendsNothing() {
        verificationService.sendOnRegistration(ada);
        String raw = mailedToken();
        mail.clear();

        verificationService.verify(raw);

        // No "welcome" mail, no confirmation - one message per flow.
        assertThat(mail.isEmpty()).isTrue();
    }
}
