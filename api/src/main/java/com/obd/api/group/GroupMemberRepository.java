package com.obd.api.group;

import com.obd.api.group.dto.GroupDTO;
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
        select new com.obd.api.group.dto.GroupDTO$Read(g.groupId, g.groupName, g.groupCreatedAt ,(select count(x) from GroupMember x where x.id.groupId = g.groupId), m.role)
          from Group g, GroupMember m
         where m.id.groupId = g.groupId
           and m.id.userId = :userId
         order by g.groupName
        """)
    List<GroupDTO.Read> findSummariesForUser(@Param("userId") UUID userId);

    @Query("""
        select m
          from GroupMember m, User u
         where u.userId = m.id.userId
           and m.id.groupId = :groupId
           and u.userEmail = :email
        """)
    Optional<GroupMember> findByGroupIdAndUserEmail(@Param("groupId") UUID groupId,  @Param("email") String email);
}
