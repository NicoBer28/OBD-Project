package com.obd.api.user;


import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID userId;

    @Column(nullable = false, name = "name")
    private String userName;

    @Column(nullable = false, name = "lastname")
    private String userLastName;

    @Column(nullable = false, unique = true, name = "email")
    private String userEmail;

    @Column(nullable = false, name = "password")
    private String userPasswordHash;

    @Column(name = "phone_number")
    private String userPhone;

    @Column(name = "password_changed_at")
    private Instant userPasswordChangedAt;

    @Column(name = "email_verified_at")
    private Instant userEmailVerifiedAt;

    // @Builder ignores plain field initialisers - without @Builder.Default a
    // builder that skips role() would write null into a NOT NULL column.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled;
}
