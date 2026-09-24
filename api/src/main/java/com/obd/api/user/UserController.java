package com.obd.api.user;

import com.obd.api.auth.UserPrincipal;
import com.obd.api.user.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/users/me")
    public UserDTO.Read me(@AuthenticationPrincipal UserPrincipal principal){
        return userService.me(principal.getId());
    }
}
