package com.obd.api.invitecode;

import com.obd.api.group.Group;
import com.obd.api.group.GroupRepository;
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
 * The invite-code table and its two statements against a real Postgres: the
 * constraints from V11, the conditional claim, and revocation.
 */
@RepositoryTest
class InviteCodeRepositoryTest {

    @Autowired
    private InviteCodeRepository inviteCodeRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID familiaId;
    private UUID adaId;
    private Instant now;

    @BeforeEach
    void setUp() {
        adaId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace").userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
        familiaId = groupRepository.saveAndFlush(
                Group.builder().groupName("Familia").build()).getGroupId();
        now = Instant.now();
    }

    private InviteCode code(UUID groupId, String hash, Instant expiresAt, Integer maxUses) {
        return code(groupId, hash, now, expiresAt, maxUses);
    }

    private InviteCode code(UUID groupId, String hash, Instant createdAt, Instant expiresAt, Integer maxUses) {
        return inviteCodeRepository.saveAndFlush(InviteCode.builder()
                .inviteCodeGroupId(groupId)
                .inviteCodeHash(hash)
                .inviteCodeCreatedBy(adaId)
                .inviteCodeCreatedAt(createdAt)
                .inviteCodeExpiresAt(expiresAt)
                .inviteCodeMaxUses(maxUses)
                .build());
    }

    /** Minted two days ago with a one-day life: ck_gic_expires_after_created
     *  means an expired row can only be made by backdating creation too. */
    private InviteCode expired(String hash) {
        Instant createdAt = now.minus(2, ChronoUnit.DAYS);
        return code(familiaId, hash, createdAt, createdAt.plus(1, ChronoUnit.DAYS), null);
    }

    private InviteCode live(String hash) {
        return code(familiaId, hash, now.plus(1, ChronoUnit.DAYS), null);
    }

    // --- constraints ---------------------------------------------------------

    @Test
    void aGroupMayHaveOnlyOneLiveCode() {
        live("hash-one");

        assertThatThrownBy(() -> live("hash-two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokingFreesTheSlotForANewCode() {
        live("hash-one");

        assertThat(inviteCodeRepository.revokeLiveFor(familiaId, now)).isEqualTo(1);
        InviteCode replacement = live("hash-two");

        assertThat(replacement.getInviteCodeRevokedAt()).isNull();
        // The old row is kept: it is the record that a code existed.
        assertThat(inviteCodeRepository.count()).isEqualTo(2);
    }

    @Test
    void anExpiredCodeStillHoldsTheSlotUntilItIsRevoked() {
        // Why revokeLiveFor does not filter on expiry: the index predicate is
        // revoked_at is null, so an expired row still collides.
        expired("old");

        assertThatThrownBy(() -> live("new"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoGroupsMayEachHaveALiveCode() {
        UUID lopezId = groupRepository.saveAndFlush(
                Group.builder().groupName("Los Lopez").build()).getGroupId();

        live("hash-familia");
        code(lopezId, "hash-lopez", now.plus(1, ChronoUnit.DAYS), null);

        assertThat(inviteCodeRepository.count()).isEqualTo(2);
    }

    @Test
    void theSameHashCannotExistTwice() {
        UUID lopezId = groupRepository.saveAndFlush(
                Group.builder().groupName("Los Lopez").build()).getGroupId();
        live("same");

        assertThatThrownBy(() -> code(lopezId, "same", now.plus(1, ChronoUnit.DAYS), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheGroupTakesItsCodesWithIt() {
        live("hash-one");
        entityManager.clear();

        groupRepository.deleteById(familiaId);
        entityManager.flush();

        // on delete cascade in V11 - a code for a group that is gone is nothing.
        assertThat(inviteCodeRepository.count()).isZero();
    }

    // --- claim ---------------------------------------------------------------

    @Test
    void claimingSpendsOneUse() {
        live("hash-one");

        assertThat(inviteCodeRepository.claim("hash-one", now)).isEqualTo(1);

        assertThat(inviteCodeRepository.findByInviteCodeHash("hash-one"))
                .get()
                .extracting(InviteCode::getInviteCodeUses)
                .isEqualTo(1);
    }

    @Test
    void anUnlimitedCodeMayBeClaimedRepeatedly() {
        live("hash-one");

        for (int i = 0; i < 5; i++) {
            assertThat(inviteCodeRepository.claim("hash-one", now)).isEqualTo(1);
        }
        assertThat(inviteCodeRepository.findByInviteCodeHash("hash-one"))
                .get().extracting(InviteCode::getInviteCodeUses).isEqualTo(5);
    }

    @Test
    void aSingleUseCodeIsClaimableExactlyOnce() {
        code(familiaId, "one-shot", now.plus(1, ChronoUnit.DAYS), 1);

        assertThat(inviteCodeRepository.claim("one-shot", now)).isEqualTo(1);
        // The second scanner loses the race in the WHERE clause, not in Java.
        assertThat(inviteCodeRepository.claim("one-shot", now)).isZero();
        assertThat(inviteCodeRepository.findByInviteCodeHash("one-shot"))
                .get().extracting(InviteCode::getInviteCodeUses).isEqualTo(1);
    }

    @Test
    void anExpiredCodeCannotBeClaimed() {
        expired("stale");

        assertThat(inviteCodeRepository.claim("stale", now)).isZero();
    }

    @Test
    void aRevokedCodeCannotBeClaimed() {
        live("hash-one");
        inviteCodeRepository.revokeLiveFor(familiaId, now);

        assertThat(inviteCodeRepository.claim("hash-one", now)).isZero();
    }

    @Test
    void anUnknownHashClaimsNothing() {
        live("hash-one");

        assertThat(inviteCodeRepository.claim("not-a-real-hash", now)).isZero();
    }

    // --- lookups -------------------------------------------------------------

    @Test
    void theGroupsLiveCodeIsFoundByGroupIdAndTheRevokedOneIsNot() {
        live("hash-one");

        assertThat(inviteCodeRepository.findByInviteCodeGroupIdAndInviteCodeRevokedAtIsNull(familiaId))
                .get().extracting(InviteCode::getInviteCodeHash).isEqualTo("hash-one");

        inviteCodeRepository.revokeLiveFor(familiaId, now);

        assertThat(inviteCodeRepository.findByInviteCodeGroupIdAndInviteCodeRevokedAtIsNull(familiaId))
                .isEmpty();
    }

    @Test
    void revokingWhenThereIsNothingLiveAffectsNoRows() {
        assertThat(inviteCodeRepository.revokeLiveFor(familiaId, now)).isZero();
    }

    // --- derived state -------------------------------------------------------

    @Test
    void usabilityAndRemainingUsesComeFromTheColumns() {
        InviteCode unlimited = live("hash-one");
        assertThat(unlimited.isUsableAt(now)).isTrue();
        assertThat(unlimited.getRemainingUses()).isNull();

        // Two live codes would collide on the index, so retire the first.
        inviteCodeRepository.revokeLiveFor(familiaId, now);

        InviteCode twice = code(familiaId, "twice", now.plus(1, ChronoUnit.DAYS), 2);
        assertThat(twice.getRemainingUses()).isEqualTo(2);
        assertThat(twice.isExhausted()).isFalse();

        inviteCodeRepository.claim("twice", now);
        inviteCodeRepository.claim("twice", now);

        InviteCode spent = inviteCodeRepository.findByInviteCodeHash("twice").orElseThrow();
        assertThat(spent.getRemainingUses()).isZero();
        assertThat(spent.isExhausted()).isTrue();
        assertThat(spent.isUsableAt(now)).isFalse();
    }
}
