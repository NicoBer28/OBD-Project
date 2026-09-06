package com.obd.api.auth;

import com.obd.api.auth.dto.AuthResponseDTO;
import com.obd.api.auth.dto.TokenPair;
import com.obd.api.auth.exception.EmailAlreadyInUseException;
import com.obd.api.auth.refresh.RefreshTokenService;
import com.obd.api.user.User;
import com.obd.api.user.UserMapper;
import com.obd.api.user.UserRepository;
import com.obd.api.user.dto.UserDTO;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.antlr.v4.runtime.Token;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper;
    private final RefreshTokenService refreshTokenService;
    private final AppUserDetailsService userDetailsService;

    @Transactional
    public TokenPair register(UserDTO.Create userDto){
        User user = userMapper.mapNewUser(userDto);
        try{
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e){
            throw new EmailAlreadyInUseException(user.getUserEmail());
        }
        return pairFor(UserPrincipal.from(user), true);

    }

    public TokenPair login(UserDTO.Login userDto){
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(userDto.userMail().trim().toLowerCase(), userDto.userPassword()));
        return pairFor((UserPrincipal) authentication.getPrincipal(), true);
    }

    public TokenPair refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BadCredentialsException("No refresh token");
        }
        var rotation = refreshTokenService.rotate(rawRefreshToken);
        UserPrincipal principal = userDetailsService.loadById(rotation.userId());
        return new TokenPair(accessResponse(principal), rotation.newRawToken());
    }

    private AuthResponseDTO issue(UserPrincipal principal){
        return new AuthResponseDTO(jwtService.generateAccessToken(principal), "Bearer", jwtService.getAccessTtMillis()/1000,principal.getId(),principal.getEmail());
    }

    public void logout(UUID userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    private TokenPair pairFor(UserPrincipal principal, boolean newFamily) {
        String refresh = newFamily ? refreshTokenService.issueNewFamily(principal.getId()) : null;
        return new TokenPair(accessResponse(principal), refresh);
    }

    private AuthResponseDTO accessResponse(UserPrincipal principal) {
        return new AuthResponseDTO( jwtService.generateAccessToken(principal), "Bearer",  jwtService.getAccessTtMillis() / 1000, principal.getId(), principal.getEmail());
    }

}
