package com.obd.api.invitation;

import com.obd.api.group.Group;
import com.obd.api.group.GroupRepository;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

@RepositoryTest
class InvitationRepositoryTest {

    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private final Instant noon = Instant.parse("2026-09-10T12:00:00Z");
    private UUID adaId;
    private UUID groupId;

    @BeforeEach
    void adaHasAGroup() {
        adaId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace").userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
        groupId = groupRepository.saveAndFlush(Group.builder().groupName("Familia").build()).getGroupId();
    }

    private Invitation.InvitationBuilder anInviteFor(String email) {
        return Invitation.builder()
                .invitationGroupId(groupId)
                .invitationEmail(email)
                .invitationInvitedBy(adaId)
                .invitationCreatedAt(noon)
                .invitationExpiresAt(noon.plus(7, ChronoUnit.DAYS));
    }

    private Invitation reload(UUID id) {
        return invitationRepository.findById(id).orElseThrow();
    }

    // --- accept: the one way accepted_at gets written -----------------------

    @Test
    void acceptsAPendingInvitationAddressedToTheCaller() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        Instant at = noon.plus(1, ChronoUnit.DAYS);

        assertThat(invitationRepository.accept(id, "grace@example.com", at)).isEqualTo(1);

        Invitation found = reload(id);
        assertThat(found.getInvitationAcceptedAt()).isEqualTo(at);
        assertThat(found.getStatusAt(at)).isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    void refusesToAcceptForADifferentEmail() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();

        // Knowing the id is not enough: the invitation is addressed to Grace,
        // and only Grace's session can accept it.
        assertThat(invitationRepository.accept(id, "mallory@example.com", noon.plusSeconds(60))).isZero();
        assertThat(reload(id).getInvitationAcceptedAt()).isNull();
    }

    @Test
    void refusesToAcceptTwice() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        Instant first = noon.plus(1, ChronoUnit.DAYS);
        invitationRepository.accept(id, "grace@example.com", first);

        // A double tap on "accept": the second write finds accepted_at set and
        // changes nothing - the original acceptance time is kept.
        assertThat(invitationRepository.accept(id, "grace@example.com", first.plusSeconds(1))).isZero();
        assertThat(reload(id).getInvitationAcceptedAt()).isEqualTo(first);
    }

    @Test
    void refusesToAcceptAfterExpiry() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        Instant tooLate = noon.plus(8, ChronoUnit.DAYS);

        assertThat(invitationRepository.accept(id, "grace@example.com", tooLate)).isZero();
        Invitation found = reload(id);
        assertThat(found.getInvitationAcceptedAt()).isNull();
        assertThat(found.getStatusAt(tooLate)).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void refusesToAcceptAnInvitationThatDoesNotExist() {
        assertThat(invitationRepository.accept(UUID.randomUUID(), "grace@example.com", noon)).isZero();
    }

    // --- the table's own rules ---------------------------------------------

    @Test
    void persistsAndReadsBackEveryMappedColumn() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        entityManager.clear();

        Invitation found = reload(id);
        assertThat(found.getInvitationGroupId()).isEqualTo(groupId);
        assertThat(found.getInvitationEmail()).isEqualTo("grace@example.com");
        assertThat(found.getInvitationInvitedBy()).isEqualTo(adaId);
        assertThat(found.getInvitationCreatedAt()).isEqualTo(noon);
        assertThat(found.getInvitationExpiresAt()).isEqualTo(noon.plus(7, ChronoUnit.DAYS));
        assertThat(found.getInvitationAcceptedAt()).isNull();
        assertThat(found.getStatusAt(noon)).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void defaultsTheExpiryToAWeekAfterCreation() {
        // No expiry given: @PrePersist derives it from created_at, so the two
        // are exactly TTL_DAYS apart - not "about a week", which is what two
        // separate Instant.now() calls would give.
        UUID id = invitationRepository.saveAndFlush(Invitation.builder()
                .invitationGroupId(groupId).invitationEmail("grace@example.com").build()).getInvitationId();
        entityManager.clear();

        Invitation found = reload(id);
        assertThat(found.getInvitationExpiresAt())
                .isEqualTo(found.getInvitationCreatedAt().plus(Invitation.TTL_DAYS, ChronoUnit.DAYS));
    }

    @Test
    void refusesAnEmailThatIsNotLowercased() {
        // ck_invitations_email_lowercase: a caller that forgets to normalise
        // fails here, instead of creating an invitation the invitee's
        // lowercased login will never match.
        assertThatThrownBy(() -> invitationRepository.saveAndFlush(
                anInviteFor("Grace@Example.com").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesASecondPendingInvitationForTheSameEmail() {
        invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build());

        // ux_invitations_pending - the "already invited" 409.
        assertThatThrownBy(() -> invitationRepository.saveAndFlush(
                anInviteFor("grace@example.com").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsReinvitingSomeoneWhoAlreadyAccepted() {
        UUID first = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        invitationRepository.accept(first, "grace@example.com", noon.plusSeconds(60));

        // The index is partial on accepted_at is null: accepted rows are
        // history and stay, and the same person can be invited again after
        // leaving. Both rows now exist.
        assertThat(invitationRepository.saveAndFlush(
                anInviteFor("grace@example.com").build()).getInvitationId()).isNotNull();
        assertThat(invitationRepository.count()).isEqualTo(2);
    }

    @Test
    void refusesAnExpiryBeforeCreation() {
        assertThatThrownBy(() -> invitationRepository.saveAndFlush(anInviteFor("grace@example.com")
                .invitationExpiresAt(noon.minusSeconds(1)).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheGroupDeletesItsInvitations() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        entityManager.clear();

        groupRepository.deleteById(groupId);
        entityManager.flush();
        entityManager.clear();

        assertThat(invitationRepository.findById(id)).isEmpty();
    }

    @Test
    void keepsTheInvitationWhenTheInviterIsDeleted() {
        UUID id = invitationRepository.saveAndFlush(anInviteFor("grace@example.com").build()).getInvitationId();
        entityManager.clear();

        userRepository.deleteById(adaId);
        entityManager.flush();
        entityManager.clear();

        // on delete set null: Grace can still accept after Ada leaves.
        Invitation found = reload(id);
        assertThat(found.getInvitationInvitedBy()).isNull();
        assertThat(invitationRepository.accept(id, "grace@example.com", noon.plusSeconds(60))).isEqualTo(1);
    }
}
