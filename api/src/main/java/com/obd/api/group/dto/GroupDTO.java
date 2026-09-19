package com.obd.api.group.dto;

import com.obd.api.group.Group;
import com.obd.api.group.GroupRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class GroupDTO {

    public record Create(
            @NotBlank @Size(max = 60) String name
    ) {}

    public record Read(
            UUID id,
            String name,
            Instant createdAt,
            // Counted from group_members, never stored - the ER diagram marks
            // cant_integrantes as derivable.
            long memberCount,
            // The calling user's role in this group, so a client can decide
            // what to show without a second request.
            GroupRole callerRole
    ) {
        public static Read from(Group group, long memberCount, GroupRole callerRole) {
            return new Read(group.getGroupId(), group.getGroupName(),
                    group.getGroupCreatedAt(), memberCount, callerRole);
        }
    }

    public record Member(
            UUID userId,
            String name,
            String email,
            GroupRole role
    ){}
}
