package com.obd.api.user;

import com.obd.api.user.dto.UserDTO;
import com.obd.api.user.exception.UserNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserDTO.Read me(UUID userId){
        User user = userRepository.findById(userId).orElseThrow(()-> new UserNotFoundException(userId));
        return UserDTO.Read.from(user);
    }
}
