package com.obd.api.auth;

import com.obd.api.auth.dto.AuthResponseDTO;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.exception.EmailAlreadyInUseException;
import com.obd.api.auth.refresh.RefreshCookie;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.support.SliceSecurityConfig;
import com.obd.api.user.dto.UserDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the auth endpoints: routing, request validation, response
 * shape and error mapping. Everything below the controller is mocked.
 *
 * The production security chain is deliberately out of the slice (the security
 * beans are excluded): a mocked Filter would silently break the chain, and
 * JwtAuthFilter drags in JwtService/AppUserDetailsService. Whether a route is
 * actually public or protected is a wiring question, so it belongs to a
 * full-context test against the real chain, not here. SliceSecurityConfig
 * stands in - a permissive chain that still loads the SecurityContext, which
 * logout's @AuthenticationPrincipal needs.
 */
@WebMvcTest(controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthFilter.class, JwtAuthEntryPoint.class}))
@Import(SliceSecurityConfig.class)
class AuthControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private RefreshCookie refreshCookie;
    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private static TokenPair tokenPair() {
        return new TokenPair(
                new AuthResponseDTO("header.payload.signature", "Bearer", 900, USER_ID, "ada@example.com"),
                "raw-refresh-token");
    }

    private static final String VALID_REGISTRATION = """
            {
              "userName": "Ada",
              "userLastName": "Lovelace",
              "userEmail": "ada@example.com",
              "userPassword": "supersecret123",
              "userPhone": "+39 320 1234567"
            }
            """;

    @Test
    void registerReturnsTheAccessTokenEnvelope() throws Exception {
        given(refreshTokenService.ttl()).willReturn(Duration.ofDays(14));
        given(authService.register(any(UserDTO.Create.class))).willReturn(tokenPair());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REGISTRATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expireInSeconds").value(900))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                // The refresh token is a cookie concern - it must never leak
                // into the JSON body.
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void registerRejectsAnInvalidPayloadWithAPerFieldErrorMap() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userName": "",
                                  "userLastName": "Lovelace",
                                  "userEmail": "not-an-email",
                                  "userPassword": "short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.userName").exists())
                .andExpect(jsonPath("$.errors.userEmail").exists())
                .andExpect(jsonPath("$.errors.userPassword").exists());
    }

    @Test
    void registerMapsADuplicateEmailToConflict() throws Exception {
        willThrow(new EmailAlreadyInUseException("ada@example.com"))
                .given(authService).register(any(UserDTO.Create.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REGISTRATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("That email is already registered"))
                // The address itself must not be echoed back - that would make
                // the endpoint an account-existence oracle.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ada@example.com"))));
    }

    @Test
    void loginReturnsTheAccessTokenEnvelope() throws Exception {
        given(refreshTokenService.ttl()).willReturn(Duration.ofDays(14));
        given(authService.login(any(UserDTO.Login.class))).willReturn(tokenPair());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userEmail": "ada@example.com", "userPassword": "supersecret123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()));
    }

    @Test
    void loginMapsBadCredentialsToUnauthorized() throws Exception {
        willThrow(new BadCredentialsException("Bad credentials"))
                .given(authService).login(any(UserDTO.Login.class));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userEmail": "ada@example.com", "userPassword": "wrongpassword"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void refreshWithoutACookieIsUnauthorized() throws Exception {
        willThrow(new BadCredentialsException("No refresh token"))
                .given(authService).refresh(null);

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesUsingTheCookieValue() throws Exception {
        given(refreshTokenService.ttl()).willReturn(Duration.ofDays(14));
        given(authService.refresh("raw-refresh-token")).willReturn(tokenPair());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(RefreshCookie.NAME, "raw-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"));
    }

    // --- logout --------------------------------------------------------------

    /** Puts a real UserPrincipal in the SecurityContext, as JwtAuthFilter would. */
    private static RequestPostProcessor caller() {
        var principal = new UserPrincipal(USER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    void logoutRevokesTheCallersRefreshTokensAndClearsTheCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(caller()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // Whose tokens: the principal's, never anything in the request.
        verify(authService).logout(USER_ID);
        verify(refreshCookie).clear(any(HttpServletResponse.class));
    }
}
