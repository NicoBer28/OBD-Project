package com.obd.api.devicetoken;

import com.obd.api.devicetoken.exception.DeviceNotAllowedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;


@Component
public class DeviceScope {


    public void requireCar(UUID carId) {
        currentDevice().ifPresent(device -> {
            if (!device.getCarId().equals(carId)) {
                throw new DeviceNotAllowedException("This device token is registered to a different car");
            }
        });
    }

    public Optional<DevicePrincipal> currentDevice() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof DevicePrincipal device)) {
            return Optional.empty();
        }
        return Optional.of(device);
    }
}
