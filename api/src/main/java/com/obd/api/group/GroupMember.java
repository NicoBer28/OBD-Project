package com.obd.api.group;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One user's membership of one group, with the role they hold there.
 *
 * Both sides are held as raw ids inside the composite key rather than as
 * @ManyToOne associations: every question asked of this table ("who is in this
 * group", "which groups is this user in", "what is this user's role here") is
 * answered by the key alone, so mappings would only add joins.
 */
@Entity
@Table(name = "group_members")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class GroupMember {

    @EmbeddedId
    private GroupMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "role")
    private GroupRole role;

    static GroupMember of(UUID groupId, UUID userId, GroupRole role) {
        return GroupMember.builder()
                .id(new GroupMemberId(groupId, userId))
                .role(role)
                .build();
    }
}
