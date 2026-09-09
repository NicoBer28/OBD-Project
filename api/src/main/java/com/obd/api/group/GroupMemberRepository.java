package com.obd.api.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    // Derived from the embedded key's properties: id.groupId / id.userId.
    long countByIdGroupId(UUID groupId);

    List<GroupMember> findByIdUserId(UUID userId);

    List<GroupMember> findByIdGroupId(UUID groupId);

    Optional<GroupMember> findByIdGroupIdAndIdUserId(UUID groupId, UUID userId);
}
