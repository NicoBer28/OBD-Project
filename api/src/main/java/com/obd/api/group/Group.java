package com.obd.api.group;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A family: the unit a car is shared with, so every member gets access to it.
 *
 * Membership lives in {@link GroupMember} rather than as a collection here.
 * Keeping the association out of the aggregate means creating a group, listing
 * one, or counting its members never drags the whole membership into memory.
 */
@Entity
// "groups" rather than "group" - GROUP is a reserved word in Postgres, the same
// reason the users table is not called "user".
@Table(name = "groups")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID groupId;

    @Column(nullable = false, name = "name")
    private String groupName;

    // Assigned once on insert. @Builder ignores plain field initialisers, so
    // without @Builder.Default this would be null in a NOT NULL column.
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant groupCreatedAt = Instant.now();
}
