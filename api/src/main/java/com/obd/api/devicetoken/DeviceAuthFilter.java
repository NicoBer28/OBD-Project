package com.obd.api.devicetoken;

import com.obd.api.auth.AppUserDetailsService;
import com.obd.api.auth.JwtAuthFilter;
import com.obd.api.auth.UserPrincipal;
import com.obd.api.auth.token.SecretTokens;
import com.obd.api.car.CarAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DeviceAuthFilter extends OncePerRequestFilter {

    public static final String SCHEME = "Device ";
    public static final String ERROR_ATTR = "device.error";

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final DeviceTokenRepository deviceTokenRepository;
    private final AppUserDetailsService userDetailsService;
    private final CarAccess carAccess;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(SCHEME)) {
            chain.doFilter(request, response);
            return;
        }

        String raw = header.substring(SCHEME.length()).trim();
        if (!raw.startsWith(DeviceTokenService.PREFIX)) {
            deny(request, "token_invalid");
            chain.doFilter(request, response);
            return;
        }

        Optional<DeviceToken> found = deviceTokenRepository.findByDeviceTokenHash(SecretTokens.hash(raw));
        if (found.isEmpty()) {
            deny(request, "token_invalid");
            chain.doFilter(request, response);
            return;
        }

        DeviceToken token = found.get();
        Instant now = Instant.now();
        if (!token.isLiveAt(now)) {
            deny(request, "token_revoked");
            chain.doFilter(request, response);
            return;
        }

        if (carAccess.readableBy(token.getDeviceTokenUserId(), token.getDeviceTokenCarId()).isEmpty()) {
            deny(request, "car_not_accessible");
            chain.doFilter(request, response);
            return;
        }

        UserPrincipal owner = userDetailsService.loadById(token.getDeviceTokenUserId());
        DevicePrincipal principal = DevicePrincipal.of(token, owner);

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        touch(token, now);

        chain.doFilter(request, response);
    }

    private void touch(DeviceToken token, Instant now) {
        Instant last = token.getDeviceTokenLastUsedAt();
        if (last == null || last.isBefore(now.minus(TOUCH_INTERVAL))) {
            deviceTokenRepository.touch(token.getDeviceTokenId(), now, now.minus(TOUCH_INTERVAL));
        }
    }

    private static void deny(HttpServletRequest request, String reason) {
        request.setAttribute(ERROR_ATTR, reason);
        SecurityContextHolder.clearContext();
    }
}
