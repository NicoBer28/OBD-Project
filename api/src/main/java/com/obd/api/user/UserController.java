package com.obd.api.user;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.auth.dto.AuthResponseDTO;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.refresh.RefreshCookie;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.auth.verification.EmailVerificationService;
import com.obd.api.user.dto.UserDTO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RefreshCookie refreshCookie;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;

    @GetMapping("/users/me")
    public UserDTO.Read me(@AuthenticationPrincipal UserPrincipal principal){
        return userService.me(principal.getId());
    }

    @PutMapping("/users/me")
    public UserDTO.Read update(@AuthenticationPrincipal UserPrincipal principal,
                               @RequestBody @Valid UserDTO.Update request){
        return userService.updateProfile(principal.getId(), request);
    }

    @PostMapping("/users/me/verify-email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@AuthenticationPrincipal UserPrincipal principal){
        emailVerificationService.resend(principal.getId());
    }

    @PostMapping("/users/me/password")
    public AuthResponseDTO changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                          @RequestBody @Valid UserDTO.ChangePassword request,
                                          HttpServletResponse response){
        TokenPair pair = userService.changePassword(principal.getId(), request);
        refreshCookie.set(response, pair.refreshToken(), refreshTokenService.ttl());
        return pair.auth();
    }
}
