package com.obd.api.user;

import com.obd.api.auth.AuthService;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.devicetoken.DeviceTokenRepository;
import com.obd.api.user.dto.UserDTO;
import com.obd.api.user.exception.IncorrectPasswordException;
import com.obd.api.user.exception.PasswordUnchangedException;
import com.obd.api.user.exception.UserNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuthService authService;
    private final DeviceTokenRepository deviceTokenRepository;

    @Transactional
    public UserDTO.Read me(UUID userId){
        return UserDTO.Read.from(load(userId));
    }


    @Transactional
    public UserDTO.Read updateProfile(UUID userId, UserDTO.Update request){
        User user = load(userId);

        user.setUserName(request.userName().trim());
        user.setUserLastName(request.userLastName().trim());
        user.setUserPhone(request.userPhone() == null ? null : request.userPhone().trim());

        return UserDTO.Read.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public TokenPair changePassword(UUID userId, UserDTO.ChangePassword request){
        User user = load(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getUserPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        if (passwordEncoder.matches(request.newPassword(), user.getUserPasswordHash())) {
            throw new PasswordUnchangedException();
        }

        user.setUserPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUserPasswordChangedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        userRepository.saveAndFlush(user);

        refreshTokenService.revokeAllForUser(userId);
        deviceTokenRepository.revokeAllForUser(userId, Instant.now());

        return authService.reissue(userId);
    }

    private User load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }
}
