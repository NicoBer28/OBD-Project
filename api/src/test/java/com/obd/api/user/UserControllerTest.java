package com.obd.api.user;

import com.obd.api.auth.JwtAuthEntryPoint;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.devicetoken.DeviceAuthFilter;
import com.obd.api.auth.SecurityConfig;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.auth.dto.AuthResponseDTO;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.refresh.RefreshCookie;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.auth.token.exception.TokenRequestedTooSoonException;
import com.obd.api.auth.verification.EmailVerificationService;
import com.obd.api.support.SliceSecurityConfig;
import com.obd.api.user.dto.UserDTO;
import com.obd.api.user.exception.EmailAlreadyVerifiedException;
import com.obd.api.user.exception.IncorrectPasswordException;
import com.obd.api.user.exception.PasswordUnchangedException;
import com.obd.api.user.exception.UserNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for the profile endpoints. See {@link UserServiceTest} for
 * the rules underneath.
 */
@WebMvcTest(controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class, DeviceAuthFilter.class}))
@Import(SliceSecurityConfig.class)
class UserControllerTest {

    private static final UUID CALLER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private RefreshCookie refreshCookie;
    @MockitoBean
    private RefreshTokenService refreshTokenService;
    @MockitoBean
    private EmailVerificationService emailVerificationService;

    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(CALLER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static UserDTO.Read profile() {
        return new UserDTO.Read(CALLER_ID, "Augusta", "Byron", "ada@example.com", "+39 06 999999", true);
    }

    private static final String VALID_PROFILE = """
            {"userName": "Augusta", "userLastName": "Byron", "userPhone": "+39 06 999999"}
            """;

    // --- GET /users/me -------------------------------------------------------

    @Test
    void meReturnsTheCallersProfile() throws Exception {
        given(userService.me(CALLER_ID)).willReturn(profile());

        mockMvc.perform(get("/api/v1/users/me").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CALLER_ID.toString()))
                .andExpect(jsonPath("$.userEmail").value("ada@example.com"));
    }

    @Test
    void meMapsAMissingAccountToNotFound() throws Exception {
        willThrow(new UserNotFoundException(CALLER_ID)).given(userService).me(CALLER_ID);

        mockMvc.perform(get("/api/v1/users/me").with(caller()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User not found"));
    }

    // --- PUT /users/me -------------------------------------------------------

    @Test
    void updateReturnsTheUpdatedProfile() throws Exception {
        given(userService.updateProfile(eq(CALLER_ID), any(UserDTO.Update.class))).willReturn(profile());

        mockMvc.perform(put("/api/v1/users/me").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("Augusta"))
                .andExpect(jsonPath("$.userLastName").value("Byron"))
                .andExpect(jsonPath("$.userPhone").value("+39 06 999999"));
    }

    @Test
    void updateTakesTheAccountFromThePrincipalNotTheBody() throws Exception {
        given(userService.updateProfile(eq(CALLER_ID), any(UserDTO.Update.class))).willReturn(profile());

        // The stub only matches CALLER_ID: naming someone else cannot edit
        // their profile.
        mockMvc.perform(put("/api/v1/users/me").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "99999999-9999-9999-9999-999999999999",
                                  "userName": "Augusta", "userLastName": "Byron"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void updateAcceptsAnAbsentPhone() throws Exception {
        given(userService.updateProfile(eq(CALLER_ID), any(UserDTO.Update.class))).willReturn(profile());

        mockMvc.perform(put("/api/v1/users/me").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName": "Augusta", "userLastName": "Byron"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void updateRejectsBlankNames() throws Exception {
        mockMvc.perform(put("/api/v1/users/me").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName": "   ", "userLastName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.userName").exists())
                .andExpect(jsonPath("$.errors.userLastName").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void updateRejectsAMalformedPhone() throws Exception {
        mockMvc.perform(put("/api/v1/users/me").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName": "Augusta", "userLastName": "Byron", "userPhone": "not a phone"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userPhone").exists());

        verifyNoInteractions(userService);
    }

    // --- POST /users/me/verify-email -----------------------------------------

    @Test
    void resendVerificationAnswers202ForTheCallersOwnAddress() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/verify-email").with(caller()))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        // No address in the request: the principal's is the only one it can
        // target, so it cannot be aimed at a stranger's inbox.
        verify(emailVerificationService).resend(CALLER_ID);
    }

    @Test
    void resendVerificationMapsTheThrottleTo429() throws Exception {
        willThrow(new TokenRequestedTooSoonException(60))
                .given(emailVerificationService).resend(CALLER_ID);

        mockMvc.perform(post("/api/v1/users/me/verify-email").with(caller()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail")
                        .value("A link was sent recently - wait a moment before asking for another"));
    }

    @Test
    void resendVerificationMapsAnAlreadyVerifiedAccountTo409() throws Exception {
        willThrow(new EmailAlreadyVerifiedException())
                .given(emailVerificationService).resend(CALLER_ID);

        mockMvc.perform(post("/api/v1/users/me/verify-email").with(caller()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That email address is already verified"));
    }

    @Test
    void meSaysWhetherTheAddressIsVerified() throws Exception {
        given(userService.me(CALLER_ID)).willReturn(profile());

        mockMvc.perform(get("/api/v1/users/me").with(caller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    // --- POST /users/me/password ---------------------------------------------

    private void stubChange() {
        given(userService.changePassword(eq(CALLER_ID), any(UserDTO.ChangePassword.class)))
                .willReturn(new TokenPair(
                        new AuthResponseDTO("new.access.token", "Bearer", 900, CALLER_ID, "ada@example.com"),
                        "new-refresh-token"));
        given(refreshTokenService.ttl()).willReturn(Duration.ofDays(14));
    }

    private static final String VALID_CHANGE = """
            {"currentPassword": "supersecret123", "newPassword": "evenbetter456"}
            """;

    @Test
    void changePasswordAnswersWithFreshTokensAndRotatesTheCookie() throws Exception {
        stubChange();

        mockMvc.perform(post("/api/v1/users/me/password").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHANGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                // The raw refresh token goes in the cookie, never the body.
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        verify(refreshCookie).set(any(HttpServletResponse.class), eq("new-refresh-token"), eq(Duration.ofDays(14)));
    }

    @Test
    void changePasswordMapsAWrongCurrentPasswordToUnauthorized() throws Exception {
        willThrow(new IncorrectPasswordException())
                .given(userService).changePassword(eq(CALLER_ID), any(UserDTO.ChangePassword.class));

        mockMvc.perform(post("/api/v1/users/me/password").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHANGE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The current password is incorrect"));

        verifyNoInteractions(refreshCookie);
    }

    @Test
    void changePasswordMapsAReusedPasswordToBadRequest() throws Exception {
        willThrow(new PasswordUnchangedException())
                .given(userService).changePassword(eq(CALLER_ID), any(UserDTO.ChangePassword.class));

        mockMvc.perform(post("/api/v1/users/me/password").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHANGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The new password must be different from the current one"));
    }

    @Test
    void changePasswordRejectsAShortOrMissingNewPassword() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "", "newPassword": "short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.currentPassword").exists())
                .andExpect(jsonPath("$.errors.newPassword").exists());

        verifyNoInteractions(userService, refreshCookie);
    }
}
