package com.obd.api.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * What the filter does with an access token once a password has changed.
 *
 * Revoking refresh tokens cannot reach access tokens already issued - they are
 * self-contained and valid until they expire. This check is what makes "signed
 * out everywhere" immediate, and it is free because the filter already loads
 * the user on every request.
 */
class JwtAuthFilterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private JwtService jwtService;
    private AppUserDetailsService userDetailsService;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        // A real JwtService, so the token under test is signed and parsed the
        // way production does it - including iat at epoch-second precision.
        jwtService = new JwtService("b2JkLXRlc3Qtc2VjcmV0LWtleS0zMi1ieXRlcy1vayE=", 15);
        userDetailsService = mock(AppUserDetailsService.class);
        filter = new JwtAuthFilter(jwtService, userDetailsService);
        SecurityContextHolder.clearContext();
    }

    private static UserPrincipal principal(Instant passwordChangedAt) {
        return new UserPrincipal(USER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true, passwordChangedAt);
    }

    /** Runs the filter with a token minted for {@code tokenOwner}. */
    private MockHttpServletRequest run(UserPrincipal tokenOwner, UserPrincipal stored) throws Exception {
        given(userDetailsService.loadById(USER_ID)).willReturn(stored);

        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.setServletPath("/api/v1/users/me");
        request.addHeader("Authorization", "Bearer " + jwtService.generateAccessToken(tokenOwner));

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return request;
    }

    @Test
    void aTokenIsAcceptedWhenThePasswordHasNeverChanged() throws Exception {
        run(principal(null), principal(null));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void aTokenMintedBeforeThePasswordChangedIsRejected() throws Exception {
        // The other device's token: issued before the change, still unexpired.
        var request = run(principal(null), principal(Instant.now().plus(1, ChronoUnit.MINUTES)));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthFilter.ERROR_ATTR)).isEqualTo("token_invalid");
    }

    @Test
    void theTokenMintedByTheChangeItselfSurvives() throws Exception {
        // The stamp and the token's iat land in the same second, and the
        // comparison is "strictly before" - so the device that changed the
        // password keeps working. This is why UserService truncates the stamp.
        Instant changedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        run(principal(null), principal(changedAt));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void aTokenMintedAfterTheChangeIsAccepted() throws Exception {
        run(principal(null), principal(Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void aRequestWithNoTokenIsLeftAlone() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.setServletPath("/api/v1/users/me");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        // Anonymous here; the filter chain decides whether that is allowed.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthFilter.ERROR_ATTR)).isNull();
    }
}
