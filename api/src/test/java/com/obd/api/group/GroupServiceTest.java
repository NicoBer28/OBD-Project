package com.obd.api.group;

import com.obd.api.group.dto.GroupDTO;
import com.obd.api.invitation.exception.NotAMemberException;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The group reads against a real database: who may see a group's members,
 * and what "my groups" answers. See {@link GroupRepositoryTest} for the
 * create side and the constraints.
 */
@RepositoryTest
@Import({GroupService.class, GroupAccess.class})
class GroupServiceTest {

    @Autowired
    private GroupService groupService;
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
        groupMemberRepository.saveAndFlush(GroupMember.of(groupId, userId, role));
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

    // --- members -------------------------------------------------------------

    @Test
    void anAdminSeesEveryMemberAsAPersonWithTheirRole() {
        List<GroupDTO.Member> members = groupService.members(adaId, familiaId);

        // Admins first, then by name - and each row carries what a members
        // screen needs, so no second request per user.
        assertThat(members).extracting(GroupDTO.Member::userId)
                .containsExactly(adaId, graceId);
        assertThat(members).extracting(GroupDTO.Member::role)
                .containsExactly(GroupRole.ADMIN, GroupRole.MEMBER);
        assertThat(members.get(1).email()).isEqualTo("grace@example.com");
        assertThat(members.get(1).name()).isEqualTo("Test");
    }

    @Test
    void aPlainMemberMaySeeTheListToo() {
        // Read-level: any role. Grace can see who else is in her family.
        assertThat(groupService.members(graceId, familiaId)).hasSize(2);
    }

    @Test
    void aNonMemberIsRefused() {
        assertThatThrownBy(() -> groupService.members(strangerId, familiaId))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void anUnknownGroupIsRefusedTheSameWayAsAForeignOne() {
        // Same exception (and same 404) whether the group does not exist or
        // the caller is simply not in it - the id is never confirmed.
        assertThatThrownBy(() -> groupService.members(adaId, UUID.randomUUID()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void membershipIsPerGroupNotGlobal() {
        UUID lopezId = newGroup("Los Lopez");
        enrol(lopezId, strangerId, GroupRole.ADMIN);

        // Being ADMIN somewhere else buys nothing here.
        assertThatThrownBy(() -> groupService.members(strangerId, familiaId))
                .isInstanceOf(NotAMemberException.class);
        // And Ada, admin of Familia, cannot look into Los Lopez.
        assertThatThrownBy(() -> groupService.members(adaId, lopezId))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void membersOfOneGroupDoNotLeakIntoAnother() {
        UUID lopezId = newGroup("Los Lopez");
        enrol(lopezId, adaId, GroupRole.MEMBER);
        enrol(lopezId, strangerId, GroupRole.ADMIN);

        assertThat(groupService.members(adaId, lopezId))
                .extracting(GroupDTO.Member::userId)
                .containsExactlyInAnyOrder(adaId, strangerId)
                .doesNotContain(graceId);
    }

    // --- getGroups -----------------------------------------------------------

    @Test
    void myGroupsCarriesMyRoleAndTheLiveMemberCount() {
        UUID lopezId = newGroup("Los Lopez");
        enrol(lopezId, adaId, GroupRole.MEMBER);

        List<GroupDTO.Read> groups = groupService.getGroups(adaId);

        assertThat(groups).extracting(GroupDTO.Read::name)
                .containsExactly("Familia", "Los Lopez"); // ordered by name
        assertThat(groups).filteredOn(g -> g.id().equals(familiaId))
                .singleElement()
                .satisfies(g -> {
                    assertThat(g.callerRole()).isEqualTo(GroupRole.ADMIN);
                    assertThat(g.memberCount()).isEqualTo(2);
                });
        assertThat(groups).filteredOn(g -> g.id().equals(lopezId))
                .singleElement()
                .satisfies(g -> {
                    assertThat(g.callerRole()).isEqualTo(GroupRole.MEMBER);
                    assertThat(g.memberCount()).isEqualTo(1);
                });
    }

    @Test
    void myGroupsIsEmptyForAUserInNone() {
        assertThat(groupService.getGroups(strangerId)).isEmpty();
    }
}
