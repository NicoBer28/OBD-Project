package com.obd.api.invitecode;

import com.obd.api.group.*;
import com.obd.api.group.dto.GroupDTO;
import com.obd.api.invitation.exception.NotAMemberException;
import com.obd.api.invitation.exception.NotAnAdminException;
import com.obd.api.invitecode.dto.InviteCodeDTO;
import com.obd.api.invitecode.exception.InviteCodeNoLongerValidException;
import com.obd.api.invitecode.exception.InviteCodeNotFoundException;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The QR flow against a real database: who may mint a code, what a scanner
 * sees, and what joining does. See {@link InviteCodeRepositoryTest} for the
 * claim statement and the constraints on its own.
 */
@RepositoryTest
@Import({InviteCodeService.class, GroupAccess.class})
class InviteCodeServiceTest {

    @Autowired
    private InviteCodeService inviteCodeService;
    @Autowired
    private InviteCodeRepository inviteCodeRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private GroupMemberRepository groupMemberRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID adaId;      // ADMIN of Familia
    private UUID graceId;    // MEMBER of Familia
    private UUID strangerId; // registered, in no group
    private UUID familiaId;

    private UUID newUser(String email) {
        // Verified: joining a group requires it, and these tests are about
        // the code, not the gate. The two tests at the end cover the gate.
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .userEmailVerifiedAt(Instant.now())
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private UUID newUnverifiedUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private UUID newGroup(String name) {
        return groupRepository.saveAndFlush(Group.builder().groupName(name).build()).getGroupId();
    }

    private void enrol(UUID groupId, UUID userId, GroupRole role) {
        groupMemberRepository.saveAndFlush(GroupMember.of(groupId, userId, role));
    }

    private static InviteCodeDTO.Create defaults() {
        return new InviteCodeDTO.Create(null, null);
    }

    private GroupRole roleOf(UUID userId, UUID groupId) {
        return groupMemberRepository.findByIdGroupIdAndIdUserId(groupId, userId)
                .map(GroupMember::getRole).orElse(null);
    }

    @BeforeEach
    void adaRunsAFamilyGraceIsIn() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        strangerId = newUser("stranger@example.com");
        familiaId = newGroup("Familia");
        enrol(familiaId, adaId, GroupRole.ADMIN);
        enrol(familiaId, graceId, GroupRole.MEMBER);
    }

    // --- mint ----------------------------------------------------------------

    @Test
    void anAdminMintsACodeAndSeesItExactlyOnce() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());

        assertThat(minted.code()).isNotBlank();
        assertThat(minted.joinUrl()).endsWith(minted.code());
        assertThat(minted.expiresAt()).isAfter(Instant.now());
        assertThat(minted.maxUses()).isNull();
        assertThat(minted.replacedPrevious()).isFalse();

        // Only the hash is stored, so nothing can hand the code out again.
        InviteCode stored = inviteCodeRepository.findAll().getFirst();
        assertThat(stored.getInviteCodeHash())
                .isEqualTo(InviteCodeService.sha256(minted.code()))
                .isNotEqualTo(minted.code());
        assertThat(stored.getInviteCodeCreatedBy()).isEqualTo(adaId);
    }

    @Test
    void mintingAgainRevokesTheCodeOnTheOldPoster() {
        InviteCodeDTO.Minted first = inviteCodeService.mint(adaId, familiaId, defaults());
        InviteCodeDTO.Minted second = inviteCodeService.mint(adaId, familiaId, defaults());

        assertThat(second.replacedPrevious()).isTrue();
        assertThat(second.code()).isNotEqualTo(first.code());

        assertThatThrownBy(() -> inviteCodeService.join(strangerId, first.code()))
                .isInstanceOf(InviteCodeNoLongerValidException.class);
        assertThat(inviteCodeService.preview(second.code()).groupName()).isEqualTo("Familia");
    }

    @Test
    void theTtlAndUseLimitAreHonoured() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId,
                new InviteCodeDTO.Create(2, 1));

        assertThat(minted.maxUses()).isEqualTo(1);
        assertThat(minted.expiresAt())
                .isBetween(Instant.now().plus(110, ChronoUnit.MINUTES),
                        Instant.now().plus(2, ChronoUnit.HOURS));
    }

    @Test
    void aPlainMemberMayNotMintACode() {
        // Driving the cars and deciding who else gets in are different rights.
        assertThatThrownBy(() -> inviteCodeService.mint(graceId, familiaId, defaults()))
                .isInstanceOf(NotAnAdminException.class);
    }

    @Test
    void aStrangerMintsNothingAndIsNotToldTheGroupExists() {
        assertThatThrownBy(() -> inviteCodeService.mint(strangerId, familiaId, defaults()))
                .isInstanceOf(NotAMemberException.class);
        assertThatThrownBy(() -> inviteCodeService.mint(adaId, UUID.randomUUID(), defaults()))
                .isInstanceOf(NotAMemberException.class);
    }

    // --- current / revoke ----------------------------------------------------

    @Test
    void theAdminSeesTheLiveCodesMetadataButNeverTheCode() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, new InviteCodeDTO.Create(null, 3));
        inviteCodeService.join(strangerId, minted.code());

        InviteCodeDTO.Read read = inviteCodeService.current(adaId, familiaId).orElseThrow();

        assertThat(read.uses()).isEqualTo(1);
        assertThat(read.maxUses()).isEqualTo(3);
        assertThat(read.remainingUses()).isEqualTo(2);
        assertThat(read.expiresAt()).isEqualTo(minted.expiresAt());
    }

    @Test
    void aGroupWithNoCodeHasNothingToShow() {
        assertThat(inviteCodeService.current(adaId, familiaId)).isEmpty();
    }

    @Test
    void anExhaustedCodeIsNoLongerTheCurrentOne() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, new InviteCodeDTO.Create(null, 1));
        inviteCodeService.join(strangerId, minted.code());

        assertThat(inviteCodeService.current(adaId, familiaId)).isEmpty();
    }

    @Test
    void revokingStopsFurtherJoinsButKeepsEveryoneWhoAlreadyJoined() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());
        inviteCodeService.join(strangerId, minted.code());

        inviteCodeService.revoke(adaId, familiaId);

        assertThat(inviteCodeService.current(adaId, familiaId)).isEmpty();
        assertThat(roleOf(strangerId, familiaId)).isEqualTo(GroupRole.MEMBER);
        assertThatThrownBy(() -> inviteCodeService.preview(minted.code()))
                .isInstanceOf(InviteCodeNoLongerValidException.class);
    }

    @Test
    void revokingNothingIsStillASuccess() {
        inviteCodeService.revoke(adaId, familiaId);
        inviteCodeService.revoke(adaId, familiaId);

        assertThat(inviteCodeRepository.count()).isZero();
    }

    @Test
    void onlyAnAdminMayReadOrRevoke() {
        inviteCodeService.mint(adaId, familiaId, defaults());

        assertThatThrownBy(() -> inviteCodeService.current(graceId, familiaId))
                .isInstanceOf(NotAnAdminException.class);
        assertThatThrownBy(() -> inviteCodeService.revoke(graceId, familiaId))
                .isInstanceOf(NotAnAdminException.class);
        assertThatThrownBy(() -> inviteCodeService.revoke(strangerId, familiaId))
                .isInstanceOf(NotAMemberException.class);
    }

    // --- preview -------------------------------------------------------------

    @Test
    void previewingShowsTheGroupWithoutSpendingAUse() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, new InviteCodeDTO.Create(null, 1));

        InviteCodeDTO.Preview preview = inviteCodeService.preview(minted.code());

        assertThat(preview.groupId()).isEqualTo(familiaId);
        assertThat(preview.groupName()).isEqualTo("Familia");
        assertThat(preview.memberCount()).isEqualTo(2);

        // Scanning and backing out must leave the single use intact.
        inviteCodeService.preview(minted.code());
        assertThat(inviteCodeService.current(adaId, familiaId).orElseThrow().uses()).isZero();
    }

    @Test
    void anUnknownCodeIsNotFound() {
        assertThatThrownBy(() -> inviteCodeService.preview("not-a-real-code"))
                .isInstanceOf(InviteCodeNotFoundException.class);
    }

    // --- join ----------------------------------------------------------------

    @Test
    void scanningJoinsTheGroupAsAMember() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());

        GroupDTO.Read joined = inviteCodeService.join(strangerId, minted.code());

        assertThat(joined.id()).isEqualTo(familiaId);
        assertThat(joined.name()).isEqualTo("Familia");
        assertThat(joined.callerRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(joined.memberCount()).isEqualTo(3);
        assertThat(roleOf(strangerId, familiaId)).isEqualTo(GroupRole.MEMBER);
        assertThat(inviteCodeService.current(adaId, familiaId).orElseThrow().uses()).isEqualTo(1);
    }

    @Test
    void anAdminScanningTheirOwnCodeIsNotDemoted() {
        // GroupMemberRepository.save is an upsert on an assigned key: writing
        // MEMBER over the admin's row would leave the group unadministrable.
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());

        GroupDTO.Read seen = inviteCodeService.join(adaId, minted.code());

        assertThat(seen.callerRole()).isEqualTo(GroupRole.ADMIN);
        assertThat(roleOf(adaId, familiaId)).isEqualTo(GroupRole.ADMIN);
        // ...and testing the QR did not spend one of its uses.
        assertThat(inviteCodeService.current(adaId, familiaId).orElseThrow().uses()).isZero();
    }

    @Test
    void scanningTwiceIsANoOpNotAnError() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());

        inviteCodeService.join(strangerId, minted.code());
        GroupDTO.Read again = inviteCodeService.join(strangerId, minted.code());

        assertThat(again.callerRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(again.memberCount()).isEqualTo(3);
        assertThat(inviteCodeService.current(adaId, familiaId).orElseThrow().uses()).isEqualTo(1);
    }

    @Test
    void aSingleUseCodeLetsInTheFirstScannerOnly() {
        UUID secondStranger = newUser("second@example.com");
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, new InviteCodeDTO.Create(null, 1));

        inviteCodeService.join(strangerId, minted.code());

        assertThatThrownBy(() -> inviteCodeService.join(secondStranger, minted.code()))
                .isInstanceOf(InviteCodeNoLongerValidException.class);
        assertThat(roleOf(secondStranger, familiaId)).isNull();
    }

    @Test
    void joiningWithAnUnknownOrRevokedCodeChangesNothing() {
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());
        inviteCodeService.revoke(adaId, familiaId);

        assertThatThrownBy(() -> inviteCodeService.join(strangerId, "not-a-real-code"))
                .isInstanceOf(InviteCodeNotFoundException.class);
        assertThatThrownBy(() -> inviteCodeService.join(strangerId, minted.code()))
                .isInstanceOf(InviteCodeNoLongerValidException.class);
        assertThat(roleOf(strangerId, familiaId)).isNull();
    }

    @Test
    void anUnverifiedAccountMayStillJoinByCode() {
        UUID unverified = newUnverifiedUser("unverified@example.com");
        InviteCodeDTO.Minted minted = inviteCodeService.mint(adaId, familiaId, defaults());

        // Unlike accepting an invitation, which is addressed to an email and
        // so could be claimed by an account that never proved it owns one, a
        // code is a bearer secret handed over in person. Nobody is
        // impersonated, so requiring verification would only stop a guest
        // joining at the dinner table.
        assertThat(inviteCodeService.join(unverified, minted.code()).callerRole())
                .isEqualTo(GroupRole.MEMBER);
        assertThat(roleOf(unverified, familiaId)).isEqualTo(GroupRole.MEMBER);
    }

    @Test
    void aCodeOnlyEverAdmitsToItsOwnGroup() {
        UUID lopezId = newGroup("Los Lopez");
        enrol(lopezId, strangerId, GroupRole.ADMIN);
        InviteCodeDTO.Minted familia = inviteCodeService.mint(adaId, familiaId, defaults());

        inviteCodeService.join(strangerId, familia.code());

        // Joined Familia as MEMBER; their ADMIN role in Los Lopez is untouched.
        assertThat(roleOf(strangerId, familiaId)).isEqualTo(GroupRole.MEMBER);
        assertThat(roleOf(strangerId, lopezId)).isEqualTo(GroupRole.ADMIN);
    }
}
