package com.obd.api.auth;

import com.obd.api.user.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @NotNull
    @Override
    @Transactional
    public UserPrincipal loadUserByUsername(@NotNull String email){
        return userRepository.findByEmail(email.trim().toLowerCase()).map(UserPrincipal::from).orElseThrow(()-> new UsernameNotFoundException("Bad credentials"));
    }

    public UserPrincipal loadById(UUID id){
        return userRepository.findById(id).map(UserPrincipal::from).orElseThrow(()-> new UsernameNotFoundException("User does not exist"));
    }
}
