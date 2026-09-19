package com.obd.api.user;

import com.obd.api.support.RepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RepositoryTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    // UserMapper is a @Component in this package, so the slice would try to
    // build it (and its PasswordEncoder) even though no test here uses it.
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private static User.UserBuilder aUser() {
        return User.builder()
                .userName("Ada")
                .userLastName("Lovelace")
                .userEmail("ada@example.com")
                .userPasswordHash("$2a$12$notarealhash")
                .userPhone("+39 320 1234567")
                .enabled(true);
    }

    @Test
    void persistsAndReadsBackEveryMappedColumn() {
        User saved = userRepository.saveAndFlush(aUser().build());

        User found = userRepository.findById(saved.getUserId()).orElseThrow();
        assertThat(found.getUserName()).isEqualTo("Ada");
        assertThat(found.getUserLastName()).isEqualTo("Lovelace");
        assertThat(found.getUserEmail()).isEqualTo("ada@example.com");
        assertThat(found.getUserPasswordHash()).isEqualTo("$2a$12$notarealhash");
        assertThat(found.getUserPhone()).isEqualTo("+39 320 1234567");
        // @Builder.Default - a builder that skips role() must still write USER,
        // not null, into the NOT NULL column.
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.isEnabled()).isTrue();
    }

    @Test
    void findByUserEmailMatchesTheStoredAddress() {
        userRepository.saveAndFlush(aUser().build());

        assertThat(userRepository.findByUserEmail("ada@example.com"))
                .map(User::getUserEmail)
                .contains("ada@example.com");
    }

    @Test
    void findByUserEmailIsCaseSensitive() {
        userRepository.saveAndFlush(aUser().build());

        // Documents why UserMapper/AppUserDetailsService lowercase before they
        // reach this method: the query itself will not do it for them.
        assertThat(userRepository.findByUserEmail("Ada@Example.com")).isEmpty();
    }

    @Test
    void findByUserEmailReturnsEmptyForUnknownAddress() {
        assertThat(userRepository.findByUserEmail("nobody@example.com"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void rejectsASecondUserWithTheSameEmail() {
        userRepository.saveAndFlush(aUser().build());

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                aUser().userName("Grace").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void generatesAUuidPrimaryKey() {
        User saved = userRepository.saveAndFlush(aUser().build());

        assertThat(saved.getUserId()).isInstanceOf(UUID.class).isNotNull();
    }
}
