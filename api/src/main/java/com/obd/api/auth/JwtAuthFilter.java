package com.obd.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class  JwtAuthFilter extends OncePerRequestFilter {

    public static final String ERROR_ATTR = "jwt.error";

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request){
        return PUBLIC_AUTH_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if(header == null || !header.startsWith("Bearer ")){
            chain.doFilter(request, response);
            return;
        }
        try{
            Claims claims = jwtService.parse(header.substring(7));

            if(SecurityContextHolder.getContext().getAuthentication() == null){
                UserPrincipal principal = userDetailsService.loadById(UUID.fromString(claims.getSubject()));

                var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }catch (ExpiredJwtException e){
            request.setAttribute(ERROR_ATTR, "token_expired");
            SecurityContextHolder.clearContext();
        }catch (JwtException | IllegalArgumentException | UsernameNotFoundException e){
            request.setAttribute(ERROR_ATTR, "token_invalid");
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}
