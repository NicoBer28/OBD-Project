package com.obd.api.user;


import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "user")
@AllArgsConstructor
@NoArgsConstructor
@Getter @Setter
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled;
}
