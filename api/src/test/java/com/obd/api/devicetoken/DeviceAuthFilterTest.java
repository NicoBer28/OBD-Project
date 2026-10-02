package com.obd.api.devicetoken;

import com.obd.api.auth.AppUserDetailsService;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.auth.token.SecretTokens;
import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * What the filter does with {@code Authorization: Device <token>}.
 *
 * The four checks it makes, in order, and - most importantly - the fourth:
 * the token carries no copy of the permission, so losing access to the car
 * kills it with no revocation anywhere. That is the decision this test exists
 * to pin.
 */
class DeviceAuthFilterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CAR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID TOKEN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String RAW = DeviceTokenService.PREFIX + "7eNCTDB-VT0SxYYvkExpjLBK5qtSoqspOHfwfzu6M40";

    private DeviceTokenRepository tokens;
    private AppUserDetailsService userDetailsService;
    private CarAccess carAccess;
    private DeviceAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        tokens = mock(DeviceTokenRepository.class);
        userDetailsService = mock(AppUserDetailsService.class);
        carAccess = mock(CarAccess.class);
        filter = new DeviceAuthFilter(tokens, userDetailsService, carAccess);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();

        given(userDetailsService.loadById(USER_ID)).willReturn(new UserPrincipal(
                USER_ID, "ada@example.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true));
    }

    private static DeviceToken live() {
        return DeviceToken.builder()
                .deviceTokenId(TOKEN_ID)
                .deviceTokenUserId(USER_ID)
                .deviceTokenCarId(CAR_ID)
                .deviceTokenHash(SecretTokens.hash(RAW))
                .deviceTokenLabel("Pixel de Ada")
                .deviceTokenCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();
    }

    private MockHttpServletRequest run(String header) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/telemetry");
        request.setServletPath("/api/v1/telemetry");
        if (header != null) {
            request.addHeader("Authorization", header);
        }
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return request;
    }

    private void carIsAccessible() {
        given(carAccess.readableBy(USER_ID, CAR_ID)).willReturn(Optional.of(new Car()));
    }

    // --- the happy path ------------------------------------------------------

    @Test
    void aLiveTokenAuthenticatesAsItsOwnerWithTheDeviceRole() throws Exception {
        given(tokens.findByDeviceTokenHash(SecretTokens.hash(RAW))).willReturn(Optional.of(live()));
        carIsAccessible();

        run("Device " + RAW);

        var principal = (DevicePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        // The owner's id, which is what makes every existing service work
        // unchanged - a trip this token starts is that person's trip.
        assertThat(principal.getId()).isEqualTo(USER_ID);
        assertThat(principal.getCarId()).isEqualTo(CAR_ID);
        // ROLE_DEVICE, never ROLE_USER: that is the whole permission model.
        assertThat(principal.getAuthorities()).extracting(Object::toString)
                .containsExactly(DevicePrincipal.ROLE);
    }

    @Test
    void firstUseStampsLastUsedAt() throws Exception {
        given(tokens.findByDeviceTokenHash(SecretTokens.hash(RAW))).willReturn(Optional.of(live()));
        carIsAccessible();

        run("Device " + RAW);

        verify(tokens).touch(eq(TOKEN_ID), any(Instant.class), any(Instant.class));
    }

    @Test
    void aTokenUsedSecondsAgoIsNotStampedAgain() throws Exception {
        DeviceToken justUsed = live();
        justUsed.setDeviceTokenLastUsedAt(Instant.now().minusSeconds(5));
        given(tokens.findByDeviceTokenHash(SecretTokens.hash(RAW))).willReturn(Optional.of(justUsed));
        carIsAccessible();

        run("Device " + RAW);

        // Telemetry arrives in batches; a write per request would be pure cost.
        verify(tokens, never()).touch(any(), any(), any());
    }

    // --- the four refusals ---------------------------------------------------

    @Test
    void anotherSchemeIsLeftEntirelyAlone() throws Exception {
        var request = run("Bearer eyJhbGciOi.something.else");

        // JwtAuthFilter's business. The two never interfere.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isNull();
        verifyNoInteractions(tokens);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noHeaderAtAllIsLeftAlone() throws Exception {
        var request = run(null);

        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isNull();
        verifyNoInteractions(tokens);
    }

    @Test
    void somethingWithoutOurPrefixIsRejectedWithoutTouchingTheDatabase() throws Exception {
        var request = run("Device not-one-of-ours");

        // The prefix earns its keep here: noise costs nothing to refuse.
        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isEqualTo("token_invalid");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(tokens);
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        given(tokens.findByDeviceTokenHash(any())).willReturn(Optional.empty());

        var request = run("Device " + RAW);

        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isEqualTo("token_invalid");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aRevokedTokenIsRejected() throws Exception {
        DeviceToken revoked = live();
        revoked.setDeviceTokenRevokedAt(Instant.now());
        given(tokens.findByDeviceTokenHash(SecretTokens.hash(RAW))).willReturn(Optional.of(revoked));

        var request = run("Device " + RAW);

        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isEqualTo("token_revoked");
        verifyNoInteractions(carAccess);
    }

    @Test
    void aTokenIdleFor90DaysIsRejected() throws Exception {
        DeviceToken stale = live();
        stale.setDeviceTokenCreatedAt(Instant.now().minus(200, ChronoUnit.DAYS));
        stale.setDeviceTokenLastUsedAt(Instant.now().minus(91, ChronoUnit.DAYS));
        given(tokens.findByDeviceTokenHash(SecretTokens.hash(RAW))).willReturn(Optional.of(stale));

        var request = run("Device " + RAW);

        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isEqualTo("token_revoked");
    }

    /**
     * The decision this whole design rests on.
     *
     * The token stores (userId, carId) and no copy of the permission, so the
     * car being un-shared, the owner leaving the group, the group being
     * deleted - or any rule invented later - kills the token with no
     * revocation bookkeeping anywhere. Nothing to keep in step, nothing to
     * drift.
     */
    @Test
    void aTokenWhoseOwnerLostTheCarStopsWorkingWithoutBeingRevoked() throws Exception {
        given(tokens.findByDeviceTokenHash(SecretTokens.hash(RAW))).willReturn(Optional.of(live()));
        given(carAccess.readableBy(USER_ID, CAR_ID)).willReturn(Optional.empty());

        var request = run("Device " + RAW);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // A distinct reason, because the client must NOT retry or re-mint:
        // the car is not theirs any more. The entry point turns this into 403.
        assertThat(request.getAttribute(DeviceAuthFilter.ERROR_ATTR)).isEqualTo("car_not_accessible");
        // And the row itself is untouched - nothing revoked it.
        verify(tokens, never()).revoke(any(), any(), any());
    }

    @Test
    void theRequestAlwaysContinuesDownTheChain() throws Exception {
        given(tokens.findByDeviceTokenHash(any())).willReturn(Optional.empty());

        run("Device " + RAW);

        // Refusing is the filter chain's job, not this filter's: it records
        // why and lets authorisation produce the response.
        verify(chain).doFilter(any(), any());
    }
}
