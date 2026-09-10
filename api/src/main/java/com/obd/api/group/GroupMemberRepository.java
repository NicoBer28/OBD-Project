package com.obd.api.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    // Derived from the embedded key's properties: id.groupId / id.userId.
    long countByIdGroupId(UUID groupId);

    List<GroupMember> findByIdUserId(UUID userId);

    List<GroupMember> findByIdGroupId(UUID groupId);

    Optional<GroupMember> findByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    @Query("""
        select new com.obd.api.group.GroupSummary( g, m.role, (select count(x) from GroupMember x where x.id.groupId = g.groupId))
          from Group g, GroupMember m
         where m.id.groupId = g.groupId
           and m.id.userId = :userId
         order by g.groupName
        """)
    List<GroupSummary> findSummariesForUser(@Param("userId") UUID userId);
}
