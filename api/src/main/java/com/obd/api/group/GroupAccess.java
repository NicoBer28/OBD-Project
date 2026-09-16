package com.obd.api.group;

import com.obd.api.invitation.exception.NotAMemberException;
import com.obd.api.invitation.exception.NotAnAdminException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides who may do what with a group - the counterpart
 * of {@link com.obd.api.car.CarAccess}.
 *
 * Both answers come from a single primary-key lookup on group_members. A
 * non-member gets an empty Optional (or a 404) so the group's existence is
 * not confirmed; a member who is not admin gets a 403, since they already
 * know it exists.
 */
@Component
@RequiredArgsConstructor
public class GroupAccess {

    private final GroupMemberRepository groupMemberRepository;

    /** Read-level: any member, any role. */
    public Optional<GroupMember> memberOf(UUID userId, UUID groupId) {
        return groupMemberRepository.findByIdGroupIdAndIdUserId(groupId, userId);
    }

    /** Like {@link #memberOf}, but a non-member is an error rather than empty. */
    public GroupMember requireMember(UUID userId, UUID groupId) {
        return memberOf(userId, groupId)
                .orElseThrow(() -> new NotAMemberException(userId, groupId));
    }

    /** Admin-level: invite, remove, change roles, rename, delete. */
    public GroupMember requireAdmin(UUID userId, UUID groupId) {
        GroupMember member = requireMember(userId, groupId);
        if (member.getRole() != GroupRole.ADMIN) {
            throw new NotAnAdminException(groupId);
        }
        return member;
    }
}
