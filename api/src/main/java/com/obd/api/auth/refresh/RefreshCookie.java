package com.obd.api.auth.refresh;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RefreshCookie {

    public static final String NAME = "refreshToken";
    private static final String PATH = "/api/v1/auth";

    @Value("${app.cookie.secure:true}")
    private boolean secure;

    public void set(HttpServletResponse response, String rawToken, Duration ttl) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, ttl).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }

}
