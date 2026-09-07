package com.obd.api.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Derived from the entity property name (userEmail), not the column name
    // (email). Emails are stored lowercased by UserMapper, so callers have to
    // normalise before looking up.
    Optional<User> findByUserEmail(String userEmail);
}
