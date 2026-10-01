package com.obd.api.user;

import com.obd.api.user.exception.EmailNotVerifiedException;
import com.obd.api.user.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserAccess {

    private final UserRepository userRepository;

    public void requireVerifiedEmail(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getUserEmailVerifiedAt() == null) {
            throw new EmailNotVerifiedException(userId);
        }
    }
}
