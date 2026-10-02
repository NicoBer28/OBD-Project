package com.obd.api.auth;

import com.obd.api.user.User;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class UserPrincipal implements UserDetails {
    @Getter
    private final UUID id;
    @Getter
    private final String email;
    private final String passwordHash;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;

    @Getter
    private final Instant passwordChangedAt;


    public UserPrincipal(UUID id, String email, String passwordHash, List<GrantedAuthority> authorities, boolean enable){
        this(id, email, passwordHash, authorities, enable, null);
    }

    public UserPrincipal(UUID id, String email, String passwordHash, List<GrantedAuthority> authorities,
                         boolean enable, Instant passwordChangedAt){
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.authorities = authorities;
        this.enabled = enable;
        this.passwordChangedAt = passwordChangedAt;
    }

    public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";

    public static UserPrincipal from(User user){
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (user.getUserEmailVerifiedAt() != null) {
            authorities.add(new SimpleGrantedAuthority(EMAIL_VERIFIED));
        }
        return new UserPrincipal(user.getUserId(), user.getUserEmail(), user.getUserPasswordHash(),
                List.copyOf(authorities), user.isEnabled(), user.getUserPasswordChangedAt());
    }

    public boolean isEmailVerified() {
        return authorities.contains(new SimpleGrantedAuthority(EMAIL_VERIFIED));
    }

    @NotNull
    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }
    @NotNull
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}


