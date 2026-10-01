package com.obd.api.auth.token;

import com.obd.api.auth.reset.PasswordResetToken;
import com.obd.api.auth.reset.PasswordResetTokenRepository;
import com.obd.api.auth.verification.EmailVerificationToken;
import com.obd.api.auth.verification.EmailVerificationTokenRepository;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two token tables from V13 against a real Postgres: their constraints,
 * the conditional consume, and the one-live-per-user index the throttle
 * leans on.
 *
 * Both tables are exercised in one class because they are deliberately
 * identical in shape - if they ever drift, these tests are where it shows.
 */
@RepositoryTest
class TokenTablesRepositoryTest {

    @Autowired
    private EmailVerificationTokenRepository verificationTokens;
    @Autowired
    private PasswordResetTokenRepository resetTokens;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID adaId;
    private Instant now;

    @BeforeEach
    void setUp() {
        adaId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace").userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
        now = Instant.now();
    }

    private EmailVerificationToken verification(String hash, Instant createdAt, Instant expiresAt) {
        return verificationTokens.saveAndFlush(EmailVerificationToken.builder()
                .tokenUserId(adaId).tokenHash(hash)
                .tokenCreatedAt(createdAt).tokenExpiresAt(expiresAt).build());
    }

    private EmailVerificationToken liveVerification(String hash) {
        return verification(hash, now, now.plus(24, ChronoUnit.HOURS));
    }

    private PasswordResetToken liveReset(String hash) {
        return resetTokens.saveAndFlush(PasswordResetToken.builder()
                .tokenUserId(adaId).tokenHash(hash)
                .tokenCreatedAt(now).tokenExpiresAt(now.plus(30, ChronoUnit.MINUTES)).build());
    }

    /** ck_evt_expires_after_created means an expired row needs a backdated creation. */
    private EmailVerificationToken expiredVerification(String hash) {
        Instant createdAt = now.minus(48, ChronoUnit.HOURS);
        return verification(hash, createdAt, createdAt.plus(24, ChronoUnit.HOURS));
    }

    // --- constraints ---------------------------------------------------------

    @Test
    void aUserMayHaveOnlyOneLiveVerificationToken() {
        liveVerification("hash-one");

        assertThatThrownBy(() -> liveVerification("hash-two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aUserMayHaveOnlyOneLiveResetToken() {
        liveReset("reset-one");

        assertThatThrownBy(() -> liveReset("reset-two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void consumingFreesTheSlotForANewToken() {
        liveVerification("hash-one");

        assertThat(verificationTokens.consumeLiveFor(adaId, now)).isEqualTo(1);
        liveVerification("hash-two");

        // The old row is kept, so a second click can be told "already used".
        assertThat(verificationTokens.count()).isEqualTo(2);
    }

    @Test
    void anExpiredTokenStillHoldsTheSlotUntilItIsConsumed() {
        // Why consumeLiveFor does not filter on expiry: the index predicate is
        // consumed_at is null, so an expired row still collides.
        expiredVerification("stale");

        assertThatThrownBy(() -> liveVerification("fresh"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTwoTablesAreIndependent() {
        // The same person may be verifying an address and resetting a password
        // at the same time; these are different secrets in different tables.
        liveVerification("same-hash");
        liveReset("same-hash");

        assertThat(verificationTokens.count()).isEqualTo(1);
        assertThat(resetTokens.count()).isEqualTo(1);
    }

    @Test
    void aHashCannotBeReusedWithinATable() {
        UUID graceId = userRepository.saveAndFlush(User.builder()
                .userName("Grace").userLastName("Hopper").userEmail("grace@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
        liveVerification("shared");

        assertThatThrownBy(() -> verificationTokens.saveAndFlush(EmailVerificationToken.builder()
                .tokenUserId(graceId).tokenHash("shared")
                .tokenCreatedAt(now).tokenExpiresAt(now.plus(1, ChronoUnit.HOURS)).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheAccountTakesItsTokensWithIt() {
        liveVerification("hash-one");
        liveReset("reset-one");
        entityManager.clear();

        userRepository.deleteById(adaId);
        entityManager.flush();

        // on delete cascade in V13: a link to an account that is gone is nothing.
        assertThat(verificationTokens.count()).isZero();
        assertThat(resetTokens.count()).isZero();
    }

    // --- consume -------------------------------------------------------------

    @Test
    void consumingStampsTheRowOnce() {
        liveVerification("hash-one");

        assertThat(verificationTokens.consume("hash-one", now)).isEqualTo(1);
        // The second click loses in the WHERE clause, not in Java.
        assertThat(verificationTokens.consume("hash-one", now)).isZero();

        assertThat(verificationTokens.findByTokenHash("hash-one"))
                .get().extracting(EmailVerificationToken::getTokenConsumedAt).isNotNull();
    }

    @Test
    void anExpiredTokenCannotBeConsumed() {
        expiredVerification("stale");

        assertThat(verificationTokens.consume("stale", now)).isZero();
        // ...and it is left unconsumed, so the service can tell the user it expired.
        assertThat(verificationTokens.findByTokenHash("stale"))
                .get().extracting(EmailVerificationToken::getTokenConsumedAt).isNull();
    }

    @Test
    void anUnknownHashConsumesNothing() {
        liveVerification("hash-one");

        assertThat(verificationTokens.consume("not-a-real-hash", now)).isZero();
        assertThat(resetTokens.consume("hash-one", now)).isZero();
    }

    @Test
    void aResetTokenIsConsumedTheSameWay() {
        liveReset("reset-one");

        assertThat(resetTokens.consume("reset-one", now)).isEqualTo(1);
        assertThat(resetTokens.consume("reset-one", now)).isZero();
    }

    // --- lookups -------------------------------------------------------------

    @Test
    void theLiveTokenIsFoundByUserAndTheConsumedOneIsNot() {
        liveVerification("hash-one");

        assertThat(verificationTokens.findByTokenUserIdAndTokenConsumedAtIsNull(adaId))
                .get().extracting(EmailVerificationToken::getTokenHash).isEqualTo("hash-one");

        verificationTokens.consume("hash-one", now);

        assertThat(verificationTokens.findByTokenUserIdAndTokenConsumedAtIsNull(adaId)).isEmpty();
    }

    @Test
    void consumingWhenThereIsNothingLiveAffectsNoRows() {
        assertThat(verificationTokens.consumeLiveFor(adaId, now)).isZero();
        assertThat(resetTokens.consumeLiveFor(adaId, now)).isZero();
    }

    @Test
    void usabilityIsDerivedFromTheColumns() {
        assertThat(liveVerification("hash-one").isUsableAt(now)).isTrue();

        // Two unconsumed rows would collide on the index, so retire the first.
        verificationTokens.consumeLiveFor(adaId, now);

        assertThat(expiredVerification("stale").isUsableAt(now)).isFalse();
        assertThat(verificationTokens.findByTokenHash("hash-one"))
                .get().extracting(t -> t.isUsableAt(now)).isEqualTo(false);
    }
}
