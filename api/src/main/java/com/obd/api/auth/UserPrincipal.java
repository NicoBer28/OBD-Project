package com.obd.api.auth;

import com.obd.api.user.User;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

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

    public UserPrincipal(UUID id, String email, String passwordHash, List<GrantedAuthority> authorities, boolean enable){
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.authorities = authorities;
        this.enabled = enable;
    }

    public static UserPrincipal from(User user){
        return new UserPrincipal(user.getUserId(), user.getUserEmail(), user.getUserPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())), user.isEnabled());
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


