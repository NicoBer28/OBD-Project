package com.obd.api.devicetoken;

import com.obd.api.auth.UserPrincipal;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

public class DevicePrincipal extends UserPrincipal {

    public static final String ROLE = "ROLE_DEVICE";

    /** The only car this credential may touch */
    @Getter
    private final UUID carId;

    @Getter
    private final UUID tokenId;

    public DevicePrincipal(UserPrincipal owner, UUID carId, UUID tokenId) {
        super(owner.getId(), owner.getEmail(), owner.getPassword(),
                List.of(new SimpleGrantedAuthority(ROLE)), owner.isEnabled(),
                owner.getPasswordChangedAt());
        this.carId = carId;
        this.tokenId = tokenId;
    }

    public static DevicePrincipal of(DeviceToken token, UserPrincipal owner) {
        return new DevicePrincipal(owner, token.getDeviceTokenCarId(), token.getDeviceTokenId());
    }
}
