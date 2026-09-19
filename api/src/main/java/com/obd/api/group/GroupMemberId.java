package com.obd.api.group;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key for {@link GroupMember}. Serializable and with value equality,
 * both of which JPA requires of an @EmbeddedId.
 */
@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@EqualsAndHashCode
public class GroupMemberId implements Serializable {

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
}
