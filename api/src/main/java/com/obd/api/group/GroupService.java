package com.obd.api.group;

import com.obd.api.group.dto.GroupDTO;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupAccess groupAccess;

    /**
     * Creates a group and enrols the caller as its first member, with the ADMIN
     * role.
     *
     * The two writes are one transaction on purpose: a group with no members
     * would be unreachable - nobody could list it, join it or delete it - so it
     * must never be possible to observe one, not even briefly after a crash
     * between the inserts.
     */
    @Transactional
    public GroupDTO.Read create(UUID creatorId, GroupDTO.Create request) {
        Group group = groupRepository.save(Group.builder()
                .groupName(request.name().trim())
                .build());

        groupMemberRepository.save(
                GroupMember.of(group.getGroupId(), creatorId, GroupRole.ADMIN));

        // Counted rather than hard-coded to 1: memberCount has exactly one
        // source of truth, so it cannot drift as membership endpoints arrive.
        long memberCount = groupMemberRepository.countByIdGroupId(group.getGroupId());

        return GroupDTO.Read.from(group, memberCount, GroupRole.ADMIN);
    }

    @Transactional
    public List<GroupDTO.Read> getGroups(UUID userId){
        return groupMemberRepository.findSummariesForUser(userId);
    }

    public List<GroupMember> members(UUID userId, UUID groupId){
        groupAccess.requireMember(userId, groupId);

        return groupMemberRepository.findByIdGroupId(groupId);
    }

}
