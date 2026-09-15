package com.obd.api.invitation;

import com.obd.api.group.*;
import com.obd.api.invitation.dto.InvitationDTO;
import com.obd.api.invitation.exception.*;
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
 * The invitation flow against a real database: who may invite, what the
 * invitee sees, and what accepting does. See {@link InvitationRepositoryTest}
 * for the accept statement on its own.
 */
@RepositoryTest
@Import(InvitationService.class)
class InvitationServiceTest {

    @Autowired
    private InvitationService invitationService;
    @Autowired
    private InvitationRepository invitationRepository;
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
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    private UUID newGroup(String name) {
        return groupRepository.saveAndFlush(Group.builder().groupName(name).build()).getGroupId();
    }

    private void enrol(UUID groupId, UUID userId, GroupRole role) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .id(new GroupMemberId(groupId, userId)).role(role).build());
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

    // --- invite --------------------------------------------------------------

    @Test
    void anAdminInvitesAnEmailAndGetsAPendingInvitationBack() {
        InvitationDTO.Read read = invitationService.invite(adaId, "new@example.com", familiaId);

        assertThat(read.invitationId()).isNotNull();
        assertThat(read.groupId()).isEqualTo(familiaId);
        assertThat(read.invitationEmail()).isEqualTo("new@example.com");
        assertThat(read.invitationBy()).isEqualTo(adaId);
        assertThat(read.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(read.invitationExpiresAt())
                .isEqualTo(read.invitationCreatedAt().plus(Invitation.TTL_DAYS, ChronoUnit.DAYS));
    }

    @Test
    void inviteNormalisesTheEmail() {
        // Stored lowercased so it matches the invitee's login later; the
        // CHECK constraint would reject anything else.
        InvitationDTO.Read read = invitationService.invite(adaId, "  New@Example.COM ", familiaId);

        assertThat(read.invitationEmail()).isEqualTo("new@example.com");
    }

    @Test
    void inviteLooksIdenticalForRegisteredAndUnregisteredEmails() {
        // The one property that keeps this endpoint from being an
        // "is this email registered?" oracle: a stranger with an account and
        // an address nobody has both get the same PENDING invitation.
        InvitationDTO.Read registered = invitationService.invite(adaId, "stranger@example.com", familiaId);
        InvitationDTO.Read unknown = invitationService.invite(adaId, "nobody@example.com", familiaId);

        assertThat(registered.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(unknown.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(registered.invitationBy()).isEqualTo(unknown.invitationBy());
    }

    @Test
    void inviteRefusesSomeoneWhoIsNotInTheGroup() {
        assertThatThrownBy(() -> invitationService.invite(strangerId, "new@example.com", familiaId))
                .isInstanceOf(NotAMemberException.class);
        assertThat(invitationRepository.count()).isZero();
    }

    @Test
    void inviteRefusesAMemberWhoIsNotAdmin() {
        assertThatThrownBy(() -> invitationService.invite(graceId, "new@example.com", familiaId))
                .isInstanceOf(NotAnAdminException.class);
        assertThat(invitationRepository.count()).isZero();
    }

    @Test
    void inviteRefusesSomeoneAlreadyInTheGroup() {
        // Grace is already a member. Inviting her would, on accept, hit the
        // GroupMemberRepository.save() upsert and rewrite her role.
        assertThatThrownBy(() -> invitationService.invite(adaId, "Grace@Example.com", familiaId))
                .isInstanceOf(AlreadyAMemberException.class);
        assertThat(invitationRepository.count()).isZero();
    }

    @Test
    void inviteRefusesASecondPendingInvitationForTheSameEmail() {
        invitationService.invite(adaId, "new@example.com", familiaId);

        // ux_invitations_pending, surfaced through the service's catch. No
        // assertion after it: the violation aborts the test transaction, so
        // any further statement fails regardless of what the service did.
        assertThatThrownBy(() -> invitationService.invite(adaId, "new@example.com", familiaId))
                .isInstanceOf(FailedInvitationException.class);
    }

    @Test
    void theSameEmailMayBeInvitedToTwoGroups() {
        UUID amigos = newGroup("Amigos");
        enrol(amigos, adaId, GroupRole.ADMIN);

        invitationService.invite(adaId, "new@example.com", familiaId);
        invitationService.invite(adaId, "new@example.com", amigos);

        assertThat(invitationRepository.count()).isEqualTo(2);
    }

    // --- pending -------------------------------------------------------------

    @Test
    void pendingListsOnlyLiveInvitationsForMyEmailNewestFirst() {
        UUID amigos = newGroup("Amigos");
        enrol(amigos, adaId, GroupRole.ADMIN);
        Instant now = Instant.now();

        // Two live ones, in two groups, created in a known order.
        invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(familiaId).invitationEmail("new@example.com")
                .invitationCreatedAt(now.minus(2, ChronoUnit.HOURS)).build());
        invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(amigos).invitationEmail("new@example.com")
                .invitationCreatedAt(now.minus(1, ChronoUnit.HOURS)).build());
        // One expired, one accepted, one for somebody else: all invisible.
        invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(newGroup("Old")).invitationEmail("new@example.com")
                .invitationCreatedAt(now.minus(20, ChronoUnit.DAYS))
                .invitationExpiresAt(now.minus(13, ChronoUnit.DAYS)).build());
        invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(newGroup("Done")).invitationEmail("new@example.com")
                .invitationCreatedAt(now.minus(2, ChronoUnit.DAYS))
                .invitationAcceptedAt(now.minus(1, ChronoUnit.DAYS)).build());
        invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(familiaId).invitationEmail("other@example.com").build());

        var pending = invitationService.pending("new@example.com");

        assertThat(pending).extracting(InvitationDTO.Pending::groupName)
                .containsExactly("Amigos", "Familia");
        assertThat(pending.getFirst().id()).isNotNull();
        assertThat(pending.getFirst().invitationExpiresAt()).isAfter(now);
    }

    @Test
    void pendingIsEmptyForSomeoneNobodyInvited() {
        assertThat(invitationService.pending("stranger@example.com")).isEmpty();
    }

    // --- accept --------------------------------------------------------------

    @Test
    void theInviteeAcceptsAndTheInvitationIsMarkedAccepted() {
        UUID id = invitationService.invite(adaId, "stranger@example.com", familiaId).invitationId();

        InvitationDTO.Read read = invitationService.accept("stranger@example.com", id);

        assertThat(read.invitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitationRepository.findById(id).orElseThrow().getInvitationAcceptedAt()).isNotNull();
        // Gone from the invitee's list now.
        assertThat(invitationService.pending("stranger@example.com")).isEmpty();
    }

    @Test
    void acceptRefusesAnInvitationAddressedToSomeoneElse() {
        UUID id = invitationService.invite(adaId, "new@example.com", familiaId).invitationId();

        // Not 403: a "not yours" that differed from "does not exist" would
        // confirm the id is real.
        assertThatThrownBy(() -> invitationService.accept("stranger@example.com", id))
                .isInstanceOf(InvitationNotFoundException.class);
        assertThat(invitationRepository.findById(id).orElseThrow().getInvitationAcceptedAt()).isNull();
    }

    @Test
    void acceptRefusesAnUnknownInvitation() {
        assertThatThrownBy(() -> invitationService.accept("stranger@example.com", UUID.randomUUID()))
                .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void acceptRefusesASecondTime() {
        UUID id = invitationService.invite(adaId, "stranger@example.com", familiaId).invitationId();
        invitationService.accept("stranger@example.com", id);

        assertThatThrownBy(() -> invitationService.accept("stranger@example.com", id))
                .isInstanceOf(InvitationAlreadyAccepted.class);
    }

    @Test
    void acceptRefusesAnExpiredInvitation() {
        Instant now = Instant.now();
        UUID id = invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(familiaId).invitationEmail("stranger@example.com")
                .invitationCreatedAt(now.minus(10, ChronoUnit.DAYS))
                .invitationExpiresAt(now.minus(3, ChronoUnit.DAYS)).build()).getInvitationId();

        assertThatThrownBy(() -> invitationService.accept("stranger@example.com", id))
                .isInstanceOf(InvitationExpiredException.class);
        assertThat(invitationRepository.findById(id).orElseThrow().getInvitationAcceptedAt()).isNull();
    }
}
