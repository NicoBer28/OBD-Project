package com.obd.api.user;


import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
// "user" is a reserved word in Postgres (it resolves to the session user), so
// the table is "users".
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class User {

    // IDENTITY needs a numeric column; UUID lets Hibernate generate the value
    // itself and write it to a real Postgres `uuid` column.
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

    // @Builder ignores plain field initialisers - without @Builder.Default a
    // builder that skips role() would write null into a NOT NULL column.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled;
}
