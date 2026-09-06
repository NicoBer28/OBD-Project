package com.obd.api.user;

import com.obd.api.user.dto.UserDTO;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class UserMapper {

    private final PasswordEncoder encoder;

    public User mapNewUser(UserDTO.Create userDto){
        return User.builder()
                .userName(userDto.userName())
                .userLastName(userDto.userLastName())
                .userEmail(userDto.userEMail())
                .userPhone(userDto.userPhone())
                .userPasswordHash(encoder.encode(userDto.userPassword()))
                .role(Role.USER)
                .enabled(true)
                .build();
    }
}
