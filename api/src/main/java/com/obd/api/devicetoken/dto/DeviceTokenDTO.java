package com.obd.api.devicetoken.dto;

import com.obd.api.devicetoken.DeviceToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class DeviceTokenDTO {

    public record Create(
            @NotBlank @Size(max = 60) String label
    ) {}


    public record Minted(
            UUID id,
            UUID carId,
            String label,
            String token,
            Instant createdAt,
            Instant idleExpiresAt
    ) {
        public static Minted of(DeviceToken t, String rawToken) {
            return new Minted(t.getDeviceTokenId(), t.getDeviceTokenCarId(), t.getDeviceTokenLabel(),
                    rawToken, t.getDeviceTokenCreatedAt(), t.idleDeadline());
        }
    }

    public record Read(
            UUID id,
            UUID carId,
            String label,
            Instant createdAt,
            Instant lastUsedAt,
            Instant idleExpiresAt
    ) {
        public static Read from(DeviceToken t) {
            return new Read(t.getDeviceTokenId(), t.getDeviceTokenCarId(), t.getDeviceTokenLabel(),
                    t.getDeviceTokenCreatedAt(), t.getDeviceTokenLastUsedAt(), t.idleDeadline());
        }
    }
}
