package com.obd.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    @Getter
    private final long accessTtMillis;

    public JwtService(@Value("${app.jwt.secret}") String base64Secret, @Value("${app.jwt.access-ttl-minutes}") long accessTtMinutes){
        this.key= Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.accessTtMillis = Duration.ofMinutes(accessTtMinutes).toMillis();
    }

    public String generateAccessToken(UserPrincipal principal){
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.getId().toString())
                .claim("email", principal.getEmail())
                .claim("roles", principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTtMillis)))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    public Claims parse(String token){
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
