package com.obd.api.user;

import com.obd.api.auth.AuthService;
import com.obd.api.auth.dto.AuthResponseDTO;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.dto.UserDTO;
import com.obd.api.user.exception.IncorrectPasswordException;
import com.obd.api.user.exception.PasswordUnchangedException;
import com.obd.api.user.exception.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Profile editing and password changes against a real database.
 *
 * The password encoder is the real BCrypt one - the point of most of these
 * tests is what the stored hash does - while the two auth collaborators are
 * mocked: issuing tokens and revoking families are their own concern, covered
 * by the auth tests. What matters here is that they are called, and in the
 * right order.
 */
@RepositoryTest
@Import({UserService.class, UserServiceTest.RealEncoder.class})
class UserServiceTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RealEncoder {
        // Cheap rounds: these tests hash a lot, and nothing here is a secret.
        @org.springframework.context.annotation.Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    private static final String PASSWORD = "supersecret123";

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RefreshTokenService refreshTokenService;
    @MockitoBean
    private AuthService authService;

    private UUID adaId;

    @BeforeEach
    void ada() {
        adaId = userRepository.saveAndFlush(User.builder()
                .userName("Ada").userLastName("Lovelace").userEmail("ada@example.com")
                .userPhone("+39 320 1234567")
                .userPasswordHash(passwordEncoder.encode(PASSWORD))
                .role(Role.USER).enabled(true).build()).getUserId();

        given(authService.reissue(any(UUID.class))).willReturn(new TokenPair(
                new AuthResponseDTO("new.access.token", "Bearer", 900, adaId, "ada@example.com"),
                "new-refresh-token"));
    }

    private User reload() {
        return userRepository.findById(adaId).orElseThrow();
    }

    // --- updateProfile -------------------------------------------------------

    @Test
    void updatingReplacesTheEditableFields() {
        UserDTO.Read updated = userService.updateProfile(adaId,
                new UserDTO.Update("Augusta", "Byron", "+39 06 999999"));

        assertThat(updated.userName()).isEqualTo("Augusta");
        assertThat(updated.userLastName()).isEqualTo("Byron");
        assertThat(updated.userPhone()).isEqualTo("+39 06 999999");
        assertThat(updated.userEmail()).isEqualTo("ada@example.com");
        assertThat(updated.id()).isEqualTo(adaId);
    }

    @Test
    void updatingTrimsWhitespace() {
        UserDTO.Read updated = userService.updateProfile(adaId,
                new UserDTO.Update("  Augusta  ", " Byron ", "  +39 06 999999  "));

        assertThat(updated.userName()).isEqualTo("Augusta");
        assertThat(updated.userLastName()).isEqualTo("Byron");
        assertThat(updated.userPhone()).isEqualTo("+39 06 999999");
    }

    @Test
    void anAbsentPhoneClearsIt() {
        // PUT replaces the profile rather than merging into it.
        assertThat(userService.updateProfile(adaId,
                new UserDTO.Update("Ada", "Lovelace", null)).userPhone()).isNull();
        assertThat(reload().getUserPhone()).isNull();
    }

    @Test
    void updatingTouchesNeitherTheEmailNorThePassword() {
        String hashBefore = reload().getUserPasswordHash();

        userService.updateProfile(adaId, new UserDTO.Update("Augusta", "Byron", null));

        User after = reload();
        assertThat(after.getUserEmail()).isEqualTo("ada@example.com");
        assertThat(after.getUserPasswordHash()).isEqualTo(hashBefore);
        assertThat(after.getUserPasswordChangedAt()).isNull();
        verifyNoInteractions(refreshTokenService, authService);
    }

    @Test
    void updatingAnAccountThatIsGoneIsNotFound() {
        assertThatThrownBy(() -> userService.updateProfile(UUID.randomUUID(),
                new UserDTO.Update("Augusta", "Byron", null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    // --- changePassword ------------------------------------------------------

    @Test
    void theNewPasswordReplacesTheOldHash() {
        String hashBefore = reload().getUserPasswordHash();

        userService.changePassword(adaId, new UserDTO.ChangePassword(PASSWORD, "evenbetter456"));

        User after = reload();
        assertThat(after.getUserPasswordHash()).isNotEqualTo(hashBefore);
        assertThat(passwordEncoder.matches("evenbetter456", after.getUserPasswordHash())).isTrue();
        // The old one stops working, which is the whole point.
        assertThat(passwordEncoder.matches(PASSWORD, after.getUserPasswordHash())).isFalse();
    }

    @Test
    void changingSignsEveryOtherDeviceOutAndKeepsThisOne() {
        TokenPair pair = userService.changePassword(adaId,
                new UserDTO.ChangePassword(PASSWORD, "evenbetter456"));

        // Revoke first, then issue: the other way round would kill the fresh
        // family along with the rest.
        InOrder inOrder = inOrder(refreshTokenService, authService);
        inOrder.verify(refreshTokenService).revokeAllForUser(adaId);
        inOrder.verify(authService).reissue(adaId);

        assertThat(pair.auth().accessToken()).isEqualTo("new.access.token");
        assertThat(pair.refreshToken()).isEqualTo("new-refresh-token");
    }

    @Test
    void theChangeIsStampedAtSecondPrecisionSoTheNewTokenSurvives() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        userService.changePassword(adaId, new UserDTO.ChangePassword(PASSWORD, "evenbetter456"));

        Instant stamped = reload().getUserPasswordChangedAt();
        assertThat(stamped).isNotNull().isAfterOrEqualTo(before);
        // A JWT's iat is epoch seconds; a finer stamp would make the token
        // minted by this very call look older than the change - see
        // JwtAuthFilter.mintedBeforeAPasswordChange.
        assertThat(stamped).isEqualTo(stamped.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void theWrongCurrentPasswordChangesNothing() {
        String hashBefore = reload().getUserPasswordHash();

        assertThatThrownBy(() -> userService.changePassword(adaId,
                new UserDTO.ChangePassword("not-my-password", "evenbetter456")))
                .isInstanceOf(IncorrectPasswordException.class);

        User after = reload();
        assertThat(after.getUserPasswordHash()).isEqualTo(hashBefore);
        assertThat(after.getUserPasswordChangedAt()).isNull();
        // No sessions were harmed: a wrong guess must not log anyone out.
        verifyNoInteractions(refreshTokenService, authService);
    }

    @Test
    void reusingTheSamePasswordIsRefused() {
        assertThatThrownBy(() -> userService.changePassword(adaId,
                new UserDTO.ChangePassword(PASSWORD, PASSWORD)))
                .isInstanceOf(PasswordUnchangedException.class);

        // Accepting it as a no-op would sign every other device out for nothing.
        assertThat(reload().getUserPasswordChangedAt()).isNull();
        verifyNoInteractions(refreshTokenService, authService);
    }

    @Test
    void changingForAnAccountThatIsGoneIsNotFound() {
        assertThatThrownBy(() -> userService.changePassword(UUID.randomUUID(),
                new UserDTO.ChangePassword(PASSWORD, "evenbetter456")))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void changingTwiceMovesTheStampForward() {
        userService.changePassword(adaId, new UserDTO.ChangePassword(PASSWORD, "evenbetter456"));
        Instant first = reload().getUserPasswordChangedAt();

        userService.changePassword(adaId, new UserDTO.ChangePassword("evenbetter456", "thirdtime789"));

        assertThat(reload().getUserPasswordChangedAt()).isAfterOrEqualTo(first);
        assertThat(passwordEncoder.matches("thirdtime789", reload().getUserPasswordHash())).isTrue();
    }

    // --- me ------------------------------------------------------------------

    @Test
    void meReturnsTheProfileWithoutTheHash() {
        UserDTO.Read me = userService.me(adaId);

        assertThat(me.id()).isEqualTo(adaId);
        assertThat(me.userName()).isEqualTo("Ada");
        assertThat(me.userEmail()).isEqualTo("ada@example.com");
    }
}
