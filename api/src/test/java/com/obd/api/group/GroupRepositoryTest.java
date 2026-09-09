package com.obd.api.group;

import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class GroupRepositoryTest {

    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private GroupMemberRepository groupMemberRepository;
    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID adaId;
    private UUID graceId;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User")
                .userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    @BeforeEach
    void createUsers() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
    }

    private Group newGroup(String name) {
        return groupRepository.saveAndFlush(Group.builder().groupName(name).build());
    }

    @Test
    void persistsAndReadsBackAGroup() {
        Group saved = newGroup("Familia Lazzari");

        Group found = groupRepository.findById(saved.getGroupId()).orElseThrow();
        assertThat(found.getGroupId()).isNotNull();
        assertThat(found.getGroupName()).isEqualTo("Familia Lazzari");
        assertThat(found.getGroupCreatedAt()).isNotNull();
    }

    @Test
    void allowsTwoGroupsWithTheSameName() {
        // Two unrelated families may well both be "Los Lopez" - the name is a
        // label, not an identifier.
        newGroup("Los Lopez");
        assertThat(newGroup("Los Lopez").getGroupId()).isNotNull();
    }

    @Test
    void storesMembershipWithItsRole() {
        Group group = newGroup("Familia Lazzari");
        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));

        GroupMember found = groupMemberRepository
                .findByIdGroupIdAndIdUserId(group.getGroupId(), adaId).orElseThrow();
        assertThat(found.getId().getGroupId()).isEqualTo(group.getGroupId());
        assertThat(found.getId().getUserId()).isEqualTo(adaId);
        assertThat(found.getRole()).isEqualTo(GroupRole.ADMIN);
    }

    @Test
    void refusesTheSameUserTwiceInOneGroup() {
        Group group = newGroup("Familia Lazzari");
        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));
        entityManager.clear();

        // The composite primary key is what enforces this: a genuine second
        // INSERT of the same (group, user) pair is rejected by the database.
        assertThatThrownBy(() -> {
            entityManager.persist(GroupMember.of(group.getGroupId(), adaId, GroupRole.MEMBER));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void savingAnExistingMembershipSilentlyChangesTheRole() {
        Group group = newGroup("Familia Lazzari");
        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));

        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), adaId, GroupRole.MEMBER));

        // Not a bug, but a trap worth pinning down: the id is assigned by us
        // rather than generated, so Spring Data cannot tell a new row from an
        // existing one and save() merges - an upsert, not an insert. Adding a
        // member who is already in the group therefore rewrites their role
        // instead of failing. Whoever writes the add-member endpoint has to
        // check membership first, or an ADMIN could be quietly demoted (or a
        // MEMBER promoted) by a repeated "add".
        assertThat(groupMemberRepository.countByIdGroupId(group.getGroupId())).isEqualTo(1);
        assertThat(groupMemberRepository
                .findByIdGroupIdAndIdUserId(group.getGroupId(), adaId).orElseThrow()
                .getRole()).isEqualTo(GroupRole.MEMBER);
    }

    @Test
    void countsMembersRatherThanStoringTheNumber() {
        Group group = newGroup("Familia Lazzari");
        assertThat(groupMemberRepository.countByIdGroupId(group.getGroupId())).isZero();

        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));
        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), graceId, GroupRole.MEMBER));

        assertThat(groupMemberRepository.countByIdGroupId(group.getGroupId())).isEqualTo(2);
    }

    @Test
    void findsEveryGroupAUserBelongsTo() {
        Group first = newGroup("Familia");
        Group second = newGroup("Amigos");
        groupMemberRepository.saveAndFlush(GroupMember.of(first.getGroupId(), adaId, GroupRole.ADMIN));
        groupMemberRepository.saveAndFlush(GroupMember.of(second.getGroupId(), adaId, GroupRole.MEMBER));
        groupMemberRepository.saveAndFlush(GroupMember.of(first.getGroupId(), graceId, GroupRole.MEMBER));

        assertThat(groupMemberRepository.findByIdUserId(adaId))
                .extracting(m -> m.getId().getGroupId())
                .containsExactlyInAnyOrder(first.getGroupId(), second.getGroupId());
        assertThat(groupMemberRepository.findByIdUserId(graceId)).hasSize(1);
    }

    @Test
    void deletingAGroupRemovesItsMemberships() {
        Group group = newGroup("Familia Lazzari");
        groupMemberRepository.saveAndFlush(GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));
        groupMemberRepository.saveAndFlush(GroupMember.of(group.getGroupId(), graceId, GroupRole.MEMBER));

        groupRepository.deleteById(group.getGroupId());
        entityManager.flush();
        entityManager.clear();

        // on delete cascade in V3 - orphaned membership rows would otherwise
        // point at a group that no longer exists.
        assertThat(groupMemberRepository.countByIdGroupId(group.getGroupId())).isZero();
    }

    @Test
    void deletingAUserRemovesTheirMemberships() {
        Group group = newGroup("Familia Lazzari");
        groupMemberRepository.saveAndFlush(GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));
        groupMemberRepository.saveAndFlush(GroupMember.of(group.getGroupId(), graceId, GroupRole.MEMBER));

        userRepository.deleteById(graceId);
        entityManager.flush();
        entityManager.clear();

        assertThat(groupMemberRepository.findByIdUserId(graceId)).isEmpty();
        // The group and everyone else survive.
        assertThat(groupMemberRepository.countByIdGroupId(group.getGroupId())).isEqualTo(1);
    }

    @Test
    void requiresAGroupThatExists() {
        assertThatThrownBy(() -> groupMemberRepository.saveAndFlush(
                GroupMember.of(UUID.randomUUID(), adaId, GroupRole.MEMBER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresAUserThatExists() {
        Group group = newGroup("Familia Lazzari");

        assertThatThrownBy(() -> groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), UUID.randomUUID(), GroupRole.MEMBER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesTheRoleAsAReadableString() {
        Group group = newGroup("Familia Lazzari");
        groupMemberRepository.saveAndFlush(
                GroupMember.of(group.getGroupId(), adaId, GroupRole.ADMIN));
        entityManager.flush();

        // EnumType.STRING, not ORDINAL: reordering the enum must not silently
        // change what every existing row means.
        Object role = entityManager.createNativeQuery(
                        "select role from group_members where group_id = :g and user_id = :u")
                .setParameter("g", group.getGroupId())
                .setParameter("u", adaId)
                .getSingleResult();
        assertThat(role).isEqualTo("ADMIN");
    }
}
