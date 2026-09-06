package com.obd.api.auth;

import com.obd.api.auth.dto.AuthResponseDTO;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.refresh.RefreshCookie;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.user.dto.UserDTO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshCookie refreshCookie;
    private final RefreshTokenService refreshTokenService;


    @PostMapping("/register")
    public AuthResponseDTO register(@RequestBody @Valid UserDTO.Create req, HttpServletResponse response){
        return respond( authService.register(req), response);
    }

    @PostMapping("/login")
    public AuthResponseDTO login(@RequestBody @Valid UserDTO.Login req, HttpServletResponse response){
        return respond(authService.login(req), response);
    }

    @PostMapping("/refresh")
    public AuthResponseDTO refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String raw, HttpServletResponse response){
        return respond(authService.refresh(raw), response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal UserPrincipal principal, HttpServletResponse response) {
        authService.logout(principal.getId());
        refreshCookie.clear(response);
    }

    private AuthResponseDTO respond(TokenPair pair, HttpServletResponse response) {
        refreshCookie.set(response, pair.refreshToken(), refreshTokenService.ttl());
        return pair.auth();
    }
}
