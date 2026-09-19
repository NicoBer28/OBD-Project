package com.obd.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A permissive filter chain for controller slices that need an authenticated
 * caller.
 *
 * Turning filters off entirely ({@code @AutoConfigureMockMvc(addFilters=false)})
 * looks simpler but breaks {@code @AuthenticationPrincipal}: without
 * SecurityContextHolderFilter nothing loads the context that
 * {@code .with(authentication(...))} stored on the request, so the principal
 * arrives null. Keeping a chain that authorises everything gives the slice a
 * real SecurityContext while leaving authorisation rules - which belong to a
 * full-context test against the production chain - out of scope.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SliceSecurityConfig {

    @Bean
    SecurityFilterChain sliceFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
